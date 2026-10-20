package com.laioffer.onlineorder.aftersales;


import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;


/**
 * Cases are inserted with {@code save}; every step after the opening is written with {@link #compareAndSetStep}, the
 * compare-and-set of the single writer ({@code AfterSalesTransitions}). The lock queries are the decision points of the
 * commands (ADR 0002 section 7); a missing row and another customer's or restaurant's case are both null.
 */
public interface AfterSalesCaseRepository extends ListCrudRepository<AfterSalesCaseEntity, Long> {

    @Query("""
            SELECT *
            FROM after_sales_cases
            WHERE id = :id
            FOR UPDATE
            """)
    AfterSalesCaseEntity lockById(Long id);


    @Query("""
            SELECT *
            FROM after_sales_cases
            WHERE id = :id
              AND customer_id = :customerId
            FOR UPDATE
            """)
    AfterSalesCaseEntity lockByIdAndCustomerId(Long id, Long customerId);


    @Query("""
            SELECT *
            FROM after_sales_cases
            WHERE id = :id
              AND restaurant_id = :restaurantId
            FOR UPDATE
            """)
    AfterSalesCaseEntity lockByIdAndRestaurantId(Long id, Long restaurantId);


    /**
     * The cases of an order, oldest first: at most an ORDER_CANCELLED case, or one or two customer requests.
     */
    @Query("""
            SELECT *
            FROM after_sales_cases
            WHERE order_id = :orderId
            ORDER BY id
            """)
    List<AfterSalesCaseEntity> findAllByOrderId(Long orderId);


    /**
     * The cases of several orders, by order and then oldest first; {@code orderIds} must not be empty.
     */
    @Query("""
            SELECT *
            FROM after_sales_cases
            WHERE order_id IN (:orderIds)
            ORDER BY order_id, id
            """)
    List<AfterSalesCaseEntity> findAllByOrderIdIn(Collection<Long> orderIds);


    /**
     * Writes one step of a case (ADR 0002 section 5.2): a compare-and-set on the status and the version the single
     * writer read under the row lock, which also raises the version by one and clears the deadline retry of the status
     * left behind. Every other column of the step is written as given. Returns the rows changed, 1, or 0 when the case
     * moved on (checked with {@link com.laioffer.onlineorder.statemachine.CompareAndSet}).
     */
    @Modifying
    @Query("""
            UPDATE after_sales_cases
            SET status = :status,
                offered_amount = :offeredAmount,
                approved_amount = :approvedAmount,
                refunded_amount = :refundedAmount,
                merchant_decision = :merchantDecision,
                merchant_note = :merchantNote,
                resolution = :resolution,
                close_reason = :closeReason,
                admin_note = :adminNote,
                deadline_at = :deadlineAt,
                deadline_retry_at = NULL,
                deadline_attempts = 0,
                version = version + 1,
                updated_at = :updatedAt
            WHERE id = :id
              AND status = :expectedStatus
              AND version = :expectedVersion
            """)
    int compareAndSetStep(
            Long id,
            String expectedStatus,
            Long expectedVersion,
            String status,
            BigDecimal offeredAmount,
            BigDecimal approvedAmount,
            BigDecimal refundedAmount,
            String merchantDecision,
            String merchantNote,
            String resolution,
            String closeReason,
            String adminNote,
            LocalDateTime deadlineAt,
            LocalDateTime updatedAt
    );


    @Query("""
            SELECT *
            FROM after_sales_cases
            WHERE customer_id = :customerId
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """)
    List<AfterSalesCaseEntity> findPageByCustomerId(Long customerId, int limit);


    @Query("""
            SELECT *
            FROM after_sales_cases
            WHERE status = :status
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """)
    List<AfterSalesCaseEntity> findPageByStatus(String status, int limit);


    @Query("""
            SELECT *
            FROM after_sales_cases
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """)
    List<AfterSalesCaseEntity> findRecent(int limit);


    /**
     * Claims the most overdue case whose deadline is due and not backing off. SKIP LOCKED lets pollers of several
     * instances claim different cases; a command holding the row lock makes the poller skip the case until the
     * command's transaction ends.
     */
    @Query("""
            SELECT *
            FROM after_sales_cases
            WHERE deadline_at <= :now
              AND (deadline_retry_at IS NULL OR deadline_retry_at <= :now)
            ORDER BY deadline_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    AfterSalesCaseEntity claimNextDue(LocalDateTime now);


    /**
     * Backs off a deadline whose processing failed, only while the case is still at {@code version}: a case a command
     * moved on meanwhile has a new deadline, which a stale retry must not touch (ADR 0002 section 19.3). Leaves the
     * version as it is, so that a concurrent command still saves. Returns the rows changed, 1 or 0.
     */
    @Modifying
    @Query("""
            UPDATE after_sales_cases
            SET deadline_retry_at = :retryAt,
                deadline_attempts = :attempts
            WHERE id = :id
              AND version = :version
            """)
    int rescheduleDeadline(Long id, Long version, LocalDateTime retryAt, Integer attempts);
}
