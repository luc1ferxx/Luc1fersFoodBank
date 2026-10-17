package com.laioffer.onlineorder.model;


import com.laioffer.onlineorder.entity.CartEntity;


import java.math.BigDecimal;
import java.util.List;


/**
 * @param currency the ISO 4217 currency of {@code totalPrice} and of every line
 */
public record CartDto(
        Long id,
        BigDecimal totalPrice,
        String currency,
        List<OrderItemDto> orderItems
) {
    public CartDto(CartEntity entity, List<OrderItemDto> orderItems) {
        this(entity.id(), entity.totalPrice(), entity.currency(), orderItems);
    }
}
