package com.laioffer.onlineorder.aftersales;


/**
 * The status of a refund (refunds.status; ADR 0002 sections 5.4 and 9).
 */
public enum RefundStatus {
    /**
     * Waiting for the executor, from next_attempt_at on.
     */
    PENDING,
    /**
     * Claimed by an executor, which calls the gateway outside the transaction; the lease ends at claimed_until.
     */
    PROCESSING,
    /**
     * The gateway refunded the amount. Final.
     */
    SUCCEEDED,
    /**
     * The executor gave up; an admin can retry it. Not final.
     */
    FAILED
}
