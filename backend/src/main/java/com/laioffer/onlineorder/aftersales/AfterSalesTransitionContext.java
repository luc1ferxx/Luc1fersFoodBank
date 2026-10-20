package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.money.Money;


import java.time.LocalDateTime;
import java.util.Objects;


/**
 * What the after-sales guards and alreadyDoneWhen checks read: the locked case, the time of the command and the amount
 * it carries.
 *
 * @param current the case as locked ({@code FOR UPDATE}) by the caller
 * @param now     the time of the command, read after the case was locked (ADR 0002 section 19.2), so that a command
 *                that waited for the lock is judged at the time it got it
 * @param amount  the amount of a counter-offer, in the case's currency; null for every other command
 */
public record AfterSalesTransitionContext(AfterSalesCaseEntity current, LocalDateTime now, Money amount) {

    public AfterSalesTransitionContext {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(now, "now");
    }


    /**
     * The context of a command without an amount.
     */
    public AfterSalesTransitionContext(AfterSalesCaseEntity current, LocalDateTime now) {
        this(current, now, null);
    }


    /**
     * True while the open status's deadline has not come: the customer and the merchant can still answer. The
     * deadline itself is already too late.
     */
    public boolean beforeDeadline() {
        return now.isBefore(deadlineAt());
    }


    /**
     * True from the open status's deadline on: its timeout may fire.
     */
    public boolean deadlineDue() {
        return !beforeDeadline();
    }


    public boolean merchantDecided(AfterSalesMerchantDecision decision) {
        return decision.name().equals(current.merchantDecision());
    }


    /**
     * True when the merchant already offered {@link #amount}: a repeated offer. The amounts compare by value
     * ({@link Money#compareTo}), so 12.0 and 12.00 are the same offer.
     */
    public boolean sameOffer() {
        return merchantDecided(AfterSalesMerchantDecision.OFFERED)
                && current.offeredAmount() != null
                && amount != null
                && Money.of(current.offeredAmount(), current.currency()).compareTo(amount) == 0;
    }


    public boolean resolvedAs(AfterSalesResolution resolution) {
        return resolution.name().equals(current.resolution());
    }


    public boolean closedAs(AfterSalesCloseReason reason) {
        return reason.name().equals(current.closeReason());
    }


    private LocalDateTime deadlineAt() {
        // Set in every open status (chk_after_sales_case_deadline); the deadline guards only run in those.
        return Objects.requireNonNull(current.deadlineAt(), "An open after-sales case has a deadline");
    }
}
