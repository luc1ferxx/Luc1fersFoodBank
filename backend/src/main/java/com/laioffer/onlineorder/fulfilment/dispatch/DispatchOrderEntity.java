package com.laioffer.onlineorder.fulfilment.dispatch;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.time.LocalDateTime;


/**
 * The dispatch module's own record of an order: the row both order event handlers lock before they touch deliveries.
 *
 * @param cancelledAt set by order.cancelled; an order.accepted handled afterwards assigns no rider
 */
@Table("dispatch_orders")
public record DispatchOrderEntity(
        @Id
        @Column("order_id")
        Long orderId,
        @Column("cancelled_at")
        LocalDateTime cancelledAt,
        @Column("created_at")
        LocalDateTime createdAt,
        @Column("updated_at")
        LocalDateTime updatedAt
) {
}
