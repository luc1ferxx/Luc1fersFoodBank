package com.laioffer.onlineorder.model;


import com.laioffer.onlineorder.entity.MenuItemEntity;


import java.math.BigDecimal;


/**
 * A menu item as served by {@code GET /restaurant/{id}/menu} and cached per restaurant. Field names and order
 * match the {@link MenuItemEntity} JSON the endpoint returned before it was cached, so the client sees the
 * same shape. Changing the fields requires bumping the {@code menu} cache key version.
 */
public record CatalogMenuItemDto(
        Long id,
        Long restaurantId,
        String name,
        String description,
        BigDecimal price,
        String imageUrl
) {


    public CatalogMenuItemDto(MenuItemEntity entity) {
        this(entity.id(), entity.restaurantId(), entity.name(), entity.description(), entity.price(), entity.imageUrl());
    }
}
