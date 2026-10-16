package com.laioffer.onlineorder.entity;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.time.LocalDateTime;


/**
 * One status change of an order (V7_1). {@code fromStatus} is null for the creation of the order.
 */
@Table("order_status_history")
public record OrderStatusHistoryEntity(
        @Id
        @Column("id")
        Long id,
        @Column("order_id")
        Long orderId,
        @Column("from_status")
        String fromStatus,
        @Column("to_status")
        String toStatus,
        @Column("actor_type")
        String actorType,
        @Column("actor_id")
        Long actorId,
        @Column("reason")
        String reason,
        @Column("trace_id")
        String traceId,
        @Column("occurred_at")
        LocalDateTime occurredAt
) {
}
