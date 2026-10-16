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


    /**
     * The rider's own availability change. Only an active rider's flag changes: a deactivation that committed after
     * the caller read the rider leaves 0 rows updated instead of an inactive rider on duty. On PostgreSQL a concurrent
     * deactivation that has not committed yet holds the row lock; this waits for it and re-checks the condition.
     *
     * @return 1 when the flag was written, 0 when the rider is (now) inactive
     */
    @Modifying
    @Query("""
            UPDATE riders
            SET on_duty = :onDuty,
                updated_at = :updatedAt
            WHERE id = :id
              AND active = TRUE
            """)
    int updateOnDutyIfActive(Long id, boolean onDuty, LocalDateTime updatedAt);


    @Query("SELECT * FROM riders WHERE id = :id FOR UPDATE")
    RiderEntity lockById(Long id);


    /**
     * Writes both flags of a rider the caller has locked ({@link #lockById}); the caller keeps on_duty FALSE for an
     * inactive rider.
     */
    @Modifying
    @Query("""
            UPDATE riders
            SET active = :active,
                on_duty = :onDuty,
                updated_at = :updatedAt
            WHERE id = :id
            """)
    int updateStatus(Long id, boolean active, boolean onDuty, LocalDateTime updatedAt);
}
