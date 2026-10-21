package com.laioffer.onlineorder.fulfilment;


import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventItem;
import com.laioffer.onlineorder.messaging.OrderEventPayload;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;


/**
 * Builds order event envelopes the way OrderEventOutboxService does, for feeding consumers in tests.
 */
public final class FulfilmentTestEvents {

    private FulfilmentTestEvents() {
    }


    public static OrderEventEnvelope orderEvent(
            String eventType,
            Long orderId,
            Long customerId,
            Long restaurantId,
            String status,
            String reason,
            LocalDateTime occurredAt
    ) {
        String eventId = UUID.randomUUID().toString();
        return new OrderEventEnvelope(
                eventId,
                1,
                eventType,
                "ORDER",
                orderId,
                occurredAt,
                eventId,
                null,
                new OrderEventPayload(
                        customerId,
                        status,
                        new BigDecimal("10.59"),
                        "USD",
                        occurredAt,
                        List.of(new OrderEventItem(
                                1L, 2L, restaurantId, new BigDecimal("10.59"), null, 1, "Whopper Meal", "Meal", null)),
                        restaurantId,
                        reason
                )
        );
    }


    /**
     * {@code event} as published in {@code currency}, or without a currency (null) like events published before
     * orders carried one.
     */
    public static OrderEventEnvelope inCurrency(OrderEventEnvelope event, String currency) {
        OrderEventPayload data = event.data();
        return new OrderEventEnvelope(
                event.eventId(),
                event.eventVersion(),
                event.eventType(),
                event.aggregateType(),
                event.aggregateId(),
                event.occurredAt(),
                event.correlationId(),
                event.idempotencyKey(),
                new OrderEventPayload(
                        data.customerId(),
                        data.status(),
                        data.totalPrice(),
                        currency,
                        data.createdAt(),
                        data.items(),
                        data.restaurantId(),
                        data.reason(),
                        data.orderNo()
                )
        );
    }


    /**
     * {@code event} as a version 2 event that carries the order's public number, like OrderEventOutboxService writes
     * it now; the other builders here make version 1 events, which carry none.
     */
    public static OrderEventEnvelope withOrderNo(OrderEventEnvelope event, Long orderNo) {
        OrderEventPayload data = event.data();
        return new OrderEventEnvelope(
                event.eventId(),
                2,
                event.eventType(),
                event.aggregateType(),
                event.aggregateId(),
                event.sequence(),
                event.occurredAt(),
                event.correlationId(),
                event.idempotencyKey(),
                new OrderEventPayload(
                        data.customerId(),
                        data.status(),
                        data.totalPrice(),
                        data.currency(),
                        data.createdAt(),
                        data.items(),
                        data.restaurantId(),
                        data.reason(),
                        orderNo
                )
        );
    }


    public static OrderEventEnvelope orderEvent(String eventType, Long orderId, Long restaurantId, String status) {
        return orderEvent(eventType, orderId, 3L, restaurantId, status, null, LocalDateTime.of(2026, 4, 4, 12, 0));
    }


    /**
     * The event as the outbox stores it: the {@code sequence}-th event of the order (a fresh event id each call, as a
     * copy of an event would have).
     */
    public static OrderEventEnvelope orderEvent(
            String eventType,
            Long orderId,
            Long restaurantId,
            String status,
            long sequence
    ) {
        return orderEvent(eventType, orderId, restaurantId, status).withSequence(sequence);
    }
}
