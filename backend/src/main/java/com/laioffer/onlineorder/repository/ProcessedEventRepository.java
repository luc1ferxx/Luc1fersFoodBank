package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.ProcessedEventEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;


public interface ProcessedEventRepository extends ListCrudRepository<ProcessedEventEntity, Long> {


    /**
     * Inserts the row unless (consumer_name, dedup_key) is already present and returns the rows inserted: 1 or 0.
     * <p>
     * The ON CONFLICT clause deliberately names no conflict target. H2 in PostgreSQL mode (the {@code h2}
     * profile) only parses the target-less form, and on this table the two forms are equivalent on PostgreSQL:
     * the only unique constraint is {@code uq_processed_event_consumer_dedup} and the serial primary key cannot
     * conflict on insert. Keep it that way if another unique constraint is ever added here.
     */
    @Modifying
    @Query("""
            INSERT INTO processed_events (
                consumer_name,
                event_id,
                dedup_key,
                event_type,
                aggregate_type,
                aggregate_id,
                processed_at
            ) VALUES (
                :consumerName,
                :eventId,
                :dedupKey,
                :eventType,
                :aggregateType,
                :aggregateId,
                :processedAt
            )
            ON CONFLICT DO NOTHING
            """)
    int insertIfAbsent(
            String consumerName,
            String eventId,
            String dedupKey,
            String eventType,
            String aggregateType,
            Long aggregateId,
            LocalDateTime processedAt
    );


    @Modifying
    @Query("""
            DELETE FROM processed_events
            WHERE id IN (
                SELECT id
                FROM processed_events
                WHERE processed_at < :processedBefore
                ORDER BY id
                LIMIT :batchSize
            )
            """)
    int deleteByProcessedAtBefore(LocalDateTime processedBefore, int batchSize);
}
