package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.fulfilment.FulfilmentEvents;


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


    /**
     * The type of the after-sales event a step into this status publishes (ADR 0002 section 10.1). Opening a case
     * publishes {@link FulfilmentEvents#AFTER_SALES_CASE_OPENED} whatever status it opens in; no step leads back to
     * PENDING_MERCHANT, so case_opened is its type too.
     */
    public String eventType() {
        return switch (this) {
            case PENDING_MERCHANT -> FulfilmentEvents.AFTER_SALES_CASE_OPENED;
            case COUNTER_OFFERED -> FulfilmentEvents.AFTER_SALES_OFFER_MADE;
            case REJECTED -> FulfilmentEvents.AFTER_SALES_REQUEST_REJECTED;
            case IN_ARBITRATION -> FulfilmentEvents.AFTER_SALES_CASE_ESCALATED;
            case REFUNDING -> FulfilmentEvents.AFTER_SALES_REFUND_APPROVED;
            case REFUNDED -> FulfilmentEvents.AFTER_SALES_CASE_REFUNDED;
            case CLOSED -> FulfilmentEvents.AFTER_SALES_CASE_CLOSED;
        };
    }
}
