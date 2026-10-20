package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.OrderNotificationEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;
import java.util.List;


public interface OrderNotificationRepository extends ListCrudRepository<OrderNotificationEntity, Long> {

    /**
     * Records an order or delivery notification unless the order already has one of this event type, and returns the
     * rows inserted: 1 or 0. The row takes the empty dedup_scope (the column default), the scope of every order and
     * delivery event, so uq_notification_order_event_scope keeps one per order and event type. The ON CONFLICT target
     * is PostgreSQL syntax; the notification consumers only run with Kafka, which the h2 profile switches off.
     */
    @Modifying
    @Query("""
            INSERT INTO order_notifications (
                order_id,
                customer_id,
                event_type,
                title,
                message,
                created_at
            ) VALUES (
                :orderId,
                :customerId,
                :eventType,
                :title,
                :message,
                :createdAt
            )
            ON CONFLICT (order_id, event_type, dedup_scope) DO NOTHING
            """)
    int insertIfAbsent(
            Long orderId,
            Long customerId,
            String eventType,
            String title,
            String message,
            LocalDateTime createdAt
    );


    List<OrderNotificationEntity> findAllByCustomerIdOrderByCreatedAtDesc(Long customerId);


    @Query("""
            SELECT *
            FROM order_notifications
            WHERE customer_id = :customerId
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """)
    List<OrderNotificationEntity> findPageByCustomerIdOrderByCreatedAtDesc(Long customerId, int limit);
}
