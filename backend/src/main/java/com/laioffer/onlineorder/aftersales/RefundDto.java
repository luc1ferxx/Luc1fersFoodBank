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
}
