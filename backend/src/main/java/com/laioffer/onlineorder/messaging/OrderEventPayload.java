package com.laioffer.onlineorder.messaging;


import com.fasterxml.jackson.annotation.JsonCreator;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


/**
 * The data of an order event ({@code event-schemas/common/order-data/v1.json}): the order after the change.
 *
 * @param currency     ISO 4217 code of {@code totalPrice}; null (omitted) means the shop currency
 * @param restaurantId the restaurant the order belongs to; null for orders placed before single-restaurant carts
 * @param reason       why the order was cancelled (order.cancelled only), for example MERCHANT_TIMEOUT
 */
public record OrderEventPayload(
        Long customerId,
        String status,
        BigDecimal totalPrice,
        String currency,
        LocalDateTime createdAt,
        List<OrderEventItem> items,
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
            List<OrderEventItem> items,
            Long restaurantId,
            String reason
    ) {
        this(customerId, status, totalPrice, null, createdAt, items, restaurantId, reason);
    }


    public OrderEventPayload(
            Long customerId,
            String status,
            BigDecimal totalPrice,
            LocalDateTime createdAt,
            List<OrderEventItem> items
    ) {
        this(customerId, status, totalPrice, null, createdAt, items, null, null);
    }
}
