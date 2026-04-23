package com.laioffer.onlineorder.entity;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.time.LocalDateTime;


@Table("order_notifications")
public record OrderNotificationEntity(
        @Id
        @Column("id")
        Long id,
        @Column("order_id")
        Long orderId,
        @Column("customer_id")
        Long customerId,
        @Column("event_type")
        String eventType,
        @Column("title")
        String title,
        @Column("message")
        String message,
        @Column("created_at")
        LocalDateTime createdAt
) {
}
