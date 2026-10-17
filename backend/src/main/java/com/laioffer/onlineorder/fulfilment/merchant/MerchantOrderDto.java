package com.laioffer.onlineorder.fulfilment.merchant;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


/**
 * @param currency the ISO 4217 currency of {@code totalPrice} and of every item
 */
public record MerchantOrderDto(
        Long orderId,
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
