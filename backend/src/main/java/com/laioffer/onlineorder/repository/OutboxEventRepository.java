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
            WHERE status = :status
            ORDER BY updated_at ASC, id ASC
            LIMIT :limit
            """)
    List<OutboxEventEntity> findByStatusOrderByUpdatedAtAsc(String status, int limit);


    @Query("""
            SELECT COUNT(*)
            FROM outbox_events
            WHERE status = :status
            """)
    long countByStatus(String status);


    @Query("""
            SELECT COUNT(*)
            FROM outbox_events
            WHERE status IN (:firstStatus, :secondStatus)
            """)
    long countByStatuses(String firstStatus, String secondStatus);


    @Query("""
            SELECT *
            FROM outbox_events
            WHERE id = :id
            FOR UPDATE
            """)
    OutboxEventEntity lockById(Long id);


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
            UPDATE outbox_events
            SET status = :status,
                updated_at = :updatedAt,
                last_error = :lastError
            WHERE id = :id
            """)
    void markFailed(Long id, String status, LocalDateTime updatedAt, String lastError);


    @Modifying
    @Query("""
            UPDATE outbox_events
            SET status = :pendingStatus,
                attempts = 0,
                updated_at = :updatedAt,
                published_at = NULL,
                last_error = NULL
            WHERE id = :id
              AND status = :failedStatus
            """)
    int resetFailedForRetry(Long id, String failedStatus, String pendingStatus, LocalDateTime updatedAt);


    @Modifying
    @Query("""
            DELETE FROM outbox_events
            WHERE status = :status
              AND published_at < :publishedBefore
            """)
    int deleteByStatusAndPublishedAtBefore(String status, LocalDateTime publishedBefore);
}
