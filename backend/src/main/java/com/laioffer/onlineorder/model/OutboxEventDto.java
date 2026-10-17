package com.laioffer.onlineorder.model;


import com.laioffer.onlineorder.entity.OutboxEventEntity;


import java.time.LocalDateTime;


/**
 * @param aggregateSequence the event's position among the events of its aggregate; the publisher sends them in this
 *                          order, so a FAILED event holds back the later ones of the same aggregate
 */
public record OutboxEventDto(
        Long id,
        String aggregateType,
        Long aggregateId,
        Long aggregateSequence,
        String eventId,
        String topic,
        String eventKey,
        String eventType,
        String status,
        Integer attempts,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime publishedAt
) {

    public OutboxEventDto(OutboxEventEntity entity) {
        this(
                entity.id(),
                entity.aggregateType(),
                entity.aggregateId(),
                entity.aggregateSequence(),
                entity.eventId(),
                entity.topic(),
                entity.eventKey(),
                entity.eventType(),
                entity.status(),
                entity.attempts(),
                entity.lastError(),
                entity.createdAt(),
                entity.updatedAt(),
                entity.publishedAt()
        );
    }
}
