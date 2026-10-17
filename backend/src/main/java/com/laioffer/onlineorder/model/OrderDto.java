package com.laioffer.onlineorder.model;


import com.fasterxml.jackson.annotation.JsonFormat;
import com.laioffer.onlineorder.entity.OrderEntity;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


/**
 * @param id        the internal key that the order endpoints take ({@code /orders/{orderId}/...})
 * @param orderNo   the public order number to show customers, written as a JSON string: Snowflake ids are larger
 *                  than 2^53, which JavaScript numbers cannot hold exactly
 * @param updatedAt when the order last changed status (its creation time until the first change)
 */
public record OrderDto(
        Long id,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Long orderNo,
        BigDecimal totalPrice,
        String status,
        LocalDateTime createdAt,
        List<OrderHistoryItemDto> items,
        Long restaurantId,
        String cancelReason,
        LocalDateTime updatedAt
) {

    public OrderDto(
            Long id,
            BigDecimal totalPrice,
            String status,
            LocalDateTime createdAt,
            List<OrderHistoryItemDto> items,
            Long restaurantId,
            String cancelReason
    ) {
        this(id, null, totalPrice, status, createdAt, items, restaurantId, cancelReason, createdAt);
    }


    public OrderDto(Long id, BigDecimal totalPrice, String status, LocalDateTime createdAt, List<OrderHistoryItemDto> items) {
        this(id, totalPrice, status, createdAt, items, null, null);
    }


    public OrderDto(OrderEntity order, List<OrderHistoryItemDto> items) {
        this(
                order.id(),
                order.orderNo(),
                order.totalPrice(),
                order.status(),
                order.createdAt(),
                items,
                order.restaurantId(),
                order.cancelReason(),
                order.updatedAt()
        );
    }
}
