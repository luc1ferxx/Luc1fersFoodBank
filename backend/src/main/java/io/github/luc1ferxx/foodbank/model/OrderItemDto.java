package io.github.luc1ferxx.foodbank.model;


import io.github.luc1ferxx.foodbank.entity.MenuItemEntity;
import io.github.luc1ferxx.foodbank.entity.OrderItemEntity;


import java.math.BigDecimal;


public record OrderItemDto(
        Long orderItemId,
        Long menuItemId,
        Long restaurantId,
        BigDecimal price,
        String currency,
        Integer quantity,
        String menuItemName,
        String menuItemDescription,
        String menuItemImageUrl
) {
    public OrderItemDto(OrderItemEntity orderItemEntity, MenuItemEntity menuItemEntity) {
        this(
                orderItemEntity.id(),
                orderItemEntity.menuItemId(),
                menuItemEntity.restaurantId(),
                orderItemEntity.price(),
                orderItemEntity.currency(),
                orderItemEntity.quantity(),
                menuItemEntity.name(),
                menuItemEntity.description(),
                menuItemEntity.imageUrl()
        );
    }
}
