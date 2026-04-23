package com.laioffer.onlineorder.entity;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.time.LocalDateTime;


@Table("idempotency_requests")
public record IdempotencyRequestEntity(
        @Id
        @Column("id")
        Long id,
        @Column("customer_id")
        Long customerId,
        @Column("scope")
        String scope,
        @Column("idempotency_key")
        String idempotencyKey,
        @Column("request_hash")
        String requestHash,
        @Column("status")
        String status,
        @Column("order_id")
        Long orderId,
        @Column("created_at")
        LocalDateTime createdAt,
        @Column("updated_at")
        LocalDateTime updatedAt
) {
}
