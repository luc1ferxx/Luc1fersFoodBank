package com.laioffer.onlineorder.fulfilment.dispatch;


import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;


public interface RiderRepository extends ListCrudRepository<RiderEntity, Long> {

    /**
     * Locks the least-loaded active rider that no other transaction holds. Concurrent assignments skip each other's
     * riders instead of queueing on the same row, so they spread over the fleet.
     */
    @Query("""
            SELECT *
            FROM riders
            WHERE active = TRUE
            ORDER BY active_deliveries, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    RiderEntity lockLeastLoadedUnlockedRider();


    /**
     * Fallback when every active rider is locked by a concurrent assignment: waits for the least-loaded one.
     */
    @Query("""
            SELECT *
            FROM riders
            WHERE active = TRUE
            ORDER BY active_deliveries, id
            LIMIT 1
            FOR UPDATE
            """)
    RiderEntity lockLeastLoadedRider();


    @Modifying
    @Query("""
            UPDATE riders
            SET active_deliveries = active_deliveries + 1,
                updated_at = :updatedAt
            WHERE id = :id
            """)
    int incrementLoad(Long id, LocalDateTime updatedAt);


    @Modifying
    @Query("""
            UPDATE riders
            SET active_deliveries = active_deliveries - 1,
                updated_at = :updatedAt
            WHERE id = :id
              AND active_deliveries > 0
            """)
    int decrementLoad(Long id, LocalDateTime updatedAt);
}
