package com.laioffer.onlineorder.model;


import com.laioffer.onlineorder.entity.OrderEntity;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


/**
 * @param updatedAt when the order last changed status (its creation time until the first change)
 */
public record OrderDto(
        Long id,
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
        this(id, totalPrice, status, createdAt, items, restaurantId, cancelReason, createdAt);
    }


    public OrderDto(Long id, BigDecimal totalPrice, String status, LocalDateTime createdAt, List<OrderHistoryItemDto> items) {
        this(id, totalPrice, status, createdAt, items, null, null);
    }


    public OrderDto(OrderEntity order, List<OrderHistoryItemDto> items) {
        this(
                order.id(),
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
