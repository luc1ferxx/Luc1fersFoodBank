package com.laioffer.onlineorder.messaging;


import com.fasterxml.jackson.annotation.JsonCreator;


import java.time.LocalDateTime;


/**
 * A merchant event on the merchant topic (merchant.order_received), published by the merchant module through the
 * outbox and keyed by the order id. Contract: {@code event-schemas/merchant-events/merchant.order_received/v1.json}.
 * The envelope has the same fields as {@link OrderEventEnvelope}, so the dead-letter tooling reads its event id the
 * same way.
 *
 * @param aggregateId the order id
 * @param sequence    see {@link EventEnvelope#sequence()}
 */
public record MerchantEventEnvelope(
        String eventId,
        Integer eventVersion,
        String eventType,
        String aggregateType,
        Long aggregateId,
        Long sequence,
        LocalDateTime occurredAt,
        String correlationId,
        String idempotencyKey,
        MerchantEventPayload data
) implements EventEnvelope {

    @JsonCreator
    public MerchantEventEnvelope {
    }


    /**
     * An envelope without a sequence, which the outbox assigns when it stores the event.
     */
    public MerchantEventEnvelope(
            String eventId,
            Integer eventVersion,
            String eventType,
            String aggregateType,
            Long aggregateId,
            LocalDateTime occurredAt,
            String correlationId,
            String idempotencyKey,
            MerchantEventPayload data
    ) {
        this(eventId, eventVersion, eventType, aggregateType, aggregateId, null, occurredAt, correlationId,
                idempotencyKey, data);
    }


    @Override
    public MerchantEventEnvelope withSequence(Long sequence) {
        return new MerchantEventEnvelope(eventId, eventVersion, eventType, aggregateType, aggregateId, sequence,
                occurredAt, correlationId, idempotencyKey, data);
    }
}
