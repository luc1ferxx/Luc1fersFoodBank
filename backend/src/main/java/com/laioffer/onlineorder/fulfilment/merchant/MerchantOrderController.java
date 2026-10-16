package com.laioffer.onlineorder.fulfilment.merchant;


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
 * Merchant endpoints (ROLE_MERCHANT only, see AppConfig). Commands are idempotent: repeating one whose transition
 * already happened returns the order unchanged.
 */
@RestController
public class MerchantOrderController {

    private final MerchantOrderService merchantOrderService;


    public MerchantOrderController(MerchantOrderService merchantOrderService) {
        this.merchantOrderService = merchantOrderService;
    }


    @GetMapping("/merchant/orders")
    public List<MerchantOrderDto> getOrders(
            Principal principal,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return merchantOrderService.getOrders(principal.getName(), status, limit);
    }


    @PostMapping("/merchant/orders/{orderId}/accept")
    public MerchantOrderDto accept(Principal principal, @PathVariable Long orderId) {
        return merchantOrderService.accept(principal.getName(), orderId);
    }


    @PostMapping("/merchant/orders/{orderId}/reject")
    public MerchantOrderDto reject(
            Principal principal,
            @PathVariable Long orderId,
            @Valid @RequestBody RejectOrderBody body
    ) {
        return merchantOrderService.reject(principal.getName(), orderId, body.reason());
    }


    @PostMapping("/merchant/orders/{orderId}/preparing")
    public MerchantOrderDto markPreparing(Principal principal, @PathVariable Long orderId) {
        return merchantOrderService.markPreparing(principal.getName(), orderId);
    }
}
