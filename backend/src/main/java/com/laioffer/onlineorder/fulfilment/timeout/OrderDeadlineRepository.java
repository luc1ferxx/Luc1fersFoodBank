package com.laioffer.onlineorder.fulfilment.timeout;


import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;
import java.util.Collection;


public interface OrderDeadlineRepository extends ListCrudRepository<OrderDeadlineEntity, Long> {

    /**
     * Target-less ON CONFLICT so that H2 (PostgreSQL mode) parses it; the only constraint an insert can violate is
     * uq_order_deadline_order_type.
     */
    @Modifying
    @Query("""
            INSERT INTO order_deadlines (order_id, deadline_type, due_at, attempts, created_at, updated_at)
            VALUES (:orderId, :deadlineType, :dueAt, 0, :createdAt, :createdAt)
            ON CONFLICT DO NOTHING
            """)
    int insertIfAbsent(Long orderId, String deadlineType, LocalDateTime dueAt, LocalDateTime createdAt);


    @Modifying
    @Query("""
            DELETE FROM order_deadlines
            WHERE order_id = :orderId
              AND deadline_type IN (:deadlineTypes)
            """)
    int deleteByOrderIdAndDeadlineTypes(Long orderId, Collection<String> deadlineTypes);


    /**
     * Claims the most overdue deadline. SKIP LOCKED lets several pollers (one per instance) claim different rows
     * instead of waiting on each other; the row stays locked until the claiming transaction ends.
     */
    @Query("""
            SELECT *
            FROM order_deadlines
            WHERE due_at <= :now
            ORDER BY due_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    OrderDeadlineEntity claimNextDue(LocalDateTime now);


    @Modifying
    @Query("""
            UPDATE order_deadlines
            SET due_at = :retryAt,
                attempts = attempts + 1,
                last_error = :lastError,
                updated_at = :updatedAt
            WHERE id = :id
            """)
    int reschedule(Long id, LocalDateTime retryAt, String lastError, LocalDateTime updatedAt);
}
