package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.OutboxEventEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;
import java.util.List;


public interface OutboxEventRepository extends ListCrudRepository<OutboxEventEntity, Long> {


    @Query("""
            SELECT *
            FROM outbox_events
            WHERE status = :pendingStatus
               OR (
                    status = :processingStatus
                AND updated_at <= :staleBefore
               )
            ORDER BY id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """)
    List<OutboxEventEntity> lockNextBatchReadyToPublish(
            String pendingStatus,
            String processingStatus,
            LocalDateTime staleBefore,
            int batchSize
    );


    @Modifying
    @Query("""
            UPDATE outbox_events
            SET status = :status,
                attempts = attempts + 1,
                updated_at = :updatedAt,
                last_error = NULL
            WHERE id = :id
            """)
    void markProcessing(Long id, String status, LocalDateTime updatedAt);


    @Modifying
    @Query("""
            UPDATE outbox_events
            SET status = :status,
                updated_at = :updatedAt,
                published_at = :publishedAt,
                last_error = NULL
            WHERE id = :id
            """)
    void markPublished(Long id, String status, LocalDateTime updatedAt, LocalDateTime publishedAt);


    @Modifying
    @Query("""
            UPDATE outbox_events
            SET status = :status,
                updated_at = :updatedAt,
                last_error = :lastError
            WHERE id = :id
            """)
    void markPendingRetry(Long id, String status, LocalDateTime updatedAt, String lastError);


    @Modifying
    @Query("""
            DELETE FROM outbox_events
            WHERE status = :status
              AND published_at < :publishedBefore
            """)
    int deleteByStatusAndPublishedAtBefore(String status, LocalDateTime publishedBefore);
}
