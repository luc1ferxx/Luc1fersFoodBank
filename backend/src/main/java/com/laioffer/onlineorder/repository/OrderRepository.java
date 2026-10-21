package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.OrderEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


public interface OrderRepository extends ListCrudRepository<OrderEntity, Long> {

    List<OrderEntity> findAllByCustomerIdOrderByCreatedAtDesc(Long customerId);

    @Query("""
            SELECT *
            FROM orders
            WHERE customer_id = :customerId
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """)
    List<OrderEntity> findPageByCustomerIdOrderByCreatedAtDesc(Long customerId, int limit);

    Optional<OrderEntity> findByIdAndCustomerId(Long id, Long customerId);

    /**
     * The customer's orders that can get an after-sales request now, latest completed first (ADR 0002 sections 6, 20
     * and D16): COMPLETED since {@code completedSince} (orders.updated_at is the completion time), with a restaurant
     * and a captured payment, and without a customer request other than a withdrawn first one. The open command checks
     * the same rules under the order's row lock.
     */
    @Query("""
            SELECT *
            FROM orders
            WHERE customer_id = :customerId
              AND status = 'COMPLETED'
              AND restaurant_id IS NOT NULL
              AND updated_at >= :completedSince
              AND EXISTS (
                  SELECT 1
                  FROM order_payments
                  WHERE order_payments.order_id = orders.id
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM after_sales_cases
                  WHERE after_sales_cases.order_id = orders.id
                    AND after_sales_cases.kind = 'CUSTOMER_REQUEST'
                    AND NOT (after_sales_cases.attempt_no = 1
                        AND after_sales_cases.status = 'CLOSED'
                        AND after_sales_cases.close_reason = 'WITHDRAWN')
              )
            ORDER BY updated_at DESC, id DESC
            LIMIT :limit
            """)
    List<OrderEntity> findPageOpenToAfterSales(Long customerId, LocalDateTime completedSince, int limit);

    /**
     * @return the public order number of the order; empty when there is no such order
     */
    @Query("""
            SELECT order_no
            FROM orders
            WHERE id = :id
            """)
    Optional<Long> findOrderNoById(Long id);

    @Query("""
            SELECT *
            FROM orders
            WHERE id = :id
            FOR UPDATE
            """)
    OrderEntity lockById(Long id);

    @Query("""
            SELECT *
            FROM orders
            WHERE id = :id
              AND customer_id = :customerId
            FOR UPDATE
            """)
    OrderEntity lockByIdAndCustomerId(Long id, Long customerId);


    /**
     * Moves the order from {@code fromStatus} to {@code toStatus}: a compare-and-set on the status the caller read
     * under the row lock. The cancel reason and updated_at are written as given. Returns the rows changed, 1, or 0
     * when the order is no longer in {@code fromStatus} (checked with
     * {@link com.laioffer.onlineorder.statemachine.CompareAndSet}).
     */
    @Modifying
    @Query("""
            UPDATE orders
            SET status = :toStatus,
                cancel_reason = :cancelReason,
                updated_at = :updatedAt
            WHERE id = :id
              AND status = :fromStatus
            """)
    int compareAndSetStatus(Long id, String fromStatus, String toStatus, String cancelReason, LocalDateTime updatedAt);


    /**
     * Records when the rider picked the order up, once and only while the order is neither cancelled nor completed: a
     * repeated or later pickup leaves the first one, and a pickup that arrives after the cancel (it was decided without
     * it) is not recorded. Not a status change, so neither the status nor updated_at moves. Returns the rows changed, 0
     * for an unknown order, a cancelled or completed one, or one whose pickup is already recorded.
     */
    @Modifying
    @Query("""
            UPDATE orders
            SET picked_up_at = :pickedUpAt
            WHERE id = :id
              AND picked_up_at IS NULL
              AND status NOT IN ('CANCELLED', 'COMPLETED')
            """)
    int recordPickup(Long id, LocalDateTime pickedUpAt);


    /**
     * When the rider picked the order up, or empty while no pickup is recorded (or for an unknown order). Read under
     * the order's row lock by a cancel, so a pickup that committed first is seen.
     */
    @Query("SELECT picked_up_at FROM orders WHERE id = :id")
    Optional<LocalDateTime> findPickedUpAt(Long id);
}
