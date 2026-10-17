package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.OutboxEventEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


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


    /**
     * Creation time of the oldest event still waiting to be published. Ids grow with insertion, so the lowest
     * unpublished id is the oldest event. The statuses are literals so that PostgreSQL can prove the partial index
     * idx_outbox_events_unpublished_id (V3) applies and read a single index entry, however large the table is.
     */
    @Query("""
            SELECT created_at
            FROM outbox_events
            WHERE status IN ('PENDING', 'PROCESSING')
            ORDER BY id
            LIMIT 1
            """)
    Optional<LocalDateTime> findOldestUnpublishedCreatedAt();


    /**
     * Aggregates whose later events are held back by a FAILED event: the publisher claims nothing of such an aggregate
     * until the FAILED event is retried (POST /admin/outbox/events/{id}/retry) and published, or abandoned
     * (POST /admin/outbox/events/{id}/abandon). A FAILED row without a sequence (failed before V9) holds nothing back
     * and is not counted. An aggregate whose only
     * unpublished event is the FAILED one is not counted; onlineorder.outbox.failed covers it.
     */
    @Query("""
            SELECT COUNT(*)
            FROM (
                SELECT DISTINCT failed.aggregate_type, failed.aggregate_id
                FROM outbox_events failed
                WHERE failed.status = 'FAILED'
                  AND EXISTS (
                        SELECT 1
                        FROM outbox_events later
                        WHERE later.aggregate_type = failed.aggregate_type
                          AND later.aggregate_id = failed.aggregate_id
                          AND later.aggregate_sequence > failed.aggregate_sequence
                          AND later.status IN ('PENDING', 'PROCESSING', 'FAILED')
                  )
            ) blocked
            """)
    long countBlockedAggregates();


    /**
     * Unpublished events held back behind a FAILED event of their aggregate. Driven from the (few) FAILED rows, so a
     * large PENDING backlog is not scanned on every scrape.
     */
    @Query("""
            SELECT COUNT(DISTINCT held.id)
            FROM outbox_events failed
            JOIN outbox_events held
              ON held.aggregate_type = failed.aggregate_type
             AND held.aggregate_id = failed.aggregate_id
             AND held.aggregate_sequence > failed.aggregate_sequence
             AND held.status IN ('PENDING', 'PROCESSING', 'FAILED')
            WHERE failed.status = 'FAILED'
            """)
    long countBlockedEvents();


    @Query("""
            SELECT *
            FROM outbox_events
            WHERE id = :id
            FOR UPDATE
            """)
    OutboxEventEntity lockById(Long id);


    /**
     * Locks the next events to publish: PENDING, or PROCESSING whose claim is older than {@code staleBefore}, that are
     * the head of their aggregate. An event is held back while an earlier event of its aggregate (a lower
     * aggregate_sequence) is still PENDING, PROCESSING or FAILED, so an aggregate's events reach Kafka one at a time in
     * sequence order, across publisher instances: the next one only becomes claimable once the previous one is
     * PUBLISHED, which happens after Kafka acknowledged it. SKIP LOCKED lets instances claim disjoint batches; an
     * instance never sees the next event of an aggregate whose head another instance holds, because the head is still
     * unpublished.
     *
     * <p>The statuses in the NOT EXISTS are literals so that PostgreSQL can prove the partial index
     * idx_outbox_events_unpublished_aggregate (V9_1) applies: each probe reads the aggregate's few unpublished index
     * entries. Rows without a sequence (published before V9) are never an earlier event of anything.
     */
    @Query("""
            SELECT *
            FROM outbox_events e
            WHERE (
                    e.status = :pendingStatus
                 OR (
                        e.status = :processingStatus
                    AND e.updated_at <= :staleBefore
                 )
              )
              AND NOT EXISTS (
                    SELECT 1
                    FROM outbox_events earlier
                    WHERE earlier.aggregate_type = e.aggregate_type
                      AND earlier.aggregate_id = e.aggregate_id
                      AND earlier.aggregate_sequence < e.aggregate_sequence
                      AND earlier.status IN ('PENDING', 'PROCESSING', 'FAILED')
              )
            ORDER BY e.id
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
            WHERE id IN (:ids)
            """)
    int markProcessing(List<Long> ids, String status, LocalDateTime updatedAt);


    @Modifying
    @Query("""
            UPDATE outbox_events
            SET status = :status,
                updated_at = :updatedAt,
                published_at = :publishedAt,
                last_error = NULL
            WHERE id IN (:ids)
            """)
    int markPublished(List<Long> ids, String status, LocalDateTime updatedAt, LocalDateTime publishedAt);


    /**
     * Returns claimed events that were never sent to PENDING and gives back the attempt the claim counted.
     * Rows that are no longer PROCESSING are left alone.
     */
    @Modifying
    @Query("""
            UPDATE outbox_events
            SET status = :pendingStatus,
                attempts = CASE WHEN attempts > 0 THEN attempts - 1 ELSE 0 END,
                updated_at = :updatedAt
            WHERE id IN (:ids)
              AND status = :processingStatus
            """)
    int releaseClaims(List<Long> ids, String processingStatus, String pendingStatus, LocalDateTime updatedAt);


    /**
     * Returns claimed events whose send failed for a transient reason to PENDING, gives back the attempt the claim
     * counted and records the error. Rows that are no longer PROCESSING are left alone.
     */
    @Modifying
    @Query("""
            UPDATE outbox_events
            SET status = :pendingStatus,
                attempts = CASE WHEN attempts > 0 THEN attempts - 1 ELSE 0 END,
                updated_at = :updatedAt,
                last_error = :lastError
            WHERE id IN (:ids)
              AND status = :processingStatus
            """)
    int releaseFailedClaims(
            List<Long> ids,
            String processingStatus,
            String pendingStatus,
            LocalDateTime updatedAt,
            String lastError
    );


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


    /**
     * Gives up on a FAILED event for good: ABANDONED is outside the statuses the head-of-line claim waits for, so the
     * later events of its aggregate become claimable, and the event itself is never published. last_error is kept.
     */
    @Modifying
    @Query("""
            UPDATE outbox_events
            SET status = :abandonedStatus,
                updated_at = :updatedAt
            WHERE id = :id
              AND status = :failedStatus
            """)
    int markFailedAbandoned(Long id, String failedStatus, String abandonedStatus, LocalDateTime updatedAt);


    /**
     * Unpublished events of the aggregate after {@code sequence}: what an event with that sequence holds back while it
     * is FAILED.
     */
    @Query("""
            SELECT COUNT(*)
            FROM outbox_events
            WHERE aggregate_type = :aggregateType
              AND aggregate_id = :aggregateId
              AND aggregate_sequence > :sequence
              AND status IN ('PENDING', 'PROCESSING', 'FAILED')
            """)
    long countUnpublishedAfter(String aggregateType, Long aggregateId, Long sequence);


    /**
     * Whether one of the given (just published) events has a PENDING successor in its aggregate, which the head-of-line
     * claim held back until now: the publisher then claims again in the same run instead of waiting for the next one.
     * The literal status lies inside the predicate of the head index (V9_1), which answers each probe.
     */
    @Query("""
            SELECT COUNT(*)
            FROM outbox_events published
            WHERE published.id IN (:publishedIds)
              AND published.aggregate_sequence IS NOT NULL
              AND EXISTS (
                    SELECT 1
                    FROM outbox_events later
                    WHERE later.aggregate_type = published.aggregate_type
                      AND later.aggregate_id = published.aggregate_id
                      AND later.aggregate_sequence > published.aggregate_sequence
                      AND later.status = 'PENDING'
              )
            """)
    long countWithPendingSuccessor(List<Long> publishedIds);


    @Modifying
    @Query("""
            DELETE FROM outbox_events
            WHERE id IN (
                SELECT id
                FROM outbox_events
                WHERE status = :status
                  AND published_at < :publishedBefore
                ORDER BY id
                LIMIT :batchSize
            )
            """)
    int deleteByStatusAndPublishedAtBefore(String status, LocalDateTime publishedBefore, int batchSize);
}
