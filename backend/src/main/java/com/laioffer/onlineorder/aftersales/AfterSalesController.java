package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.service.CustomerService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


import java.security.Principal;


/**
 * The customer's after-sales endpoints (ADR 0002 section 11.2). Any logged-in user reaches them
 * ({@code anyRequest().authenticated()} in AppConfig): the service only shows and changes the caller's own orders and
 * cases, and answers 404 for anyone else's. Every command needs an Idempotency-Key; repeating a request with its key
 * answers with the case the first one opened or changed.
 */
@RestController
public class AfterSalesController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final CustomerService customerService;
    private final AfterSalesCustomerService afterSalesCustomerService;


    public AfterSalesController(CustomerService customerService, AfterSalesCustomerService afterSalesCustomerService) {
        this.customerService = customerService;
        this.afterSalesCustomerService = afterSalesCustomerService;
    }


    @GetMapping("/after-sales/cases")
    public AfterSalesOverviewDto getOverview(Principal principal, @RequestParam(defaultValue = "50") int limit) {
        return afterSalesCustomerService.getOverview(customerId(principal), limit);
    }


    @GetMapping("/after-sales/cases/{caseId}")
    public AfterSalesCaseDto getCase(Principal principal, @PathVariable Long caseId) {
        return afterSalesCustomerService.getCase(customerId(principal), caseId);
    }


    @PostMapping("/orders/{orderId}/after-sales")
    public AfterSalesCaseDto open(
            Principal principal,
            @PathVariable Long orderId,
            @Valid @RequestBody OpenAfterSalesBody body,
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey
    ) {
        return afterSalesCustomerService.open(customerId(principal), orderId, body, idempotencyKey);
    }


    @PostMapping("/after-sales/cases/{caseId}/accept-offer")
    public AfterSalesCaseDto acceptOffer(
            Principal principal,
            @PathVariable Long caseId,
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey
    ) {
        return afterSalesCustomerService.acceptOffer(customerId(principal), caseId, idempotencyKey);
    }


    @PostMapping("/after-sales/cases/{caseId}/decline-offer")
    public AfterSalesCaseDto declineOffer(
            Principal principal,
            @PathVariable Long caseId,
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey
    ) {
        return afterSalesCustomerService.declineOffer(customerId(principal), caseId, idempotencyKey);
    }


    /**
     * The body, with the customer's note for the platform, is optional.
     */
    @PostMapping("/after-sales/cases/{caseId}/escalate")
    public AfterSalesCaseDto escalate(
            Principal principal,
            @PathVariable Long caseId,
            @Valid @RequestBody(required = false) EscalateAfterSalesBody body,
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey
    ) {
        String note = body == null ? null : body.note();
        return afterSalesCustomerService.escalate(customerId(principal), caseId, note, idempotencyKey);
    }


    @PostMapping("/after-sales/cases/{caseId}/withdraw")
    public AfterSalesCaseDto withdraw(
            Principal principal,
            @PathVariable Long caseId,
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey
    ) {
        return afterSalesCustomerService.withdraw(customerId(principal), caseId, idempotencyKey);
    }


    private Long customerId(Principal principal) {
        return customerService.getCustomerByEmail(principal.getName()).id();
    }
}
