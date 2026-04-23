package com.laioffer.onlineorder.entity;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.time.LocalDateTime;


@Table("outbox_events")
public record OutboxEventEntity(
        @Id
        @Column("id")
        Long id,
        @Column("aggregate_type")
        String aggregateType,
        @Column("aggregate_id")
        Long aggregateId,
        @Column("event_id")
        String eventId,
        @Column("topic")
        String topic,
        @Column("event_key")
        String eventKey,
        @Column("event_type")
        String eventType,
        @Column("payload")
        String payload,
        @Column("status")
        String status,
        @Column("attempts")
        Integer attempts,
        @Column("last_error")
        String lastError,
        @Column("created_at")
        LocalDateTime createdAt,
        @Column("updated_at")
        LocalDateTime updatedAt,
        @Column("published_at")
        LocalDateTime publishedAt
) {
}
