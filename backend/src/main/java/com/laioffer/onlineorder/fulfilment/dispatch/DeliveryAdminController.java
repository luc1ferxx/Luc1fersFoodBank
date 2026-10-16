package com.laioffer.onlineorder.fulfilment.dispatch;


import com.laioffer.onlineorder.audit.AuditActorResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


import java.security.Principal;
import java.util.List;


/**
 * Simulated rider actions for demos and tests (ROLE_ADMIN only, see AppConfig), recorded in the audit log with the
 * admin who performed them.
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


    @GetMapping("/admin/deliveries")
    public List<DeliveryDto> getDeliveries(
            @RequestParam(name = "order_id", required = false) Long orderId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return dispatchService.getDeliveries(orderId, limit);
    }


    @PostMapping("/admin/deliveries/{deliveryId}/picked-up")
    public DeliveryDto markPickedUp(Principal principal, @PathVariable Long deliveryId) {
        return deliveryAdminService.markPickedUp(deliveryId, auditActorResolver.resolve(principal));
    }


    @PostMapping("/admin/deliveries/{deliveryId}/delivered")
    public DeliveryDto markDelivered(Principal principal, @PathVariable Long deliveryId) {
        return deliveryAdminService.markDelivered(deliveryId, auditActorResolver.resolve(principal));
    }
}
