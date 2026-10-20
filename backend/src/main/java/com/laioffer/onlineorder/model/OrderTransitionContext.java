package com.laioffer.onlineorder.model;


import java.util.Objects;


/**
 * What {@link OrderTrigger#MERCHANT_REJECT} reads to tell a repeated reject from a conflicting one; the other triggers
 * read no context and are decided with null.
 *
 * @param currentCancelReason   the cancel reason the locked order has
 * @param requestedCancelReason the cancel reason the command asks for
 */
public record OrderTransitionContext(String currentCancelReason, String requestedCancelReason) {

    /**
     * True when both cancel reasons have the same code: a reject of an order this command already rejected, not of
     * one the customer or a timeout cancelled.
     */
    public boolean sameCancelReasonCode() {
        return Objects.equals(cancelReasonCode(currentCancelReason), cancelReasonCode(requestedCancelReason));
    }


    /**
     * The code of a cancel reason: MERCHANT_REJECTED for "MERCHANT_REJECTED: out of buns"; null for no reason.
     */
    public static String cancelReasonCode(String cancelReason) {
        if (cancelReason == null) {
            return null;
        }
        int separator = cancelReason.indexOf(':');
        return (separator < 0 ? cancelReason : cancelReason.substring(0, separator)).trim();
    }
}
