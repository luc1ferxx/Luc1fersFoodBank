package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.statemachine.Decision;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


import java.math.BigDecimal;
import java.time.LocalDateTime;


/**
 * How a refused after-sales step maps to its 409 (ADR 0002 section 5.2), and a refused admin retry of a refund to its
 * own (section 5.4).
 */
class AfterSalesErrorsTests {

    private static final AfterSalesCaseEntity OFFERED = new AfterSalesCaseEntity(501L, 1001L, 3L, 7L,
            "CUSTOMER_REQUEST", 1, "COUNTER_OFFERED", "OTHER", "x", new BigDecimal("9.00"), new BigDecimal("9.00"),
            new BigDecimal("4.00"), null, null, "USD", "OFFERED", null, null, null, null,
            LocalDateTime.of(2026, 10, 22, 9, 30), null, 0, 1L, LocalDateTime.of(2026, 10, 21, 9, 0),
            LocalDateTime.of(2026, 10, 21, 9, 30));


    @Test
    void refused_byTheStatus_shouldBeAStatusConflictNamingTheStatusesTheCommandNeeds() {
        ConflictException conflict = AfterSalesErrors.refused(new Decision.Rejected<>(
                AfterSalesTrigger.CUSTOMER_WITHDRAW, AfterSalesStatus.REFUNDED, Decision.Reason.NOT_FROM_CURRENT_STATE,
                null), OFFERED);

        Assertions.assertEquals("AFTER_SALES_STATUS_CONFLICT", conflict.getCode());
        Assertions.assertEquals("After-sales case is REFUNDED; only PENDING_MERCHANT or COUNTER_OFFERED or REJECTED or "
                + "IN_ARBITRATION cases can be withdrawn", conflict.getMessage());
    }


    @Test
    void refused_byTheDeadline_shouldNameTheDeadlineToTheSecond() {
        ConflictException conflict = AfterSalesErrors.refused(new Decision.Rejected<>(
                AfterSalesTrigger.CUSTOMER_ACCEPT_OFFER, AfterSalesStatus.COUNTER_OFFERED, Decision.Reason.GUARD_FAILED,
                AfterSalesStateMachine.BEFORE_DEADLINE), OFFERED);

        Assertions.assertEquals("AFTER_SALES_DEADLINE_PASSED", conflict.getCode());
        Assertions.assertEquals("The deadline of this after-sales step passed at 2026-10-22T09:30:00 UTC",
                conflict.getMessage());
    }


    @Test
    void refused_byATimeoutsGuard_shouldBeAProgrammingError() {
        Decision.Rejected<AfterSalesStatus, AfterSalesTrigger> timeout = new Decision.Rejected<>(
                AfterSalesTrigger.OFFER_TIMEOUT, AfterSalesStatus.COUNTER_OFFERED, Decision.Reason.GUARD_FAILED,
                AfterSalesStateMachine.DEADLINE_DUE);

        Assertions.assertThrows(IllegalStateException.class, () -> AfterSalesErrors.refused(timeout, OFFERED));
    }


    @Test
    void refundRetryRefused_shouldBeARefundStatusConflictNamingTheStatusARetryNeeds() {
        for (RefundStatus status : new RefundStatus[]{RefundStatus.PROCESSING, RefundStatus.SUCCEEDED}) {
            ConflictException conflict = AfterSalesErrors.refundRetryRefused(new Decision.Rejected<>(
                    RefundTrigger.ADMIN_RETRY, status, Decision.Reason.NOT_FROM_CURRENT_STATE, null));

            Assertions.assertEquals("REFUND_STATUS_CONFLICT", conflict.getCode());
            Assertions.assertEquals("Refund is " + status + "; only FAILED refunds can be retried",
                    conflict.getMessage());
        }
    }


    @Test
    void refundRetryRefused_ofAnotherStepOrByAGuard_shouldBeAProgrammingError() {
        Decision.Rejected<RefundStatus, RefundTrigger> claim = new Decision.Rejected<>(
                RefundTrigger.CLAIM, RefundStatus.FAILED, Decision.Reason.NOT_FROM_CURRENT_STATE, null);
        Decision.Rejected<RefundStatus, RefundTrigger> guarded = new Decision.Rejected<>(
                RefundTrigger.ADMIN_RETRY, RefundStatus.FAILED, Decision.Reason.GUARD_FAILED, "someGuard");

        Assertions.assertThrows(IllegalStateException.class, () -> AfterSalesErrors.refundRetryRefused(claim));
        Assertions.assertThrows(IllegalStateException.class, () -> AfterSalesErrors.refundRetryRefused(guarded));
    }


    @Test
    void notEligible_shouldCarryItsCode() {
        ConflictException conflict = AfterSalesErrors.notEligible("Order has no captured payment");

        Assertions.assertEquals("AFTER_SALES_NOT_ELIGIBLE", conflict.getCode());
        Assertions.assertEquals("Order has no captured payment", conflict.getMessage());
        // Truncated to the second, with the zone the server runs in.
        Assertions.assertEquals("2026-10-22T09:30:15 UTC",
                AfterSalesErrors.timestamp(LocalDateTime.of(2026, 10, 22, 9, 30, 15, 999_999_000)));
    }
}
