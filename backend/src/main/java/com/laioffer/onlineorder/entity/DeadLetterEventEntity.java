package com.laioffer.onlineorder.entity;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.time.LocalDateTime;


@Table("dead_letter_events")
public record DeadLetterEventEntity(
        @Id
        @Column("id")
        Long id,
        @Column("source_topic")
        String sourceTopic,
        @Column("dead_letter_topic")
        String deadLetterTopic,
        @Column("message_key")
        String messageKey,
        @Column("payload")
        String payload,
        @Column("error_message")
        String errorMessage,
        @Column("replay_status")
        String replayStatus,
        @Column("replay_attempts")
        Integer replayAttempts,
        @Column("replayed_at")
        LocalDateTime replayedAt,
        @Column("last_replay_error")
        String lastReplayError,
        @Column("created_at")
        LocalDateTime createdAt,
        @Column("updated_at")
        LocalDateTime updatedAt
) {
}
