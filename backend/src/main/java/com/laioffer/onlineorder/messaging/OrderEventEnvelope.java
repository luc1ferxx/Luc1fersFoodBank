package com.laioffer.onlineorder.messaging;


import java.time.LocalDateTime;


public record OrderEventEnvelope(
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
}
