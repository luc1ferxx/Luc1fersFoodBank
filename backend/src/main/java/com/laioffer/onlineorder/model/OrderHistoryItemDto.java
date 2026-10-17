package com.laioffer.onlineorder.model;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;


import java.math.BigDecimal;


/**
 * @param currency the ISO 4217 currency of {@code price}, the order's currency. Null (and omitted from the JSON) in
 *                 order events, whose lines are priced in the payload's currency ({@link #withoutCurrency()}), and in
 *                 lines read back from events.
 */
public record OrderHistoryItemDto(
        Long id,
        Long menuItemId,
        Long restaurantId,
        BigDecimal price,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String currency,
        Integer quantity,
        String menuItemName,
        String menuItemDescription,
        String menuItemImageUrl
) {

    public OrderHistoryItemDto(OrderHistoryItemEntity entity) {
        this(
                entity.id(),
                entity.menuItemId(),
                entity.restaurantId(),
                entity.price(),
                entity.currency(),
                entity.quantity(),
                entity.menuItemName(),
                entity.menuItemDescription(),
                entity.menuItemImageUrl()
        );
    }


    /**
     * This line as order events carry it: without a currency of its own.
     */
    public OrderHistoryItemDto withoutCurrency() {
        return new OrderHistoryItemDto(
                id,
                menuItemId,
                restaurantId,
                price,
                null,
                quantity,
                menuItemName,
                menuItemDescription,
                menuItemImageUrl
        );
    }
}
