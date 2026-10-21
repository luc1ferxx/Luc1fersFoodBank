package com.laioffer.onlineorder.fulfilment.merchant;


import com.fasterxml.jackson.annotation.JsonFormat;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


/**
 * An order of the merchant's inbox (the declaration order is the JSON order).
 *
 * @param orderId  the internal key of the order, which the merchant's commands take
 *                 ({@code /merchant/orders/{orderId}/...})
 * @param orderNo  the order's public number to show the merchant, written as a JSON string (Snowflake ids exceed
 *                 2^53); absent (null) for an inbox row that only version 1 order events reached, rows from before
 *                 V14 included
 * @param currency the ISO 4217 currency of {@code totalPrice} and of every item
 */
public record MerchantOrderDto(
        Long orderId,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Long orderNo,
        Long restaurantId,
        Long customerId,
        String status,
        BigDecimal totalPrice,
        String currency,
        List<Item> items,
        String cancelReason,
        LocalDateTime paidAt,
        LocalDateTime updatedAt
) {

    public record Item(
            Long menuItemId,
            String menuItemName,
            BigDecimal price,
            String currency,
            Integer quantity
    ) {
    }
}
