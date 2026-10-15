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
     * (or completed) cannot be claimed a second time.
     */
    @Modifying
    @Query("""
            UPDATE dead_letter_events
            SET replay_status = :replayingStatus,
                replay_attempts = replay_attempts + 1,
                updated_at = :updatedAt
            WHERE id = :id
              AND replay_status IN (:pendingStatus, :failedStatus)
            """)
    int claimForReplay(
            Long id,
            String replayingStatus,
            String pendingStatus,
            String failedStatus,
            LocalDateTime updatedAt
    );


    @Modifying
    @Query("""
            UPDATE dead_letter_events
            SET replay_status = :replayStatus,
                replayed_at = :replayedAt,
                updated_at = :updatedAt,
                last_replay_error = NULL
            WHERE id = :id
            """)
    void markReplayed(Long id, String replayStatus, LocalDateTime replayedAt, LocalDateTime updatedAt);


    @Modifying
    @Query("""
            UPDATE dead_letter_events
            SET replay_status = :replayStatus,
                updated_at = :updatedAt,
                last_replay_error = :lastReplayError
            WHERE id = :id
            """)
    void markReplayFailed(Long id, String replayStatus, LocalDateTime updatedAt, String lastReplayError);


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
