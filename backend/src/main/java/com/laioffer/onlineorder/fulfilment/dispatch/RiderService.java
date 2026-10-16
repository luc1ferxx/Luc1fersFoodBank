package com.laioffer.onlineorder.fulfilment.dispatch;


import com.laioffer.onlineorder.audit.AuditActions;
import com.laioffer.onlineorder.audit.AuditActor;
import com.laioffer.onlineorder.audit.AuditLogService;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


/**
 * Riders as accounts: the dispatch module's API for admin provisioning (com.laioffer.onlineorder.staff), GET /me and
 * the rider's own endpoints (/rider/**). The module only touches its own riders and deliveries tables; the caller
 * passes the login's customer id, resolved by the web layer like every other controller does.
 *
 * <p>Every rider request looks the rider up again by login, so deactivating a rider takes effect on their next
 * request (403) although the session still carries ROLE_RIDER. A rider only ever reaches their own deliveries: another
 * rider's delivery is reported as 404, the same as one that does not exist.
 *
 * <p>Each change writes one audit_log entry in its transaction: an admin's {@link #update} (rider.update), the rider's
 * own duty change (rider.availability) and their pick-up and delivery (delivery.picked_up, delivery.delivered, the same
 * actions as the admin's simulated ones, with ROLE_RIDER). A repeat that changes nothing and a refused request write
 * none.
 */
@Service
public class RiderService {

    static final String RIDER_INACTIVE = "RIDER_INACTIVE";

    private static final Logger LOGGER = LoggerFactory.getLogger(RiderService.class);

    private final RiderRepository riderRepository;
    private final DeliveryRepository deliveryRepository;
    private final DispatchService dispatchService;
    private final AuditLogService auditLogService;


    public RiderService(
            RiderRepository riderRepository,
            DeliveryRepository deliveryRepository,
            DispatchService dispatchService,
            AuditLogService auditLogService
    ) {
        this.riderRepository = riderRepository;
        this.deliveryRepository = deliveryRepository;
        this.dispatchService = dispatchService;
        this.auditLogService = auditLogService;
    }


    /**
     * Binds the login to a new rider: active, off duty (the rider, or an admin, puts them on duty) and without
     * deliveries.
     */
    @Transactional
    public RiderEntity create(Long customerId, String name, String phone) {
        LocalDateTime now = LocalDateTime.now();
        RiderEntity rider = riderRepository.save(new RiderEntity(
                null, customerId, name, phone, true, false, 0, now, now));
        LOGGER.info("Rider created: rider_id={}, customer_id={}", rider.id(), customerId);
        return rider;
    }


    /**
     * Admin change of a rider's flags (PATCH /admin/riders/{riderId}); a null flag keeps its value. Deactivating takes
     * the rider off duty unless the same change says otherwise, and an inactive rider can never be on duty (409
     * RIDER_INACTIVE). An admin putting a rider on duty is the operator's lever when no rider is on duty (README "No
     * rider on duty"); otherwise riders set it themselves.
     *
     * <p>The rider row is locked first, so the change cannot interleave with the rider's own availability change or a
     * dispatch assignment of this rider. Deliveries the rider already holds stay assigned to them; an operator moves
     * them to another rider with {@link DispatchService#reassign}.
     *
     * @param actor the admin, recorded with a change in the audit log (rider.update)
     * @throws ResourceNotFoundException when there is no such rider
     * @throws ConflictException         RIDER_INACTIVE when the change would leave an inactive rider on duty
     */
    @Transactional
    public RiderEntity update(Long riderId, Boolean active, Boolean onDuty, AuditActor actor) {
        RiderEntity rider = riderRepository.lockById(riderId);
        if (rider == null) {
            throw new ResourceNotFoundException("Rider not found");
        }
        boolean newActive = active == null ? rider.active() : active;
        boolean newOnDuty = onDuty == null ? newActive && rider.onDuty() : onDuty;
        if (newOnDuty && !newActive) {
            throw new ConflictException(RIDER_INACTIVE, "An inactive rider cannot be on duty");
        }
        if (newActive == rider.active() && newOnDuty == rider.onDuty()) {
            return rider;
        }
        LocalDateTime now = LocalDateTime.now();
        riderRepository.updateStatus(riderId, newActive, newOnDuty, now);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("customer_id", rider.customerId());
        details.put("from_active", rider.active());
        details.put("to_active", newActive);
        details.put("from_on_duty", rider.onDuty());
        details.put("to_on_duty", newOnDuty);
        details.put("open_deliveries", rider.activeDeliveries());
        auditLogService.record(actor, AuditActions.RIDER_UPDATE, AuditActions.TARGET_RIDER, riderId, details);
        LOGGER.info(
                "Rider updated by an admin: rider_id={}, active={}, on_duty={}, open_deliveries={}",
                riderId,
                newActive,
                newOnDuty,
                rider.activeDeliveries()
        );
        return new RiderEntity(
                rider.id(),
                rider.customerId(),
                rider.name(),
                rider.phone(),
                newActive,
                newOnDuty,
                rider.activeDeliveries(),
                rider.createdAt(),
                now
        );
    }


