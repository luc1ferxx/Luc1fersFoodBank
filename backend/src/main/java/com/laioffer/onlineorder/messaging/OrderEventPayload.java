package com.laioffer.onlineorder.messaging;


import com.fasterxml.jackson.annotation.JsonCreator;
import com.laioffer.onlineorder.model.OrderHistoryItemDto;
import com.laioffer.onlineorder.money.Money;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


/**
 * @param items        the order lines, priced in {@code currency}; they carry no currency of their own
 * @param restaurantId the restaurant the order belongs to; null for orders placed before single-restaurant carts
 * @param reason       why the order was cancelled (order.cancelled only), for example MERCHANT_TIMEOUT
 * @param currency     the ISO 4217 currency of {@code totalPrice} and of every item price; null in events published
 *                     before orders carried a currency, which were all USD (see {@link #currencyCode()})
 */
public record OrderEventPayload(
        Long customerId,
        String status,
        BigDecimal totalPrice,
        LocalDateTime createdAt,
        List<OrderHistoryItemDto> items,
        Long restaurantId,
        String reason,
        String currency
) {

    @JsonCreator
    public OrderEventPayload {
    }


    /**
     * A payload without a currency, as events published before orders carried one.
     */
    public OrderEventPayload(
            Long customerId,
            String status,
            BigDecimal totalPrice,
            LocalDateTime createdAt,
            List<OrderHistoryItemDto> items,
            Long restaurantId,
            String reason
    ) {
        this(customerId, status, totalPrice, createdAt, items, restaurantId, reason, null);
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


    /**
     * The currency of the order's amounts, validated: {@link Money#LEGACY_CURRENCY} for an event without one.
     *
     * @throws IllegalArgumentException when the event names an unknown or unsupported currency
     */
    public String currencyCode() {
        return currency == null ? Money.LEGACY_CURRENCY.getCurrencyCode() : Money.currency(currency).getCurrencyCode();
    }
}
