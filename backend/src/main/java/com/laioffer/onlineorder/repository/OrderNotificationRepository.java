package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.OrderNotificationEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;
import java.util.List;


public interface OrderNotificationRepository extends ListCrudRepository<OrderNotificationEntity, Long> {

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
            ON CONFLICT (order_id, event_type) DO NOTHING
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
}
