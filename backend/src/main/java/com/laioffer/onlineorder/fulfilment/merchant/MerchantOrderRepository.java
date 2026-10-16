package com.laioffer.onlineorder.fulfilment.merchant;


import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;


public interface MerchantOrderRepository extends ListCrudRepository<MerchantOrderEntity, Long> {

    /**
     * Adds the inbox row of a paid order unless the order already has one, and returns the rows inserted: 1 or 0.
     * Inbox rows are only shown to the merchant (listings and commands) once they have a paid_at.
     * Target-less ON CONFLICT so that H2 (PostgreSQL mode) parses it; the only constraint an insert can violate is
     * uq_merchant_order_order.
     */
    @Modifying
    @Query("""
            INSERT INTO merchant_orders (
                order_id,
                restaurant_id,
                customer_id,
                status,
                total_price,
                items,
                paid_at,
                created_at,
                updated_at
            ) VALUES (
                :orderId,
                :restaurantId,
                :customerId,
                'PAID',
                :totalPrice,
                :items,
                :paidAt,
                :createdAt,
                :createdAt
            )
            ON CONFLICT DO NOTHING
            """)
    int insertPaidOrderIfAbsent(
            Long orderId,
            Long restaurantId,
            Long customerId,
            BigDecimal totalPrice,
            String items,
            LocalDateTime paidAt,
            LocalDateTime createdAt
    );


    /**
     * Adds the inbox row of an order whose later event (order.accepted, order.cancelled, ...) reached the merchant
     * group before its order.paid, with the status that event reports, unless the order already has a row; returns the
     * rows inserted: 1 or 0. The row has no paid_at, so the merchant does not see it until order.paid sets one
     * ({@link #recordPayment}); a replayed order.paid can then no longer add a PAID row for an order that moved on.
     */
    @Modifying
    @Query("""
            INSERT INTO merchant_orders (
                order_id,
                restaurant_id,
                customer_id,
                status,
                total_price,
                items,
                cancel_reason,
                created_at,
                updated_at
            ) VALUES (
                :orderId,
                :restaurantId,
                :customerId,
                :status,
                :totalPrice,
                :items,
                :cancelReason,
                :createdAt,
                :createdAt
            )
            ON CONFLICT DO NOTHING
            """)
    int insertUnpaidOrderIfAbsent(
            Long orderId,
            Long restaurantId,
            Long customerId,
            String status,
            BigDecimal totalPrice,
            String items,
            String cancelReason,
            LocalDateTime createdAt
    );


    /**
     * order.paid for an order that already has a row: sets its paid_at if a later event created the row first. Its
     * status stays whatever the later events made it.
     */
    @Modifying
    @Query("""
            UPDATE merchant_orders
            SET paid_at = :paidAt,
                updated_at = :updatedAt
            WHERE order_id = :orderId
              AND paid_at IS NULL
            """)
    int recordPayment(Long orderId, LocalDateTime paidAt, LocalDateTime updatedAt);


    /**
     * Moves an inbox row forward to {@code status}, only from one of {@code fromStatuses}, so that a late or
     * replayed event never moves a row back.
     */
    @Modifying
    @Query("""
            UPDATE merchant_orders
            SET status = :status,
                updated_at = :updatedAt
            WHERE order_id = :orderId
              AND status IN (:fromStatuses)
            """)
    int advanceStatus(Long orderId, Collection<String> fromStatuses, String status, LocalDateTime updatedAt);


    @Modifying
    @Query("""
            UPDATE merchant_orders
            SET status = 'CANCELLED',
                cancel_reason = :cancelReason,
                updated_at = :updatedAt
            WHERE order_id = :orderId
              AND status IN (:fromStatuses)
            """)
    int cancel(Long orderId, Collection<String> fromStatuses, String cancelReason, LocalDateTime updatedAt);


    /**
     * Records the outcome of a merchant command on the row the command locked.
     */
    @Modifying
    @Query("""
            UPDATE merchant_orders
            SET status = :status,
                cancel_reason = :cancelReason,
                updated_at = :updatedAt
            WHERE id = :id
            """)
    int updateStatus(Long id, String status, String cancelReason, LocalDateTime updatedAt);


    @Query("""
            SELECT *
            FROM merchant_orders
            WHERE order_id = :orderId
              AND restaurant_id = :restaurantId
              AND paid_at IS NOT NULL
            FOR UPDATE
            """)
    MerchantOrderEntity lockByOrderIdAndRestaurantId(Long orderId, Long restaurantId);


    @Query("""
            SELECT *
            FROM merchant_orders
            WHERE order_id = :orderId
            """)
    MerchantOrderEntity findByOrderId(Long orderId);


    @Query("""
            SELECT *
            FROM merchant_orders
            WHERE restaurant_id = :restaurantId
              AND paid_at IS NOT NULL
            ORDER BY paid_at DESC, id DESC
            LIMIT :limit
            """)
    List<MerchantOrderEntity> findPageByRestaurantId(Long restaurantId, int limit);


    @Query("""
            SELECT *
            FROM merchant_orders
            WHERE restaurant_id = :restaurantId
              AND status = :status
              AND paid_at IS NOT NULL
            ORDER BY paid_at DESC, id DESC
            LIMIT :limit
            """)
    List<MerchantOrderEntity> findPageByRestaurantIdAndStatus(Long restaurantId, String status, int limit);
}
