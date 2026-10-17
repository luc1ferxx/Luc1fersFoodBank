package com.laioffer.onlineorder.model;


import com.fasterxml.jackson.annotation.JsonFormat;
import com.laioffer.onlineorder.entity.OrderNotificationEntity;


import java.time.LocalDateTime;


/**
 * @param orderId the internal order id
 * @param orderNo the order's public number (a JSON string, see {@link OrderDto#orderNo()}); the messages use it too
 */
public record OrderNotificationDto(
        Long id,
        Long orderId,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Long orderNo,
        String eventType,
        String title,
        String message,
        LocalDateTime createdAt
) {

    public OrderNotificationDto(OrderNotificationEntity entity, Long orderNo) {
        this(
                entity.id(),
                entity.orderId(),
                orderNo,
                entity.eventType(),
                entity.title(),
                entity.message(),
                entity.createdAt()
        );
    }
}
