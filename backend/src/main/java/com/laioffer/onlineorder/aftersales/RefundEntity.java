package com.laioffer.onlineorder.aftersales;


import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.math.BigDecimal;
import java.time.LocalDateTime;


/**
 * The refund of a case in REFUNDING (ADR 0002 section 9).
 *
 * @param gatewayKey        the payment gateway's idempotency key, derived from the case
 * @param paymentReference  the capture it refunds (order_payments.provider_reference)
 * @param amount            the case's approved amount, in the case's currency (a composite key enforces both)
 * @param status            PENDING, PROCESSING, SUCCEEDED or FAILED
 * @param attempts          gateway calls so far, reclaims included
 * @param nextAttemptAt     when a PENDING refund is due
 * @param claimedUntil      when the lease of a PROCESSING refund expires
 * @param providerReference the gateway's id of the refund, once it succeeded
 * @param version           the optimistic lock, as on {@link AfterSalesCaseEntity}
 * @param claimFailures     the claims that failed in a row after the claim, before any call (V12_3); raised only by
 *                          {@link RefundRepository#backOffPending} and {@link RefundRepository#backOffExpiredLease},
 *                          which keep the version, and back to 0 with every step saved
 */
@Table("refunds")
public record RefundEntity(
        @Id
        @Column("id")
        Long id,
        @Column("case_id")
        Long caseId,
        @Column("order_id")
        Long orderId,
        @Column("customer_id")
        Long customerId,
        @Column("gateway_key")
        String gatewayKey,
        @Column("payment_reference")
        String paymentReference,
        @Column("amount")
        BigDecimal amount,
        @Column("currency")
        String currency,
        @Column("status")
        String status,
        @Column("attempts")
        Integer attempts,
        @Column("next_attempt_at")
        LocalDateTime nextAttemptAt,
        @Column("claimed_until")
        LocalDateTime claimedUntil,
        @Column("provider_reference")
        String providerReference,
        @Column("last_error")
        String lastError,
        @Version
        @Column("version")
        Long version,
        @Column("created_at")
        LocalDateTime createdAt,
        @Column("updated_at")
        LocalDateTime updatedAt,
        @Column("succeeded_at")
        LocalDateTime succeededAt,
        @Column("claim_failures")
        Integer claimFailures
) {

    /**
     * A refund with no failed claim: what every step saves, so that each one starts the count over.
     */
    public RefundEntity(
            Long id,
            Long caseId,
            Long orderId,
            Long customerId,
            String gatewayKey,
            String paymentReference,
            BigDecimal amount,
            String currency,
            String status,
            Integer attempts,
            LocalDateTime nextAttemptAt,
            LocalDateTime claimedUntil,
            String providerReference,
            String lastError,
            Long version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime succeededAt
    ) {
        this(id, caseId, orderId, customerId, gatewayKey, paymentReference, amount, currency, status, attempts,
                nextAttemptAt, claimedUntil, providerReference, lastError, version, createdAt, updatedAt, succeededAt, 0);
    }
}
