package com.laioffer.onlineorder.fulfilment.dispatch;


import com.laioffer.onlineorder.audit.AuditActions;
import com.laioffer.onlineorder.audit.AuditActor;
import com.laioffer.onlineorder.audit.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;


/**
 * The admin's simulated rider actions (POST /admin/deliveries/{id}/picked-up and /delivered), audited.
 *
 * <p>Each action is {@link DispatchService}'s guarded delivery transition plus one audit_log entry naming the admin,
 * in one transaction. The delivery row is locked first, so the status read here is the one the transition starts from:
 * a repeat of an action that already happened (the transition returns the delivery unchanged) and a refused one (404,
 * 409, rolled back) add no entry. The order history of the completed order still names the delivery's rider (the
 * order module learns about the delivery only from the delivery.delivered event); this entry is the record that an
 * admin performed it.
 */
@Service
public class DeliveryAdminService {

    private final DispatchService dispatchService;
    private final DeliveryRepository deliveryRepository;
    private final AuditLogService auditLogService;


    public DeliveryAdminService(
            DispatchService dispatchService,
            DeliveryRepository deliveryRepository,
            AuditLogService auditLogService
    ) {
        this.dispatchService = dispatchService;
        this.deliveryRepository = deliveryRepository;
        this.auditLogService = auditLogService;
    }


    @Transactional
    public DeliveryDto markPickedUp(Long deliveryId, AuditActor actor) {
        return advance(deliveryId, DeliveryStatus.PICKED_UP, AuditActions.DELIVERY_PICKED_UP, actor,
                dispatchService::markPickedUp);
    }


    @Transactional
    public DeliveryDto markDelivered(Long deliveryId, AuditActor actor) {
        return advance(deliveryId, DeliveryStatus.DELIVERED, AuditActions.DELIVERY_DELIVERED, actor,
                dispatchService::markDelivered);
    }


    private DeliveryDto advance(
            Long deliveryId,
            DeliveryStatus target,
            String action,
            AuditActor actor,
            Function<Long, DeliveryDto> transition
    ) {
        // Taken before the transition, which locks the same row again in this transaction.
        DeliveryEntity before = deliveryRepository.lockById(deliveryId);
        DeliveryDto after = transition.apply(deliveryId);
        if (before != null && !target.name().equals(before.status())) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("order_id", after.orderId());
            details.put("rider_id", after.riderId());
            details.put("from_status", before.status());
            details.put("to_status", after.status());
            auditLogService.record(actor, action, AuditActions.TARGET_DELIVERY, deliveryId, details);
        }
        return after;
    }
}
