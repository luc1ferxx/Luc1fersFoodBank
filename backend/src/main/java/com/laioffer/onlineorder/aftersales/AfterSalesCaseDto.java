package com.laioffer.onlineorder.aftersales;


import com.fasterxml.jackson.annotation.JsonFormat;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


/**
 * An after-sales case (ADR 0002 section 11.5; the declaration order is the JSON order). Every amount is in
 * {@code currency}, the order's.
 *
 * @param id               the case id that the case endpoints take ({@code /after-sales/cases/{caseId}/...})
 * @param orderId          the internal key of the order
 * @param orderNo          the order's public number to show customers, written as a JSON string (Snowflake ids exceed
 *                         2^53)
 * @param kind             CUSTOMER_REQUEST or ORDER_CANCELLED
 * @param attemptNo        1, or 2 for the customer's request after withdrawing the first
 * @param status           PENDING_MERCHANT, COUNTER_OFFERED, REJECTED, IN_ARBITRATION, REFUNDING, REFUNDED or CLOSED
 * @param requestedAmount  the sum of the lines
 * @param offeredAmount    the merchant's counter-offer
 * @param approvedAmount   the amount to refund, from REFUNDING on
 * @param refundedAmount   the amount refunded (REFUNDED)
 * @param merchantDecision APPROVED, REJECTED or OFFERED once the merchant answered
 * @param resolution       how the approved amount was set (MERCHANT_APPROVED, OFFER_ACCEPTED, ...)
 * @param closeReason      WITHDRAWN, CUSTOMER_TIMEOUT or ARBITRATION_REJECTED (CLOSED)
 * @param deadlineAt       until when the open status waits for its answer
 * @param refund           the refund, from REFUNDING on
 * @param history          the steps, oldest first; only in the answer about one case, absent from lists
 */
public record AfterSalesCaseDto(
        Long id,
        Long orderId,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Long orderNo,
        Long restaurantId,
        String kind,
        Integer attemptNo,
        String status,
        String reasonCode,
        String description,
        BigDecimal requestedAmount,
        BigDecimal offeredAmount,
        BigDecimal approvedAmount,
        BigDecimal refundedAmount,
        String currency,
        List<AfterSalesLineDto> lines,
        String merchantDecision,
        String merchantNote,
        String resolution,
        String closeReason,
        String adminNote,
        LocalDateTime deadlineAt,
        RefundDto refund,
        List<AfterSalesHistoryDto> history,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    AfterSalesCaseDto(
            AfterSalesCaseEntity current,
            Long orderNo,
            List<AfterSalesLineDto> lines,
            RefundDto refund,
            List<AfterSalesHistoryDto> history
    ) {
        this(
                current.id(),
                current.orderId(),
                orderNo,
                current.restaurantId(),
                current.kind(),
                current.attemptNo(),
                current.status(),
                current.reasonCode(),
                current.description(),
                current.requestedAmount(),
                current.offeredAmount(),
                current.approvedAmount(),
                current.refundedAmount(),
                current.currency(),
                lines,
                current.merchantDecision(),
                current.merchantNote(),
                current.resolution(),
                current.closeReason(),
                current.adminNote(),
                current.deadlineAt(),
                refund,
                history,
                current.createdAt(),
                current.updatedAt()
        );
    }
}
