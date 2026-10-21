package com.laioffer.onlineorder.fulfilment.merchant;


import com.laioffer.onlineorder.service.CustomerService;
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
 * already happened returns the order unchanged. A reject of an order that was cancelled for another reason (by the
 * customer or the accept timeout) is 409 ORDER_STATUS_CONFLICT, not a successful reject.
 */
@RestController
public class MerchantOrderController {

    private final CustomerService customerService;
    private final MerchantOrderService merchantOrderService;


    public MerchantOrderController(CustomerService customerService, MerchantOrderService merchantOrderService) {
        this.customerService = customerService;
        this.merchantOrderService = merchantOrderService;
    }


    @GetMapping("/merchant/orders")
    public List<MerchantOrderDto> getOrders(
            Principal principal,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return merchantOrderService.getOrders(merchantId(principal), status, limit);
    }


    @PostMapping("/merchant/orders/{orderId:\\d+}/accept")
    public MerchantOrderDto accept(Principal principal, @PathVariable Long orderId) {
        return merchantOrderService.accept(merchantId(principal), orderId);
    }


    @PostMapping("/merchant/orders/{orderId:\\d+}/reject")
    public MerchantOrderDto reject(
            Principal principal,
            @PathVariable Long orderId,
            @Valid @RequestBody RejectOrderBody body
    ) {
        return merchantOrderService.reject(merchantId(principal), orderId, body.reason());
    }


    @PostMapping("/merchant/orders/{orderId:\\d+}/preparing")
    public MerchantOrderDto markPreparing(Principal principal, @PathVariable Long orderId) {
        return merchantOrderService.markPreparing(merchantId(principal), orderId);
    }


    /**
     * The logged-in merchant's customer id, resolved like every other controller resolves its principal.
     */
    private Long merchantId(Principal principal) {
        return customerService.getCustomerByEmail(principal.getName()).id();
    }
}
