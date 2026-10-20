package com.laioffer.onlineorder.messaging;


import com.fasterxml.jackson.annotation.JsonFormat;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


/**
 * The data of every after-sales event ({@code event-schemas/common/after-sales-case-data/v1.json}): the case after the
 * step the event reports, and the step itself. Every amount is in {@code currency}, the order's. Null components are
 * omitted from the JSON.
 *
 * @param orderNo          the order's public number, written as a JSON string (Snowflake ids exceed 2^53)
 * @param restaurantId     null only for the automatic refund of an order without a restaurant
 * @param kind             CUSTOMER_REQUEST or ORDER_CANCELLED
 * @param attemptNo        1, or 2 for a customer's request after withdrawing the first
 * @param status           the case status after the step
 * @param action           the step: OPEN or ORDER_CANCELLED for the opening, otherwise the after-sales trigger
 * @param actorType        CUSTOMER, MERCHANT, ADMIN or SYSTEM
 * @param actorId          the customers.id of the customer, merchant or admin who took the step; null for SYSTEM
 * @param version          the case's version after the step (0 when it was opened): consumers that keep a copy of the
 *                         case apply an event only when its version is above the one they hold
 * @param requestedAmount  the sum of the lines
 * @param offeredAmount    the merchant's counter-offer
 * @param approvedAmount   the amount to refund, from REFUNDING on
 * @param refundedAmount   the amount refunded (REFUNDED)
 * @param merchantDecision APPROVED, REJECTED or OFFERED once the merchant answered
 * @param merchantNote     the merchant's reason for a rejection, or the note of a counter-offer
 * @param resolution       how the approved amount was set (MERCHANT_APPROVED, OFFER_TIMEOUT, ...)
 * @param closeReason      WITHDRAWN, CUSTOMER_TIMEOUT or ARBITRATION_REJECTED (CLOSED)
 * @param deadlineAt       the deadline of an open status
 * @param openedAt         when the case was opened
 */
public record AfterSalesCasePayload(
        Long caseId,
        Long orderId,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Long orderNo,
        Long customerId,
        Long restaurantId,
        String kind,
        Integer attemptNo,
        String status,
        String action,
        String actorType,
        Long actorId,
        Long version,
        String reasonCode,
        String description,
        BigDecimal requestedAmount,
        BigDecimal offeredAmount,
        BigDecimal approvedAmount,
        BigDecimal refundedAmount,
        String currency,
        String merchantDecision,
        String merchantNote,
        String resolution,
        String closeReason,
        LocalDateTime deadlineAt,
        List<AfterSalesCaseLinePayload> lines,
        LocalDateTime openedAt
) {
}
