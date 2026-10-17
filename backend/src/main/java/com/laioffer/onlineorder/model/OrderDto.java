package com.laioffer.onlineorder.model;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.money.Money;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


/**
 * @param currency  the ISO 4217 currency of {@code totalPrice} and of every item
 * @param updatedAt when the order last changed status (its creation time until the first change)
 */
public record OrderDto(
        Long id,
        BigDecimal totalPrice,
        String currency,
        String status,
        LocalDateTime createdAt,
        List<OrderHistoryItemDto> items,
        Long restaurantId,
        String cancelReason,
        LocalDateTime updatedAt
) {

    public OrderDto(
            Long id,
            Money total,
            String status,
            LocalDateTime createdAt,
            List<OrderHistoryItemDto> items,
            Long restaurantId,
            String cancelReason
    ) {
        this(id, total.amount(), total.currencyCode(), status, createdAt, items, restaurantId, cancelReason, createdAt);
    }


    public OrderDto(Long id, Money total, String status, LocalDateTime createdAt, List<OrderHistoryItemDto> items) {
        this(id, total, status, createdAt, items, null, null);
    }


    public OrderDto(OrderEntity order, List<OrderHistoryItemDto> items) {
        this(
                order.id(),
                order.totalPrice(),
                order.currency(),
                order.status(),
                order.createdAt(),
                items,
                order.restaurantId(),
                order.cancelReason(),
                order.updatedAt()
        );
    }
}
