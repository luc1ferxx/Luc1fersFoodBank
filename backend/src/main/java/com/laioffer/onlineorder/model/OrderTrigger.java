package com.laioffer.onlineorder.model;


/**
 * The commands that change an order's status, each declared once in {@link OrderStateMachine}. The caller names its
 * trigger; it is never inferred from the actor or the target status alone.
 */
public enum OrderTrigger {
    /**
     * The customer cancels their order (POST /orders/{orderId}/cancel).
     */
    CUSTOMER_CANCEL,
    /**
     * The merchant accepts a paid order.
     */
    MERCHANT_ACCEPT,
    /**
     * The merchant rejects a paid order; a repeat only counts as done when the cancel reason has the same code.
     */
    MERCHANT_REJECT,
    /**
     * The merchant starts preparing an accepted order.
     */
    MERCHANT_START_PREPARING,
    /**
     * The payment deadline ran out on a PLACED order.
     */
    PAYMENT_TIMEOUT,
    /**
     * The accept deadline ran out on a PAID order.
     */
    ACCEPT_TIMEOUT,
    /**
     * The first of the two steps a delivered ACCEPTED order is completed with: food that was delivered was prepared.
     */
    DELIVERY_PREPARE,
    /**
     * The delivered order is completed.
     */
    DELIVERY_COMPLETE,
    /**
     * An admin PATCH to PAID.
     */
    ADMIN_MARK_PAID,
    /**
     * An admin PATCH to ACCEPTED.
     */
    ADMIN_ACCEPT,
    /**
     * An admin PATCH to PREPARING.
     */
    ADMIN_START_PREPARING,
    /**
     * An admin PATCH to COMPLETED.
     */
    ADMIN_COMPLETE,
    /**
     * An admin PATCH to CANCELLED.
     */
    ADMIN_CANCEL,
    /**
     * An admin PATCH to PLACED: a no-op for a PLACED order and refused for every other one, since no order moves back
     * to PLACED.
     */
    ADMIN_KEEP_PLACED
}
