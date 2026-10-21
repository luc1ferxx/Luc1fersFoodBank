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
        LocalDateTime publishedAt,
        // W3C traceparent of the span that inserted the event; the publisher continues that trace when it
        // relays the event to Kafka. Filled in on insert by OutboxTraceContextCallback; null for rows written
        // before V6 or outside a traced request.
        @Column("trace_parent")
        String traceParent,
        // Position of the event among the events of its aggregate (aggregate_type, aggregate_id): 1, 2, 3, ... in
        // commit order, assigned by OrderEventOutboxService.enqueueEvent from outbox_aggregate_sequences (V9) and
        // carried in the envelope as "sequence". The publisher only claims an event when no earlier event of its
        // aggregate is still unpublished. Null only for rows written before V9 that were already published.
        @Column("aggregate_sequence")
        Long aggregateSequence,
        // Token of the claim that holds a PROCESSING row (V13): OrderEventOutboxService.claimNextBatch writes a new one
        // for each batch and returns the events with it, and the publisher's result writes change the row only under
        // it. Null for a row no claim holds.
        @Column("claim_token")
        String claimToken
) {

    /**
     * Event without a captured trace context or an aggregate sequence. The trace context is added on insert, so
     * callers that build new events do not deal with it.
     */
    public OutboxEventEntity(
            Long id,
            String aggregateType,
            Long aggregateId,
            String eventId,
            String topic,
            String eventKey,
            String eventType,
            String payload,
            String status,
            Integer attempts,
            String lastError,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime publishedAt
    ) {
        this(
                id,
                aggregateType,
                aggregateId,
                eventId,
                topic,
                eventKey,
                eventType,
                payload,
                status,
                attempts,
                lastError,
                createdAt,
                updatedAt,
                publishedAt,
                null,
                null,
                null
        );
    }


    public OutboxEventEntity withTraceParent(String traceParent) {
        return new OutboxEventEntity(
                id,
                aggregateType,
                aggregateId,
                eventId,
                topic,
                eventKey,
                eventType,
                payload,
                status,
                attempts,
                lastError,
                createdAt,
                updatedAt,
                publishedAt,
                traceParent,
                aggregateSequence,
                claimToken
        );
    }


    public OutboxEventEntity withClaimToken(String claimToken) {
        return new OutboxEventEntity(
                id,
                aggregateType,
                aggregateId,
                eventId,
                topic,
                eventKey,
                eventType,
                payload,
                status,
                attempts,
                lastError,
                createdAt,
                updatedAt,
                publishedAt,
                traceParent,
                aggregateSequence,
                claimToken
        );
    }
}
