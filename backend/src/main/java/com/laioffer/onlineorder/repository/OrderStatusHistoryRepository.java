package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.OrderStatusHistoryEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;
import java.util.List;


public interface OrderStatusHistoryRepository extends ListCrudRepository<OrderStatusHistoryEntity, Long> {

    List<OrderStatusHistoryEntity> findAllByOrderIdOrderByIdAsc(Long orderId);


    /**
     * The order's status changes, oldest first, from partitions that can hold them: a change is never older than the
     * order, so {@code occurredFrom} (the order's creation time with a margin for clock skew) lets PostgreSQL skip
     * the monthly partitions before it (V10).
     */
    @Query("""
            SELECT *
            FROM order_status_history
            WHERE order_id = :orderId
              AND occurred_at >= :occurredFrom
            ORDER BY id
            """)
    List<OrderStatusHistoryEntity> findAllByOrderIdSince(Long orderId, LocalDateTime occurredFrom);


    /**
     * Retention by rows where order_status_history is not partitioned (H2) and
     * app.cleanup.order-status-history-retention is set; on PostgreSQL whole months are dropped instead.
     */
    @Modifying
    @Query("""
            DELETE FROM order_status_history
            WHERE id IN (
                SELECT id
                FROM order_status_history
                WHERE occurred_at < :occurredBefore
                ORDER BY id
                LIMIT :batchSize
            )
            """)
    int deleteByOccurredAtBefore(LocalDateTime occurredBefore, int batchSize);
}
