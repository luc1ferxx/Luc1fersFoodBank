package com.laioffer.onlineorder.entity;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.time.LocalDateTime;


@Table("processed_events")
public record ProcessedEventEntity(
        @Id
        @Column("id")
        Long id,
        @Column("consumer_name")
        String consumerName,
        @Column("event_id")
        String eventId,
        @Column("dedup_key")
        String dedupKey,
        @Column("event_type")
        String eventType,
        @Column("aggregate_type")
        String aggregateType,
        @Column("aggregate_id")
        Long aggregateId,
        @Column("processed_at")
        LocalDateTime processedAt
) {
}
