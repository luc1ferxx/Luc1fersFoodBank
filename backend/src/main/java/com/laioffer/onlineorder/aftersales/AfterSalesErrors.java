package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.statemachine.Decision;


import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;


/**
 * The error codes of the after-sales API (ADR 0002 sections 11.1 and 20) and how a refused step maps to them.
 */
public final class AfterSalesErrors {

    /**
     * 409: the order cannot get an after-sales request (not COMPLETED, no restaurant, no captured payment, outside the
     * request window, a request already open, or no request left).
     */
    public static final String AFTER_SALES_NOT_ELIGIBLE = "AFTER_SALES_NOT_ELIGIBLE";

    /**
     * 409: the case's status does not allow the command.
     */
    public static final String AFTER_SALES_STATUS_CONFLICT = "AFTER_SALES_STATUS_CONFLICT";

    /**
     * 409: the deadline of the case's current step passed (the customer's and the merchant's answers only).
     */
    public static final String AFTER_SALES_DEADLINE_PASSED = "AFTER_SALES_DEADLINE_PASSED";

    /**
     * 409: the refund's status does not allow the command (an admin retry of a refund that is not FAILED).
     */
    public static final String REFUND_STATUS_CONFLICT = "REFUND_STATUS_CONFLICT";


    private AfterSalesErrors() {
    }


    public static ConflictException notEligible(String message) {
        return new ConflictException(AFTER_SALES_NOT_ELIGIBLE, message);
    }


    /**
     * The 409 of a command the after-sales machine refused on {@code current} (section 5.2):
     * AFTER_SALES_STATUS_CONFLICT "After-sales case is REFUNDING; only PENDING_MERCHANT cases can be approved" when
     * the status does not allow it, AFTER_SALES_DEADLINE_PASSED "The deadline of this after-sales step passed at ..."
     * when the answer came too late.
     *
     * @throws IllegalStateException for a refusal by another guard: only the timeouts carry one, and the poller counts
     *                               their refusals as skipped instead of answering a request
     */
    public static ConflictException refused(
            Decision.Rejected<AfterSalesStatus, AfterSalesTrigger> rejected,
            AfterSalesCaseEntity current
    ) {
        if (rejected.reason() == Decision.Reason.NOT_FROM_CURRENT_STATE) {
            return new ConflictException(
                    AFTER_SALES_STATUS_CONFLICT,
                    AfterSalesStateMachine.statusConflictMessage(rejected.trigger(), rejected.current())
            );
        }
        if (AfterSalesStateMachine.BEFORE_DEADLINE.equals(rejected.guard())) {
            return new ConflictException(
                    AFTER_SALES_DEADLINE_PASSED,
                    "The deadline of this after-sales step passed at " + timestamp(current.deadlineAt())
            );
        }
        throw new IllegalStateException("No API error for " + rejected);
    }


    /**
     * The 409 REFUND_STATUS_CONFLICT of an admin retry the refund machine refused (section 5.4), with the statuses
     * ADMIN_RETRY fires from in declaration order: "Refund is SUCCEEDED; only FAILED refunds can be retried".
     *
     * @throws IllegalStateException for another refusal: ADMIN_RETRY has no guard, and the executor's own steps answer
     *                               no request
     */
    public static ConflictException refundRetryRefused(Decision.Rejected<RefundStatus, RefundTrigger> rejected) {
        if (rejected.trigger() != RefundTrigger.ADMIN_RETRY
                || rejected.reason() != Decision.Reason.NOT_FROM_CURRENT_STATE) {
            throw new IllegalStateException("No API error for " + rejected);
        }
        List<String> from = RefundStateMachine.DEFINITION.transition(RefundTrigger.ADMIN_RETRY).from().stream()
                .map(RefundStatus::name)
                .toList();
        return new ConflictException(
                REFUND_STATUS_CONFLICT,
                "Refund is " + rejected.current() + "; only " + String.join(" or ", from) + " refunds can be retried"
        );
    }


    /**
     * A time in a message: ISO-8601 date and time to the second with its zone named, as "2026-10-21T12:00:00 UTC". The
     * server runs in UTC, and the UI shows a message as it comes while it shows every other time in the browser's zone.
     */
    static String timestamp(LocalDateTime time) {
        return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(time.truncatedTo(ChronoUnit.SECONDS)) + " UTC";
    }
}
