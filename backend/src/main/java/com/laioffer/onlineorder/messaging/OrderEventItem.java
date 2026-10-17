package com.laioffer.onlineorder.messaging;


import java.math.BigDecimal;


/**
 * One line of the order in an order event ({@code event-schemas/common/order-data/v1.json#/$defs/item}). Part of the
 * event contract, deliberately separate from the REST DTOs, so the API can change without changing the events.
 *
 * @param id       the order line (order_history_items.id)
 * @param price    the unit price
 * @param currency ISO 4217 code of {@code price}; null (omitted) means the shop currency
 */
public record OrderEventItem(
        Long id,
        Long menuItemId,
        Long restaurantId,
        BigDecimal price,
        String currency,
        Integer quantity,
        String menuItemName,
        String menuItemDescription,
        String menuItemImageUrl
) {
}
