package com.laioffer.onlineorder.messaging;


import com.fasterxml.jackson.annotation.JsonCreator;


import java.time.LocalDateTime;


/**
 * An order event on the order topic (order.created, order.paid, order.accepted, order.preparing, order.completed,
 * order.cancelled), keyed by the order id. Contract: {@code event-schemas/order-events/<event_type>/v1.json}.
 *
 * @param aggregateId the order id
 * @param sequence    see {@link EventEnvelope#sequence()}
 */
public record OrderEventEnvelope(
        String eventId,
        Integer eventVersion,
        String eventType,
        String aggregateType,
        Long aggregateId,
        Long sequence,
        LocalDateTime occurredAt,
        String correlationId,
        String idempotencyKey,
        OrderEventPayload data
) implements EventEnvelope {

    @JsonCreator
    public OrderEventEnvelope {
    }


    /**
     * An envelope without a sequence, which the outbox assigns when it stores the event.
     */
    public OrderEventEnvelope(
            String eventId,
            Integer eventVersion,
            String eventType,
            String aggregateType,
            Long aggregateId,
            LocalDateTime occurredAt,
            String correlationId,
            String idempotencyKey,
            OrderEventPayload data
    ) {
        this(eventId, eventVersion, eventType, aggregateType, aggregateId, null, occurredAt, correlationId,
                idempotencyKey, data);
    }


    @Override
    public OrderEventEnvelope withSequence(Long sequence) {
        return new OrderEventEnvelope(eventId, eventVersion, eventType, aggregateType, aggregateId, sequence, occurredAt,
                correlationId, idempotencyKey, data);
    }
}
