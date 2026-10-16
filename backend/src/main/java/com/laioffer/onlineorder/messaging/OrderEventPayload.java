package com.laioffer.onlineorder.messaging;


import com.fasterxml.jackson.annotation.JsonCreator;
import com.laioffer.onlineorder.model.OrderHistoryItemDto;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


/**
 * @param restaurantId the restaurant the order belongs to; null for orders placed before single-restaurant carts
 * @param reason       why the order was cancelled (order.cancelled only), for example MERCHANT_TIMEOUT
 */
public record OrderEventPayload(
        Long customerId,
        String status,
        BigDecimal totalPrice,
        LocalDateTime createdAt,
        List<OrderHistoryItemDto> items,
        Long restaurantId,
        String reason
) {

    @JsonCreator
    public OrderEventPayload {
    }


    public OrderEventPayload(
            Long customerId,
            String status,
            BigDecimal totalPrice,
            LocalDateTime createdAt,
            List<OrderHistoryItemDto> items
    ) {
        this(customerId, status, totalPrice, createdAt, items, null, null);
    }
}
