package com.laioffer.onlineorder.entity;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.time.LocalDateTime;


/**
 * The last event sequence a consumer applied for an aggregate (V9_2).
 */
@Table("consumer_aggregate_sequences")
public record ConsumerAggregateSequenceEntity(
        @Id
        @Column("id")
        Long id,
        @Column("consumer_name")
        String consumerName,
        @Column("aggregate_type")
        String aggregateType,
        @Column("aggregate_id")
        Long aggregateId,
        @Column("last_sequence")
        Long lastSequence,
        @Column("updated_at")
        LocalDateTime updatedAt
) {
}
