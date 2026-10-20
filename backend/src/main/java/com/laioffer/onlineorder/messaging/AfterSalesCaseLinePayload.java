package com.laioffer.onlineorder.messaging;


import java.math.BigDecimal;


/**
 * One order line an after-sales case asks a refund for, in an after-sales event
 * ({@code event-schemas/common/after-sales-case-data/v1.json#/$defs/line}). Part of the event contract, separate from
 * the REST DTOs.
 *
 * @param orderItemId the order line (order_history_items.id)
 * @param quantity    the quantity asked, at least 1
 * @param unitPrice   the line's unit price
 * @param amount      unit price x quantity
 * @param currency    the ISO 4217 currency of {@code unitPrice} and {@code amount}, the case's
 */
public record AfterSalesCaseLinePayload(
        Long orderItemId,
        String menuItemName,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal amount,
        String currency
) {
}
