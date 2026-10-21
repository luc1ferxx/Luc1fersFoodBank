package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.audit.AuditActorResolver;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


import java.security.Principal;
import java.util.List;


/**
 * The platform's after-sales endpoints (ADR 0002 section 11.4), ROLE_ADMIN only (their own matchers in AppConfig):
 * the arbitration queue, any case with its history, arbitrating an escalated case, the refunds by status and retrying
 * a FAILED one. An arbitration needs an Idempotency-Key; a decision and a retry that changed something are recorded in
 * the audit log with the admin who made them.
 */
@RestController
public class AfterSalesAdminController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final AfterSalesAdminService afterSalesAdminService;
    private final AuditActorResolver auditActorResolver;


    public AfterSalesAdminController(
            AfterSalesAdminService afterSalesAdminService,
            AuditActorResolver auditActorResolver
    ) {
        this.afterSalesAdminService = afterSalesAdminService;
        this.auditActorResolver = auditActorResolver;
    }


    /**
     * @param status the cases' status; the arbitration queue (IN_ARBITRATION) by default
     */
    @GetMapping("/admin/after-sales")
    public List<AfterSalesCaseDto> getCases(
            @RequestParam(defaultValue = "IN_ARBITRATION") String status,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return afterSalesAdminService.getCases(status, limit);
    }


    @GetMapping("/admin/after-sales/{caseId}")
    public AfterSalesCaseDto getCase(@PathVariable Long caseId) {
        return afterSalesAdminService.getCase(caseId);
    }


    @PostMapping("/admin/after-sales/{caseId}/arbitrate")
    public AfterSalesCaseDto arbitrate(
            Principal principal,
            @PathVariable Long caseId,
            @Valid @RequestBody ArbitrateAfterSalesBody body,
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey
    ) {
        return afterSalesAdminService.arbitrate(caseId, body, idempotencyKey, auditActorResolver.resolve(principal));
    }


    /**
     * @param status the refunds' status; the ones given up (FAILED) by default
     */
    @GetMapping("/admin/refunds")
    public List<RefundDto> getRefunds(
            @RequestParam(defaultValue = "FAILED") String status,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return afterSalesAdminService.getRefunds(status, limit);
    }


    @PostMapping("/admin/refunds/{refundId}/retry")
    public RefundDto retryRefund(Principal principal, @PathVariable Long refundId) {
        return afterSalesAdminService.retryRefund(refundId, auditActorResolver.resolve(principal));
    }
}
