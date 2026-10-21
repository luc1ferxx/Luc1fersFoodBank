package com.laioffer.onlineorder.fulfilment.dispatch;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.time.LocalDateTime;


/**
 * @param orderNo the order's public number (V14), from the order event that created the delivery or a later one that
 *                carried it (order events version 2); null while only version 1 events reached it
 */
@Table("deliveries")
public record DeliveryEntity(
        @Id
        @Column("id")
        Long id,
        @Column("order_id")
        Long orderId,
        @Column("order_no")
        Long orderNo,
        @Column("customer_id")
        Long customerId,
        @Column("restaurant_id")
        Long restaurantId,
        @Column("rider_id")
        Long riderId,
        @Column("status")
        String status,
        @Column("assigned_at")
        LocalDateTime assignedAt,
        @Column("picked_up_at")
        LocalDateTime pickedUpAt,
        @Column("delivered_at")
        LocalDateTime deliveredAt,
        @Column("cancelled_at")
        LocalDateTime cancelledAt,
        @Column("updated_at")
        LocalDateTime updatedAt
) {
}
