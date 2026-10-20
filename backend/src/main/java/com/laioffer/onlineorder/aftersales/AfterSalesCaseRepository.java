package com.laioffer.onlineorder.aftersales;


import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;
import java.util.List;


/**
 * Cases are inserted and changed with {@code save}, whose version check is the compare-and-set of every case write.
 * The lock queries are the decision points of the commands (ADR 0002 section 7); a missing row and another customer's
 * or restaurant's case are both null.
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
