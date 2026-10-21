package com.laioffer.onlineorder.messaging;


import java.time.LocalDateTime;


/**
 * The envelope every event carries (schema {@code event-schemas/common/envelope/v1.json}). Each topic has its own
 * envelope record, which differs only in its {@code data}. The JSON is snake_case (the application ObjectMapper).
 *
 * <p>The outbox assigns {@link #sequence()} when it stores the event ({@code OrderEventOutboxService.enqueueEvent});
 * producers build envelopes without one.
 */
public sealed interface EventEnvelope
        permits OrderEventEnvelope, DeliveryEventEnvelope, MerchantEventEnvelope, AfterSalesEventEnvelope {

    String eventId();


    /**
     * The version of the event type's schema the event was written for.
     */
    Integer eventVersion();


    String eventType();


    String aggregateType();


    Long aggregateId();


    /**
     * The event's position among the events of its aggregate (1, 2, 3, ...), or null for an event written before
     * the outbox assigned sequences (migration V9).
     */
    Long sequence();


    LocalDateTime occurredAt();


    /**
     * A copy of this envelope with the given sequence.
     */
    EventEnvelope withSequence(Long sequence);
}
