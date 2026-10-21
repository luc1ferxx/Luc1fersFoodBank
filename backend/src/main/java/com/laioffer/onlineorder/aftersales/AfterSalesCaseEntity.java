package com.laioffer.onlineorder.aftersales;


import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.math.BigDecimal;
import java.time.LocalDateTime;


/**
 * An after-sales case: a refund request on an order (ADR 0002 sections 5 and 8). Every amount is in {@code currency},
 * the order's.
 *
 * @param restaurantId    the order's restaurant; null only for an ORDER_CANCELLED case of an order without one
 * @param kind            CUSTOMER_REQUEST or ORDER_CANCELLED
 * @param attemptNo       1, or 2 for a customer's request after withdrawing the first; always 1 for ORDER_CANCELLED
 * @param orderTotal      the order total when the case was opened
 * @param requestedAmount the sum of the case lines
 * @param offeredAmount   the merchant's counter-offer, below the requested amount
 * @param approvedAmount  the amount to refund, set when the case enters REFUNDING; the refund's amount
 * @param refundedAmount  the approved amount once the refund succeeded (REFUNDED)
 * @param deadlineAt      the deadline of the open status; null in REFUNDING, REFUNDED and CLOSED
 * @param deadlineRetryAt when the poller retries a deadline whose processing failed; null otherwise
 * @param version         the optimistic lock: Spring Data JDBC saves with {@code WHERE id = ? AND version = ?} and
 *                        throws {@link org.springframework.dao.OptimisticLockingFailureException} when the row moved on
 */
@Table("after_sales_cases")
public record AfterSalesCaseEntity(
        @Id
        @Column("id")
        Long id,
        @Column("order_id")
        Long orderId,
        @Column("customer_id")
        Long customerId,
        @Column("restaurant_id")
        Long restaurantId,
        @Column("kind")
        String kind,
        @Column("attempt_no")
        Integer attemptNo,
        @Column("status")
        String status,
        @Column("reason_code")
        String reasonCode,
        @Column("description")
        String description,
        @Column("order_total")
        BigDecimal orderTotal,
        @Column("requested_amount")
        BigDecimal requestedAmount,
        @Column("offered_amount")
        BigDecimal offeredAmount,
        @Column("approved_amount")
        BigDecimal approvedAmount,
        @Column("refunded_amount")
        BigDecimal refundedAmount,
        @Column("currency")
        String currency,
        @Column("merchant_decision")
        String merchantDecision,
        @Column("merchant_note")
        String merchantNote,
        @Column("resolution")
        String resolution,
        @Column("close_reason")
        String closeReason,
        @Column("admin_note")
        String adminNote,
        @Column("deadline_at")
        LocalDateTime deadlineAt,
        @Column("deadline_retry_at")
        LocalDateTime deadlineRetryAt,
        @Column("deadline_attempts")
        Integer deadlineAttempts,
        @Version
        @Column("version")
        Long version,
        @Column("created_at")
        LocalDateTime createdAt,
        @Column("updated_at")
        LocalDateTime updatedAt
) {
}
