package com.laioffer.onlineorder.messaging;


import java.time.LocalDateTime;


/**
 * A delivery event on the delivery topic (delivery.assigned, delivery.picked_up, delivery.delivered,
 * delivery.cancelled), published by the dispatch module through the outbox and keyed by the order id. The envelope
 * has the same fields as {@link OrderEventEnvelope}, so the dead-letter tooling reads its event id the same way.
 *
 * @param aggregateId the delivery id
 */
public record DeliveryEventEnvelope(
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
}
