package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.IdempotencyRequestEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;


public interface IdempotencyRequestRepository extends ListCrudRepository<IdempotencyRequestEntity, Long> {


    @Modifying
    @Query("""
            INSERT INTO idempotency_requests (
                customer_id,
                scope,
                idempotency_key,
                request_hash,
                status,
                order_id,
                created_at,
                updated_at
            ) VALUES (
                :customerId,
                :scope,
                :idempotencyKey,
                :requestHash,
                :status,
                NULL,
                :createdAt,
                :updatedAt
            )
            ON CONFLICT (customer_id, scope, idempotency_key) DO NOTHING
            """)
    void insertIfAbsent(
            Long customerId,
            String scope,
            String idempotencyKey,
            String requestHash,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    );


    @Query("""
            SELECT *
            FROM idempotency_requests
            WHERE customer_id = :customerId
              AND scope = :scope
              AND idempotency_key = :idempotencyKey
            FOR UPDATE
            """)
    IdempotencyRequestEntity lockByCustomerIdAndScopeAndIdempotencyKey(
            Long customerId,
            String scope,
            String idempotencyKey
    );


    @Modifying
    @Query("""
            UPDATE idempotency_requests
            SET status = :status,
                order_id = :orderId,
                updated_at = :updatedAt
            WHERE id = :id
            """)
    void markSucceeded(Long id, String status, Long orderId, LocalDateTime updatedAt);


    @Modifying
    @Query("""
            DELETE FROM idempotency_requests
            WHERE updated_at < :updatedBefore
            """)
    int deleteByUpdatedAtBefore(LocalDateTime updatedBefore);
}
