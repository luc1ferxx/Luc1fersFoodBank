package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.DeadLetterEventEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;


public interface DeadLetterEventRepository extends ListCrudRepository<DeadLetterEventEntity, Long> {

    @Query("""
            SELECT COUNT(*)
            FROM dead_letter_events
            WHERE replay_status = :replayStatus
            """)
    long countByReplayStatus(String replayStatus);


    @Query("""
            SELECT COUNT(*)
            FROM dead_letter_events
            WHERE replay_status IN (:pendingStatus, :replayingStatus, :failedStatus, :replayedStatus)
            """)
    long countByReplayStatuses(String pendingStatus, String replayingStatus, String failedStatus, String replayedStatus);


    /**
     * The most recent dead letter stored for an event, or null. Rows stored before the event id was recorded have
     * a NULL event id and are never matched.
     */
    @Query("""
            SELECT *
            FROM dead_letter_events
            WHERE event_id = :eventId
            ORDER BY id DESC
            LIMIT 1
            """)
    DeadLetterEventEntity findLatestByEventId(String eventId);


    /**
     * Records that an event already stored as a dead letter was dead-lettered again. The new error replaces the
     * stored one. A row whose replay was sent (REPLAYED, or REPLAYING while that replay's outcome is pending) goes
     * to REPLAY_FAILED with {@code lastReplayError}, so the operator sees the replay did not take and the row can
     * be replayed again; a row that was never replayed keeps its status. Attempts are left alone: no replay was
     * attempted. The whole decision is one statement, so it cannot lose a race with the replay's own updates.
     */
    @Modifying
    @Query("""
            UPDATE dead_letter_events
            SET error_message = :errorMessage,
                last_replay_error = CASE
                    WHEN replay_status IN (:replayedStatus, :replayingStatus) THEN :lastReplayError
                    ELSE last_replay_error
                END,
                replay_status = CASE
                    WHEN replay_status IN (:replayedStatus, :replayingStatus) THEN :failedStatus
                    ELSE replay_status
                END,
                updated_at = :updatedAt
            WHERE id = :id
            """)
    int markDeadLetteredAgain(
            Long id,
            String replayedStatus,
            String replayingStatus,
            String failedStatus,
            String errorMessage,
            String lastReplayError,
            LocalDateTime updatedAt
    );


    /**
     * Claims a dead letter for one replay attempt: PENDING or REPLAY_FAILED becomes REPLAYING and the attempt is
     * counted here, before the send. Returns 0 when the row is in any other status, so a replay already in flight
     * (or completed) cannot be claimed a second time. A REPLAYING claim whose {@code updated_at} is at or before
     * {@code staleBefore} was never resolved (the replay crashed or could not reach the database after its send)
     * and may be claimed again.
     *
     * <p>{@code claimedAt} becomes the row's {@code updated_at} and identifies this claim: the mark queries below
     * only touch the row while it still carries it.
     */
    @Modifying
    @Query("""
            UPDATE dead_letter_events
            SET replay_status = :replayingStatus,
                replay_attempts = replay_attempts + 1,
                updated_at = :claimedAt
            WHERE id = :id
              AND (replay_status IN (:pendingStatus, :failedStatus)
                   OR (replay_status = :replayingStatus AND updated_at <= :staleBefore))
            """)
    int claimForReplay(
            Long id,
            String replayingStatus,
            String pendingStatus,
            String failedStatus,
            LocalDateTime staleBefore,
            LocalDateTime claimedAt
    );


    /**
     * Records a successful replay for the claim identified by {@code claimedStatus} and {@code claimedAt}. Returns
     * 0 when the row no longer belongs to that claim (it was dead-lettered again, or claimed by a later replay after
     * the claim went stale); the row is then left as it is.
     */
    @Modifying
    @Query("""
            UPDATE dead_letter_events
            SET replay_status = :replayStatus,
                replayed_at = :replayedAt,
                updated_at = :updatedAt,
                last_replay_error = NULL
            WHERE id = :id
              AND replay_status = :claimedStatus
              AND updated_at = :claimedAt
            """)
    int markReplayed(
            Long id,
            String replayStatus,
            String claimedStatus,
            LocalDateTime claimedAt,
            LocalDateTime replayedAt,
            LocalDateTime updatedAt
    );


    /**
     * Records a failed replay for the claim identified by {@code claimedStatus} and {@code claimedAt}. Returns 0
     * when the row no longer belongs to that claim, so a late failure can never overwrite a later replay's result.
     */
    @Modifying
    @Query("""
            UPDATE dead_letter_events
            SET replay_status = :replayStatus,
                updated_at = :updatedAt,
                last_replay_error = :lastReplayError
            WHERE id = :id
              AND replay_status = :claimedStatus
              AND updated_at = :claimedAt
            """)
    int markReplayFailed(
            Long id,
            String replayStatus,
            String claimedStatus,
            LocalDateTime claimedAt,
            LocalDateTime updatedAt,
            String lastReplayError
    );


    @Modifying
    @Query("""
            DELETE FROM dead_letter_events
            WHERE id IN (
                SELECT id
                FROM dead_letter_events
                WHERE replay_status = :replayStatus
                  AND updated_at < :updatedBefore
                ORDER BY id
                LIMIT :batchSize
            )
            """)
    int deleteByReplayStatusAndUpdatedAtBefore(String replayStatus, LocalDateTime updatedBefore, int batchSize);
}
