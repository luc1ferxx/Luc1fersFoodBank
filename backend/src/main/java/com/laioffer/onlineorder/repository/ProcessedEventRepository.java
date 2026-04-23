package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.ProcessedEventEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;


public interface ProcessedEventRepository extends ListCrudRepository<ProcessedEventEntity, Long> {


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
            ON CONFLICT (consumer_name, dedup_key) DO NOTHING
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
            WHERE processed_at < :processedBefore
            """)
    int deleteByProcessedAtBefore(LocalDateTime processedBefore);
}
