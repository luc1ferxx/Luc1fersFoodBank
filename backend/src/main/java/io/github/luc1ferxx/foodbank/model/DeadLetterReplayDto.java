package io.github.luc1ferxx.foodbank.model;


import io.github.luc1ferxx.foodbank.entity.DeadLetterEventEntity;


import java.time.LocalDateTime;


public record DeadLetterReplayDto(
        Long id,
        String sourceTopic,
        String deadLetterTopic,
        String messageKey,
        String replayStatus,
        Integer replayAttempts,
        LocalDateTime replayedAt,
        String lastReplayError,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public DeadLetterReplayDto(DeadLetterEventEntity entity) {
        this(
                entity.id(),
                entity.sourceTopic(),
                entity.deadLetterTopic(),
                entity.messageKey(),
                entity.replayStatus(),
                entity.replayAttempts(),
                entity.replayedAt(),
                entity.lastReplayError(),
                entity.createdAt(),
                entity.updatedAt()
        );
    }
}
