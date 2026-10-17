package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.ProcessedEventEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;


/**
 * {@code insertIfAbsent} comes from {@link ProcessedEventDedup} (implemented in {@link ProcessedEventDedupImpl}).
 */
public interface ProcessedEventRepository extends ListCrudRepository<ProcessedEventEntity, Long>, ProcessedEventDedup {


    /**
     * Row-by-row retention, used where processed_events is not partitioned (H2). On PostgreSQL whole monthly
     * partitions are dropped instead (PartitionMaintenanceService).
     */
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
