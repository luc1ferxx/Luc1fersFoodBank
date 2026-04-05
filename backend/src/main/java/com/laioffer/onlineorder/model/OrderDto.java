package com.laioffer.onlineorder.model;


import com.laioffer.onlineorder.entity.OrderEntity;


import java.time.LocalDateTime;
import java.util.List;


public record OrderDto(
        Long id,
        Double totalPrice,
        String status,
        LocalDateTime createdAt,
        List<OrderHistoryItemDto> items
) {

    public OrderDto(OrderEntity order, List<OrderHistoryItemDto> items) {
        this(order.id(), order.totalPrice(), order.status(), order.createdAt(), items);
    }
}
