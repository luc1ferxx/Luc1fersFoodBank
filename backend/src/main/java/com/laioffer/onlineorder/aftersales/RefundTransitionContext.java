package com.laioffer.onlineorder.aftersales;


import java.time.LocalDateTime;
import java.util.Objects;


/**
 * What the refund guards read: RECLAIM's lease and attempts, RETRY_LATER's attempts. CLAIM, SUCCEED, GIVE_UP and
 * ADMIN_RETRY read no context and may be decided with null.
 *
 * @param current     the refund as locked by the caller
 * @param now         the time of the step, read after the refund was locked
 * @param maxAttempts the gateway calls a refund may make before the executor gives it up
 *                    ({@code app.after-sales.refund.max-attempts})
 */
public record RefundTransitionContext(RefundEntity current, LocalDateTime now, int maxAttempts) {

    public RefundTransitionContext {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(now, "now");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("A refund needs at least one attempt, got " + maxAttempts);
        }
    }


    /**
     * True once the lease of the PROCESSING refund ran out ({@code claimed_until <= now}), the moment the claim query
     * returns it again.
     */
    public boolean leaseExpired() {
        // Set on every PROCESSING refund (chk_refund_processing); the guard only runs on those.
        LocalDateTime claimedUntil = Objects.requireNonNull(current.claimedUntil(), "A PROCESSING refund has a lease");
        return !claimedUntil.isAfter(now);
    }


    /**
     * True while the attempts made so far (a reclaim counts as one) are fewer than {@link #maxAttempts}: another
     * gateway call is allowed.
     */
    public boolean attemptsLeft() {
        return current.attempts() < maxAttempts;
    }
}
