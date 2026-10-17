package com.laioffer.onlineorder.entity;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


/**
 * The last outbox sequence handed out for an aggregate (V9). Rows are never deleted: published outbox rows are, after
 * app.cleanup.outbox-retention, and a sequence that started again at 1 would look stale to every consumer that keeps
 * per-aggregate state (docs/operations.md section 8 says when a counter could be deleted).
 */
@Table("outbox_aggregate_sequences")
public record OutboxAggregateSequenceEntity(
        @Id
        @Column("id")
        Long id,
        @Column("aggregate_type")
        String aggregateType,
        @Column("aggregate_id")
        Long aggregateId,
        @Column("last_sequence")
        Long lastSequence
) {
}
