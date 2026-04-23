package com.laioffer.onlineorder.model;


import com.laioffer.onlineorder.entity.OrderNotificationEntity;


import java.time.LocalDateTime;


public record OrderNotificationDto(
        Long id,
        Long orderId,
        String eventType,
        String title,
        String message,
        LocalDateTime createdAt
) {

    public OrderNotificationDto(OrderNotificationEntity entity) {
        this(entity.id(), entity.orderId(), entity.eventType(), entity.title(), entity.message(), entity.createdAt());
    }
}
