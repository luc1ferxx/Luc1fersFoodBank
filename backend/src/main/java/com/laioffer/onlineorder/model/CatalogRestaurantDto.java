package com.laioffer.onlineorder.model;


import com.laioffer.onlineorder.entity.RestaurantEntity;


/**
 * A restaurant's basic fields, cached as the restaurant list. Changing the fields requires bumping the
 * {@code restaurants} cache key version.
 */
public record CatalogRestaurantDto(
        Long id,
        String name,
        String address,
        String phone,
        String imageUrl
) {


    public CatalogRestaurantDto(RestaurantEntity entity) {
        this(entity.id(), entity.name(), entity.address(), entity.phone(), entity.imageUrl());
    }
}
