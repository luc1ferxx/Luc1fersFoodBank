package com.laioffer.onlineorder.messaging;


import com.fasterxml.jackson.annotation.JsonCreator;


import java.time.LocalDateTime;


/**
 * An after-sales event on the after-sales topic (aftersales.*), published by the after-sales module through the outbox
 * and keyed by the order id. Contracts: {@code event-schemas/after-sales-events/<event_type>/v1.json}. The envelope has
 * the same fields as {@link OrderEventEnvelope}, so the dead-letter tooling reads its event id the same way.
 *
 * @param aggregateType AFTER_SALES_CASE
 * @param aggregateId   the case id
 * @param sequence      see {@link EventEnvelope#sequence()}
 * @param data          the case after the step the event reports
 */
public record AfterSalesEventEnvelope(
        String eventId,
        Integer eventVersion,
        String eventType,
        String aggregateType,
        Long aggregateId,
        Long sequence,
        LocalDateTime occurredAt,
        String correlationId,
        String idempotencyKey,
        AfterSalesCasePayload data
) implements EventEnvelope {

    @JsonCreator
    public AfterSalesEventEnvelope {
    }


    /**
     * An envelope without a sequence, which the outbox assigns when it stores the event.
     */
    public AfterSalesEventEnvelope(
            String eventId,
            Integer eventVersion,
            String eventType,
            String aggregateType,
            Long aggregateId,
            LocalDateTime occurredAt,
            String correlationId,
            String idempotencyKey,
            AfterSalesCasePayload data
    ) {
        this(eventId, eventVersion, eventType, aggregateType, aggregateId, null, occurredAt, correlationId,
                idempotencyKey, data);
    }


    @Override
    public AfterSalesEventEnvelope withSequence(Long sequence) {
        return new AfterSalesEventEnvelope(eventId, eventVersion, eventType, aggregateType, aggregateId, sequence,
                occurredAt, correlationId, idempotencyKey, data);
    }
}
