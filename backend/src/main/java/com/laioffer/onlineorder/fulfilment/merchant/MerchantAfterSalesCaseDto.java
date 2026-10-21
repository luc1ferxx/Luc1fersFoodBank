package com.laioffer.onlineorder.fulfilment.merchant;


import com.fasterxml.jackson.annotation.JsonFormat;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


/**
 * A customer's after-sales request as the merchant sees it (ADR 0002 section 11.3; the declaration order is the JSON
 * order): a row of the merchant's after-sales inbox. Every amount is in {@code currency}, the order's.
 *
 * @param caseId           the case id that the merchant's commands take ({@code /merchant/after-sales/{caseId}/...})
 * @param orderId          the internal key of the order
 * @param orderNo          the order's public number, which the merchant's after-sales page shows, written as a JSON
 *                         string (Snowflake ids exceed 2^53)
 * @param status           PENDING_MERCHANT (waiting for the merchant's answer), COUNTER_OFFERED, REJECTED,
 *                         IN_ARBITRATION, REFUNDING, REFUNDED or CLOSED, as far as the inbox has seen the case
 * @param reasonCode       MISSING_ITEM, WRONG_ITEM, QUALITY_ISSUE, DAMAGED_PACKAGING, FOOD_SAFETY, LATE_DELIVERY or
 *                         OTHER
 * @param lines            the order lines the customer asks a refund for
 * @param requestedAmount  the sum of the lines
 * @param offeredAmount    the merchant's counter-offer
 * @param approvedAmount   the amount refunded or being refunded, from REFUNDING on
 * @param merchantDecision APPROVED, REJECTED or OFFERED once the merchant answered
 * @param merchantNote     the reason of a rejection, or the note of an answer
 * @param deadlineAt       until when the case waits for its current answer; the merchant's deadline while
 *                         PENDING_MERCHANT, after which the requested amount is refunded without the merchant
 * @param openedAt         when the customer asked
 * @param updatedAt        when the inbox row last changed
 */
public record MerchantAfterSalesCaseDto(
        Long caseId,
        Long orderId,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Long orderNo,
        String status,
        String reasonCode,
        String description,
        List<Line> lines,
        BigDecimal requestedAmount,
        BigDecimal offeredAmount,
        BigDecimal approvedAmount,
        String currency,
        String merchantDecision,
        String merchantNote,
        LocalDateTime deadlineAt,
        LocalDateTime openedAt,
        LocalDateTime updatedAt
) {

    /**
     * An order line the customer asks a refund for. Also the JSON stored in merchant_after_sales_cases.lines.
     *
     * @param orderItemId the order line
     * @param quantity    the quantity asked
     * @param amount      unit price x quantity
     * @param currency    the ISO 4217 currency of {@code unitPrice} and {@code amount}
     */
    public record Line(
            Long orderItemId,
            String menuItemName,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal amount,
            String currency
    ) {
    }
}
