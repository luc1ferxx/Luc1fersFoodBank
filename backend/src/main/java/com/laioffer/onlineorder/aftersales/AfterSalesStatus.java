package com.laioffer.onlineorder.aftersales;


/**
 * The status of an after-sales case (after_sales_cases.status; ADR 0002 section 5.1). The four open statuses wait for
 * someone's answer until a deadline; REFUNDING waits for the refund executor; REFUNDED and CLOSED are final.
 */
public enum AfterSalesStatus {
    /**
     * The customer asked for a refund; the merchant approves, rejects or makes a counter-offer.
     */
    PENDING_MERCHANT(true),
    /**
     * The merchant offered a partial refund; the customer accepts or declines it.
     */
    COUNTER_OFFERED(true),
    /**
     * The merchant rejected the request or the customer declined the offer; the customer may ask the platform to step
     * in.
     */
    REJECTED(true),
    /**
     * An admin arbitrates.
     */
    IN_ARBITRATION(true),
    /**
     * The approved amount is being refunded.
     */
    REFUNDING(false),
    /**
     * The approved amount was refunded.
     */
    REFUNDED(false),
    /**
     * Withdrawn, timed out without a refund, or rejected by arbitration.
     */
    CLOSED(false);

    private final boolean open;


    AfterSalesStatus(boolean open) {
        this.open = open;
    }


    /**
     * True for the statuses that wait for an answer until a deadline (deadline_at is set exactly in these) and that the
     * customer can withdraw from.
     */
    public boolean isOpen() {
        return open;
    }
}
