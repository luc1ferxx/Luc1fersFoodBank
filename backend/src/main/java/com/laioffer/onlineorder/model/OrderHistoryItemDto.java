package com.laioffer.onlineorder.model;


import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;


public record OrderHistoryItemDto(
        Long id,
        Long menuItemId,
        Long restaurantId,
        Double price,
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
                entity.quantity(),
                entity.menuItemName(),
                entity.menuItemDescription(),
                entity.menuItemImageUrl()
        );
    }
}
