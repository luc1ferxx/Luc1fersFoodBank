package com.laioffer.onlineorder.aftersales;


import com.fasterxml.jackson.annotation.JsonFormat;


import java.math.BigDecimal;
import java.time.LocalDateTime;


/**
 * The refund of a case that reached REFUNDING.
 *
 * @param orderNo       the order's public number, written as a JSON string (Snowflake ids exceed 2^53)
 * @param currency      the ISO 4217 currency of {@code amount}
 * @param status        PENDING, PROCESSING, SUCCEEDED or FAILED
 * @param attempts      gateway calls so far
 * @param lastError     the gateway's error of the last failed attempt: for the platform's staff only, never in a
 *                      customer's answer ({@link #forCustomer})
 * @param nextAttemptAt when a PENDING refund is tried next
 */
public record RefundDto(
        Long id,
        Long caseId,
        Long orderId,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Long orderNo,
        BigDecimal amount,
        String currency,
        String status,
        Integer attempts,
        String lastError,
        String providerReference,
        LocalDateTime nextAttemptAt,
        LocalDateTime createdAt,
        LocalDateTime succeededAt
) {

    RefundDto(RefundEntity refund, Long orderNo) {
        this(
                refund.id(),
                refund.caseId(),
                refund.orderId(),
                orderNo,
                refund.amount(),
                refund.currency(),
                refund.status(),
                refund.attempts(),
                refund.lastError(),
                refund.providerReference(),
                refund.nextAttemptAt(),
                refund.createdAt(),
                refund.succeededAt()
        );
    }


    /**
     * The refund as its customer sees it: without {@code last_error}, which describes the platform's gateway and is
     * for its staff (the admin view shows it).
     */
    static RefundDto forCustomer(RefundEntity refund, Long orderNo) {
        RefundDto full = new RefundDto(refund, orderNo);
        return new RefundDto(
                full.id(),
                full.caseId(),
                full.orderId(),
                full.orderNo(),
                full.amount(),
                full.currency(),
                full.status(),
                full.attempts(),
                null,
                full.providerReference(),
                full.nextAttemptAt(),
                full.createdAt(),
                full.succeededAt()
        );
    }
}
