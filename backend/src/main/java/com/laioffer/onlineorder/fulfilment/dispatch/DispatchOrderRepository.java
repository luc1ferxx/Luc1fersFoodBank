package com.laioffer.onlineorder.fulfilment.dispatch;


import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;


import java.time.LocalDateTime;


public interface DispatchOrderRepository extends Repository<DispatchOrderEntity, Long> {

    /**
     * Target-less ON CONFLICT so that H2 (PostgreSQL mode) parses it; the only constraint an insert can violate is the
     * primary key. A concurrent insert of the same order waits for the other transaction and then does nothing.
     */
    @Modifying
    @Query("""
            INSERT INTO dispatch_orders (order_id, created_at, updated_at)
            VALUES (:orderId, :createdAt, :createdAt)
            ON CONFLICT DO NOTHING
            """)
    int insertIfAbsent(Long orderId, LocalDateTime createdAt);


    @Query("SELECT * FROM dispatch_orders WHERE order_id = :orderId FOR UPDATE")
    DispatchOrderEntity lockByOrderId(Long orderId);


    @Modifying
    @Query("""
            UPDATE dispatch_orders
            SET cancelled_at = :cancelledAt,
                updated_at = :cancelledAt
            WHERE order_id = :orderId
              AND cancelled_at IS NULL
            """)
    int markCancelled(Long orderId, LocalDateTime cancelledAt);
}
