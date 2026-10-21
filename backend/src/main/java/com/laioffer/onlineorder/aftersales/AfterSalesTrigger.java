package com.laioffer.onlineorder.aftersales;


/**
 * The commands and events that change an after-sales case, each declared once in {@link AfterSalesStateMachine}. The
 * caller names its trigger; opening a case is not one (the single writer inserts it).
 */
public enum AfterSalesTrigger {
    /**
     * The merchant approves the requested amount.
     */
    MERCHANT_APPROVE("approved"),
    /**
     * The merchant rejects the request, with a reason.
     */
    MERCHANT_REJECT("rejected"),
    /**
     * The merchant offers a partial refund; a repeat only counts as done with the same amount.
     */
    MERCHANT_OFFER("given a counter-offer"),
    /**
     * The merchant's deadline ran out: the requested amount is refunded.
     */
    MERCHANT_TIMEOUT("timed out"),
    /**
     * The customer accepts the counter-offer.
     */
    CUSTOMER_ACCEPT_OFFER("accepted"),
    /**
     * The customer declines the counter-offer.
     */
    CUSTOMER_DECLINE_OFFER("declined"),
    /**
     * The customer's deadline on a counter-offer ran out: the offered amount is refunded.
     */
    OFFER_TIMEOUT("timed out"),
    /**
     * The customer asks the platform to step in.
     */
    CUSTOMER_ESCALATE("escalated"),
    /**
     * The customer's deadline on a rejection ran out: the case is closed.
     */
    CUSTOMER_TIMEOUT("timed out"),
    /**
     * The customer withdraws an open case.
     */
    CUSTOMER_WITHDRAW("withdrawn"),
    /**
     * An admin grants the requested amount.
     */
    ADMIN_APPROVE_FULL("arbitrated"),
    /**
     * An admin grants part of the requested amount.
     */
    ADMIN_APPROVE_PARTIAL("arbitrated"),
    /**
     * An admin rejects the request: the case is closed.
     */
    ADMIN_REJECT("arbitrated"),
    /**
     * The arbitration deadline ran out: the requested amount is refunded.
     */
    ARBITRATION_TIMEOUT("timed out"),
    /**
     * The refund executor refunded the approved amount.
     */
    REFUND_SUCCEEDED("marked refunded");

    private final String verb;


    AfterSalesTrigger(String verb) {
        this.verb = verb;
    }


    /**
     * The past participle the 409 AFTER_SALES_STATUS_CONFLICT message ends with: "... only PENDING_MERCHANT cases can
     * be approved" ({@link AfterSalesStateMachine#statusConflictMessage}).
     */
    public String verb() {
        return verb;
    }
}
