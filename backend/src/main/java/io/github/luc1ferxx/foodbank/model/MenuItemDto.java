package io.github.luc1ferxx.foodbank.model;


import io.github.luc1ferxx.foodbank.entity.MenuItemEntity;


import java.math.BigDecimal;


public record MenuItemDto(
        Long id,
        String name,
        String description,
        BigDecimal price,
        String currency,
        String imageUrl
) {


    public MenuItemDto(MenuItemEntity entity) {
        this(entity.id(), entity.name(), entity.description(), entity.price(), entity.currency(), entity.imageUrl());
    }


    public MenuItemDto(CatalogMenuItemDto item) {
        this(item.id(), item.name(), item.description(), item.price(), item.currency(), item.imageUrl());
    }
}
