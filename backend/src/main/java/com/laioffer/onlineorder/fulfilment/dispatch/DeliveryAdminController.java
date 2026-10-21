package com.laioffer.onlineorder.fulfilment.dispatch;


import com.laioffer.onlineorder.audit.AuditActorResolver;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


import java.security.Principal;
import java.util.List;


/**
 * Rider actions on any delivery, for ops and tests, and reassigning an open delivery to another rider (ROLE_ADMIN only,
 * see AppConfig), each recorded in the audit log with the admin who performed it. Riders themselves use
 * {@link RiderController}. Marking a delivery picked up or delivered here is a simulation: delivered completes the
 * customer's order, so it is not a way to clear a deactivated rider's deliveries; reassign them instead.
 */
@RestController
public class DeliveryAdminController {

    private final DispatchService dispatchService;
    private final DeliveryAdminService deliveryAdminService;
    private final AuditActorResolver auditActorResolver;


    public DeliveryAdminController(
            DispatchService dispatchService,
            DeliveryAdminService deliveryAdminService,
            AuditActorResolver auditActorResolver
    ) {
        this.dispatchService = dispatchService;
        this.deliveryAdminService = deliveryAdminService;
        this.auditActorResolver = auditActorResolver;
    }


    /**
     * @param orderId one order's delivery
     * @param riderId a rider's open deliveries (ASSIGNED and PICKED_UP), for example before or after deactivating them
     */
    @GetMapping("/admin/deliveries")
    public List<DeliveryDto> getDeliveries(
            @RequestParam(name = "order_id", required = false) Long orderId,
            @RequestParam(name = "rider_id", required = false) Long riderId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return dispatchService.getDeliveries(orderId, riderId, limit);
    }


    @PostMapping("/admin/deliveries/{deliveryId:\\d+}/picked-up")
    public DeliveryDto markPickedUp(Principal principal, @PathVariable Long deliveryId) {
        return deliveryAdminService.markPickedUp(deliveryId, auditActorResolver.resolve(principal));
    }


    @PostMapping("/admin/deliveries/{deliveryId:\\d+}/delivered")
    public DeliveryDto markDelivered(Principal principal, @PathVariable Long deliveryId) {
        return deliveryAdminService.markDelivered(deliveryId, auditActorResolver.resolve(principal));
    }


    /**
     * Moves an open delivery to another active, on-duty rider (body {"rider_id": n}); idempotent for the same rider.
     */
    @PostMapping("/admin/deliveries/{deliveryId:\\d+}/reassign")
    public DeliveryDto reassign(
            Principal principal,
            @PathVariable Long deliveryId,
            @Valid @RequestBody ReassignDeliveryBody body
    ) {
        return deliveryAdminService.reassign(deliveryId, body.riderId(), auditActorResolver.resolve(principal));
    }
}
