package com.laioffer.onlineorder.fulfilment.merchant;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


public record MerchantOrderDto(
        Long orderId,
        Long restaurantId,
        Long customerId,
        String status,
        BigDecimal totalPrice,
        List<Item> items,
        String cancelReason,
        LocalDateTime paidAt,
        LocalDateTime updatedAt
) {

    public record Item(
            Long menuItemId,
            String menuItemName,
            BigDecimal price,
            Integer quantity
    ) {
    }
}
