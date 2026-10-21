package com.laioffer.onlineorder.aftersales;


/**
 * The steps of a refund, each declared once in {@link RefundStateMachine}.
 */
public enum RefundTrigger {
    /**
     * The executor claims a due PENDING refund for one gateway call; the call counts as an attempt.
     */
    CLAIM,
    /**
     * The executor claims a PROCESSING refund whose lease ran out (the claimant died or stalled). It keeps the status
     * and counts as another attempt (ADR 0002 section 19.9), so it is refused once the attempts are used up and the
     * executor gives the refund up instead.
     */
    RECLAIM,
    /**
     * The gateway refunded the amount.
     */
    SUCCEED,
    /**
     * The gateway call failed and may be retried: back to PENDING with a backoff, while attempts are left.
     */
    RETRY_LATER,
    /**
     * The gateway call failed for good, the attempts are used up, or the refund no longer matches its case: FAILED,
     * for an admin to look at.
     */
    GIVE_UP,
    /**
     * An admin retries a FAILED refund (POST /admin/refunds/{refundId}/retry); the attempts start over.
     */
    ADMIN_RETRY
}
