package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.DeadLetterEventEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;


public interface DeadLetterEventRepository extends ListCrudRepository<DeadLetterEventEntity, Long> {

    @Query("""
            SELECT *
            FROM dead_letter_events
            WHERE id = :id
            FOR UPDATE
            """)
    DeadLetterEventEntity lockById(Long id);


    @Modifying
    @Query("""
            UPDATE dead_letter_events
            SET replay_status = :replayStatus,
                replay_attempts = replay_attempts + 1,
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
                replay_attempts = replay_attempts + 1,
                updated_at = :updatedAt,
                last_replay_error = :lastReplayError
            WHERE id = :id
            """)
    void markReplayFailed(Long id, String replayStatus, LocalDateTime updatedAt, String lastReplayError);


    @Modifying
    @Query("""
            DELETE FROM dead_letter_events
            WHERE replay_status = :replayStatus
              AND updated_at < :updatedBefore
            """)
    int deleteByReplayStatusAndUpdatedAtBefore(String replayStatus, LocalDateTime updatedBefore);
}
