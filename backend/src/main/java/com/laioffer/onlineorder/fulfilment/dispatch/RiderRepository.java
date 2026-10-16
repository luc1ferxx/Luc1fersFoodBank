package com.laioffer.onlineorder.fulfilment.dispatch;


import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;
import java.util.List;


public interface RiderRepository extends ListCrudRepository<RiderEntity, Long> {

    /**
     * Locks the least-loaded rider that is active and on duty and that no other transaction holds. Concurrent
     * assignments skip each other's riders instead of queueing on the same row, so they spread over the fleet.
     */
    @Query("""
            SELECT *
            FROM riders
            WHERE active = TRUE
              AND on_duty = TRUE
            ORDER BY active_deliveries, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    RiderEntity lockLeastLoadedUnlockedRider();


    /**
     * Fallback when every dispatchable rider is locked by a concurrent assignment: waits for the least-loaded one.
     */
    @Query("""
            SELECT *
            FROM riders
            WHERE active = TRUE
              AND on_duty = TRUE
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


    @Query("SELECT * FROM riders WHERE customer_id = :customerId")
    RiderEntity findByCustomerId(Long customerId);


    @Query("SELECT * FROM riders ORDER BY id")
    List<RiderEntity> findAllOrderById();


    @Modifying
    @Query("""
            UPDATE riders
            SET on_duty = :onDuty,
                updated_at = :updatedAt
            WHERE id = :id
            """)
    int updateOnDuty(Long id, boolean onDuty, LocalDateTime updatedAt);


    /**
     * Activates or deactivates a rider. Deactivating also takes the rider off duty, so a reactivated rider only gets
     * deliveries again once they go back on duty themselves.
     */
    @Modifying
    @Query("""
            UPDATE riders
            SET active = :active,
                on_duty = CASE WHEN :active THEN on_duty ELSE FALSE END,
                updated_at = :updatedAt
            WHERE id = :id
            """)
    int updateActive(Long id, boolean active, LocalDateTime updatedAt);
}
