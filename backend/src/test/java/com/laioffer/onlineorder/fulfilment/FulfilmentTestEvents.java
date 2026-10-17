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
                        occurredAt,
                        List.of(new OrderEventItem(
                                1L, 2L, restaurantId, new BigDecimal("10.59"), null, 1, "Whopper Meal", "Meal", null)),
                        restaurantId,
                        reason
                )
        );
    }


    public static OrderEventEnvelope orderEvent(String eventType, Long orderId, Long restaurantId, String status) {
        return orderEvent(eventType, orderId, 3L, restaurantId, status, null, LocalDateTime.of(2026, 4, 4, 12, 0));
    }
}
