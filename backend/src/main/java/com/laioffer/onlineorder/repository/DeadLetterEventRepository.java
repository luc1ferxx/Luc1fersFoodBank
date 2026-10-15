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
