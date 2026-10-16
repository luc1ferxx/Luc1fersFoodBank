package com.laioffer.onlineorder.fulfilment.dispatch;


import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


import java.util.List;


/**
 * Rider actions on any delivery, for ops and tests (ROLE_ADMIN only, see AppConfig). Riders themselves use
 * {@link RiderController}.
 */
@RestController
public class DeliveryAdminController {

    private final DispatchService dispatchService;


    public DeliveryAdminController(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }


    @GetMapping("/admin/deliveries")
    public List<DeliveryDto> getDeliveries(
            @RequestParam(name = "order_id", required = false) Long orderId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return dispatchService.getDeliveries(orderId, limit);
    }


    @PostMapping("/admin/deliveries/{deliveryId}/picked-up")
    public DeliveryDto markPickedUp(@PathVariable Long deliveryId) {
        return dispatchService.markPickedUp(deliveryId);
    }


    @PostMapping("/admin/deliveries/{deliveryId}/delivered")
    public DeliveryDto markDelivered(@PathVariable Long deliveryId) {
        return dispatchService.markDelivered(deliveryId);
    }
}