    public List<RiderEntity> findAll() {
        return riderRepository.findAllOrderById();
    }


    public Optional<RiderEntity> findByCustomerId(Long customerId) {
        return customerId == null ? Optional.empty() : Optional.ofNullable(riderRepository.findByCustomerId(customerId));
    }


    /**
     * @throws AccessDeniedException (403) when the login is not bound to a rider or the rider is deactivated
     */
    public RiderEntity requireActiveRider(Long customerId) {
        RiderEntity rider = findByCustomerId(customerId)
                .orElseThrow(() -> new AccessDeniedException("Login is not bound to a rider"));
        if (!rider.active()) {
            throw new AccessDeniedException("Rider is deactivated");
        }
        return rider;
    }


    /**
     * Going off duty only stops new assignments: deliveries the rider holds stay theirs to finish. The write only
     * applies while the rider is active, so a deactivation that lands between the check and the write cannot leave an
     * inactive rider on duty (that rider would become dispatchable the moment an admin reactivates them).
     *
     * @throws AccessDeniedException (403) when the rider is deactivated, also when that happened concurrently
     */
    @Transactional
    public RiderEntity setOnDuty(Long customerId, boolean onDuty) {
        RiderEntity rider = requireActiveRider(customerId);
        if (rider.onDuty() == onDuty) {
            return rider;
        }
        if (riderRepository.updateOnDutyIfActive(rider.id(), onDuty, LocalDateTime.now()) == 0) {
            throw new AccessDeniedException("Rider is deactivated");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from_on_duty", rider.onDuty());
        details.put("to_on_duty", onDuty);
        auditLogService.record(
                AuditActor.rider(customerId),
                AuditActions.RIDER_AVAILABILITY,
                AuditActions.TARGET_RIDER,
                rider.id(),
                details
        );
        LOGGER.info("Rider {} duty: rider_id={}", onDuty ? "on" : "off", rider.id());
        return riderRepository.findById(rider.id()).orElseThrow(() -> new ResourceNotFoundException("Rider not found"));
    }


    public List<DeliveryEntity> getDeliveries(Long customerId, Collection<DeliveryStatus> statuses, int limit) {
        RiderEntity rider = requireActiveRider(customerId);
        List<String> statusNames = statuses.stream().map(DeliveryStatus::name).toList();
        return deliveryRepository.findByRiderIdAndStatuses(rider.id(), statusNames, Math.max(1, Math.min(limit, 100)));
    }


    @Transactional
    public DeliveryEntity markPickedUp(Long customerId, Long deliveryId) {
        return advance(customerId, deliveryId, DeliveryStatus.PICKED_UP, AuditActions.DELIVERY_PICKED_UP);
    }


    @Transactional
    public DeliveryEntity markDelivered(Long customerId, Long deliveryId) {
        return advance(customerId, deliveryId, DeliveryStatus.DELIVERED, AuditActions.DELIVERY_DELIVERED);
    }


    /**
     * The rider's own delivery move, audited when it changed the delivery. The delivery row is locked first (the
     * transition locks it again in this transaction), so the status read here is the one the transition starts from.
     */
    private DeliveryEntity advance(Long customerId, Long deliveryId, DeliveryStatus target, String action) {
        Long riderId = requireActiveRider(customerId).id();
        DeliveryEntity before = deliveryRepository.lockByIdAndRiderId(deliveryId, riderId);
        DeliveryEntity after = dispatchService.advanceForRider(riderId, deliveryId, target);
        if (before != null && !target.name().equals(before.status())) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("order_id", after.orderId());
            details.put("rider_id", riderId);
            details.put("from_status", before.status());
            details.put("to_status", after.status());
            auditLogService.record(
                    AuditActor.rider(customerId), action, AuditActions.TARGET_DELIVERY, deliveryId, details);
        }
        return after;
    }
}
