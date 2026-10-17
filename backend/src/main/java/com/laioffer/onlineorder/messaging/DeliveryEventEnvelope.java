package com.laioffer.onlineorder.messaging;


import com.fasterxml.jackson.annotation.JsonCreator;


import java.time.LocalDateTime;


/**
 * A delivery event on the delivery topic (delivery.assigned, delivery.picked_up, delivery.delivered,
 * delivery.cancelled, delivery.reassigned), published by the dispatch module through the outbox and keyed by the order
 * id. Contract: {@code event-schemas/delivery-events/<event_type>/v1.json}. The envelope has the same fields as
 * {@link OrderEventEnvelope}, so the dead-letter tooling reads its event id the same way.
 *
 * @param aggregateId the delivery id
 * @param sequence    see {@link EventEnvelope#sequence()}
 */
public record DeliveryEventEnvelope(
        String eventId,
        Integer eventVersion,
        String eventType,
        String aggregateType,
        Long aggregateId,
        Long sequence,
        LocalDateTime occurredAt,
        String correlationId,
        String idempotencyKey,
        DeliveryEventPayload data
) implements EventEnvelope {

    @JsonCreator
    public DeliveryEventEnvelope {
    }


    /**
     * An envelope without a sequence, which the outbox assigns when it stores the event.
     */
    public DeliveryEventEnvelope(
            String eventId,
            Integer eventVersion,
            String eventType,
            String aggregateType,
            Long aggregateId,
            LocalDateTime occurredAt,
            String correlationId,
            String idempotencyKey,
            DeliveryEventPayload data
    ) {
        this(eventId, eventVersion, eventType, aggregateType, aggregateId, null, occurredAt, correlationId,
                idempotencyKey, data);
    }


    @Override
    public DeliveryEventEnvelope withSequence(Long sequence) {
        return new DeliveryEventEnvelope(eventId, eventVersion, eventType, aggregateType, aggregateId, sequence,
                occurredAt, correlationId, idempotencyKey, data);
    }
}
