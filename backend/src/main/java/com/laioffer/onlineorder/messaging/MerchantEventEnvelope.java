package com.laioffer.onlineorder.messaging;


import java.time.LocalDateTime;


/**
 * A merchant event on the merchant topic (merchant.order_received), published by the merchant module through the
 * outbox and keyed by the order id. The envelope has the same fields as {@link OrderEventEnvelope}, so the
 * dead-letter tooling reads its event id the same way.
 *
 * @param aggregateId the order id
 */
public record MerchantEventEnvelope(
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
}
