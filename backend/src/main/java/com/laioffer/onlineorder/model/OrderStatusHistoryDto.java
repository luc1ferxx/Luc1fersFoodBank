package com.laioffer.onlineorder.model;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.laioffer.onlineorder.entity.OrderStatusHistoryEntity;


import java.time.LocalDateTime;


/**
 * One entry of GET /orders/{orderId}/history. Null fields are written as null (not left out like elsewhere), so every
 * entry has the same keys: from_status is null for the creation, actor_id for SYSTEM, reason for most changes.
 *
 * @param actorType CUSTOMER, ADMIN, MERCHANT, RIDER or SYSTEM
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record OrderStatusHistoryDto(
        String fromStatus,
        String toStatus,
        String actorType,
        Long actorId,
        String reason,
        LocalDateTime occurredAt
) {

    public OrderStatusHistoryDto(OrderStatusHistoryEntity entity) {
        this(
                entity.fromStatus(),
                entity.toStatus(),
                entity.actorType(),
                entity.actorId(),
                entity.reason(),
                entity.occurredAt()
        );
    }
}
