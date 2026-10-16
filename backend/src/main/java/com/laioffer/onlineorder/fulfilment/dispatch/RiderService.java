package com.laioffer.onlineorder.fulfilment.dispatch;


import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;


/**
 * Riders as accounts: the dispatch module's API for admin provisioning (com.laioffer.onlineorder.staff), GET /me and
 * the rider's own endpoints (/rider/**). The module only touches its own riders and deliveries tables; the caller
 * passes the login's customer id, resolved by the web layer like every other controller does.
 *
 * <p>Every rider request looks the rider up again by login, so deactivating a rider takes effect on their next
 * request (403) although the session still carries ROLE_RIDER. A rider only ever reaches their own deliveries: another
 * rider's delivery is reported as 404, the same as one that does not exist.
 */
@Service
public class RiderService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RiderService.class);

    private final RiderRepository riderRepository;
    private final DeliveryRepository deliveryRepository;
    private final DispatchService dispatchService;


    public RiderService(
            RiderRepository riderRepository,
            DeliveryRepository deliveryRepository,
            DispatchService dispatchService
    ) {
        this.riderRepository = riderRepository;
        this.deliveryRepository = deliveryRepository;
        this.dispatchService = dispatchService;
    }


    /**
     * Binds the login to a new rider: active, off duty (the rider goes on duty themselves) and without deliveries.
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
     * Deactivating also takes the rider off duty. Deliveries the rider already holds stay assigned to them; an operator
     * moves them with the admin delivery endpoints (or reactivates the rider).
     *
     * @throws ResourceNotFoundException when there is no such rider
     */
    @Transactional
    public RiderEntity setActive(Long riderId, boolean active) {
        if (riderRepository.updateActive(riderId, active, LocalDateTime.now()) == 0) {
            throw new ResourceNotFoundException("Rider not found");
        }
        RiderEntity rider = riderRepository.findById(riderId)
                .orElseThrow(() -> new ResourceNotFoundException("Rider not found"));
        LOGGER.info(
                "Rider {}: rider_id={}, open_deliveries={}",
                active ? "activated" : "deactivated",
                riderId,
                rider.activeDeliveries()
        );
        return rider;
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
     * Going off duty only stops new assignments: deliveries the rider holds stay theirs to finish.
     */
    @Transactional
    public RiderEntity setOnDuty(Long customerId, boolean onDuty) {
        RiderEntity rider = requireActiveRider(customerId);
        if (rider.onDuty() == onDuty) {
            return rider;
        }
        riderRepository.updateOnDuty(rider.id(), onDuty, LocalDateTime.now());
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
        return dispatchService.advanceForRider(requireActiveRider(customerId).id(), deliveryId, DeliveryStatus.PICKED_UP);
    }


    @Transactional
    public DeliveryEntity markDelivered(Long customerId, Long deliveryId) {
        return dispatchService.advanceForRider(requireActiveRider(customerId).id(), deliveryId, DeliveryStatus.DELIVERED);
    }
}
