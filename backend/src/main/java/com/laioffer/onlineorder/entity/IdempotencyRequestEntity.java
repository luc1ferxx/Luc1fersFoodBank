package com.laioffer.onlineorder.entity;


import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.time.LocalDateTime;


/**
 * @param orderId the order a succeeded request created or changed
 * @param caseId  the after-sales case a succeeded after-sales request opened or changed (V12_2), which its replay
 *                answers with; null for every other request
 */
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
        @Column("case_id")
        Long caseId,
        @Column("created_at")
        LocalDateTime createdAt,
        @Column("updated_at")
        LocalDateTime updatedAt
) {

    @PersistenceCreator
    public IdempotencyRequestEntity {
    }


    /**
     * A request without an after-sales case.
     */
    public IdempotencyRequestEntity(
            Long id,
            Long customerId,
            String scope,
            String idempotencyKey,
            String requestHash,
            String status,
            Long orderId,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        this(id, customerId, scope, idempotencyKey, requestHash, status, orderId, null, createdAt, updatedAt);
    }
}
