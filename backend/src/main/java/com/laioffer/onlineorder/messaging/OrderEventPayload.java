package com.laioffer.onlineorder.messaging;


import com.fasterxml.jackson.annotation.JsonCreator;
import com.laioffer.onlineorder.money.Money;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


/**
 * The data of an order event ({@code event-schemas/common/order-data/v1.json}): the order after the change.
 *
 * @param currency     the ISO 4217 currency of {@code totalPrice} and of every item price; null in events published
 *                     before orders carried a currency, which were all USD (see {@link #currencyCode()})
 * @param items        the order lines, priced in {@code currency}; their own currency is left null
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


    /**
     * The currency of the order's amounts, validated: {@link Money#LEGACY_CURRENCY} for an event without one.
     *
     * @throws IllegalArgumentException when the event names an unknown or unsupported currency
     */
    public String currencyCode() {
        return currency == null ? Money.LEGACY_CURRENCY.getCurrencyCode() : Money.currency(currency).getCurrencyCode();
    }
}
