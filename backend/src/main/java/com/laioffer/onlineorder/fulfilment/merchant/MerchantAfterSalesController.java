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
 * The merchant's after-sales endpoints (ROLE_MERCHANT only, see AppConfig; ADR 0002 section 11.3). The merchant
 * answers each request once, before its deadline: repeating the same answer returns the case unchanged, any other
 * answer to a case that is no longer PENDING_MERCHANT is 409 AFTER_SALES_STATUS_CONFLICT, and an answer from the
 * deadline on is 409 AFTER_SALES_DEADLINE_PASSED. A case of another restaurant is 404, like a missing one.
 */
@RestController
public class MerchantAfterSalesController {

    private final CustomerService customerService;
    private final MerchantAfterSalesService merchantAfterSalesService;


    public MerchantAfterSalesController(
            CustomerService customerService,
            MerchantAfterSalesService merchantAfterSalesService
    ) {
        this.customerService = customerService;
        this.merchantAfterSalesService = merchantAfterSalesService;
    }


    @GetMapping("/merchant/after-sales")
    public List<MerchantAfterSalesCaseDto> getCases(
            Principal principal,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return merchantAfterSalesService.getCases(merchantId(principal), status, limit);
    }


    @PostMapping("/merchant/after-sales/{caseId}/approve")
    public MerchantAfterSalesCaseDto approve(Principal principal, @PathVariable Long caseId) {
        return merchantAfterSalesService.approve(merchantId(principal), caseId);
    }


    @PostMapping("/merchant/after-sales/{caseId}/reject")
    public MerchantAfterSalesCaseDto reject(
            Principal principal,
            @PathVariable Long caseId,
            @Valid @RequestBody RejectAfterSalesBody body
    ) {
        return merchantAfterSalesService.reject(merchantId(principal), caseId, body.reason());
    }


    @PostMapping("/merchant/after-sales/{caseId}/offer")
    public MerchantAfterSalesCaseDto offer(
            Principal principal,
            @PathVariable Long caseId,
            @Valid @RequestBody OfferAfterSalesBody body
    ) {
        return merchantAfterSalesService.offer(merchantId(principal), caseId, body.amount(), body.note());
    }


    /**
     * The logged-in merchant's customer id, resolved like every other controller resolves its principal.
     */
    private Long merchantId(Principal principal) {
        return customerService.getCustomerByEmail(principal.getName()).id();
    }
}
