package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.statemachine.Decision;


import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;


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
     * A time in a message: ISO-8601 local date and time to the second, as "2026-10-21T12:00:00".
     */
    static String timestamp(LocalDateTime time) {
        return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(time.truncatedTo(ChronoUnit.SECONDS));
    }
}
