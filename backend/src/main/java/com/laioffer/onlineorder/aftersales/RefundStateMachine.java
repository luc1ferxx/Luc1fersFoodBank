package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.statemachine.StateMachine;


/**
 * The refund lifecycle (ADR 0002 sections 5.4, 9 and 19.9). The single writer inserts a refund PENDING when its case
 * enters REFUNDING; the executor claims it (holding a lease), calls the gateway outside the transaction and records
 * the result: SUCCEEDED, back to PENDING to retry later, or FAILED. FAILED is not final: an admin retry starts the
 * attempts over.
 *
 * <p>The attempts are capped: every claim counts as one, a reclaim of an expired lease included, and neither RECLAIM
 * nor RETRY_LATER passes the guard {@value #ATTEMPTS_LEFT} once {@code max-attempts} were made. The executor then fires
 * GIVE_UP, which every PROCESSING refund can take, instead of reclaiming or retrying it forever.
 */
public final class RefundStateMachine {

    /**
     * RECLAIM's first guard: the lease of the claim it takes over ran out.
     */
    public static final String LEASE_EXPIRED = "leaseExpired";

    /**
     * The guard of RECLAIM and RETRY_LATER: another gateway call is allowed. GUARD_FAILED with it means GIVE_UP.
     */
    public static final String ATTEMPTS_LEFT = "attemptsLeft";

    /**
     * The 5.4 table with the attempt cap of section 19.9.
     */
    public static final StateMachine<RefundStatus, RefundTrigger, AfterSalesActor, RefundTransitionContext>
            DEFINITION = StateMachine.builder(
                    "refund",
                    RefundStatus.class,
                    RefundTrigger.class,
                    AfterSalesActor.class,
                    RefundTransitionContext.class
            )
            .initial(RefundStatus.PENDING)
            .terminal(RefundStatus.SUCCEEDED)
            .on(RefundTrigger.CLAIM)
                    .from(RefundStatus.PENDING).to(RefundStatus.PROCESSING)
                    .by(AfterSalesActor.SYSTEM)
                    .add()
            .on(RefundTrigger.RECLAIM)
                    .from(RefundStatus.PROCESSING).stay()
                    .by(AfterSalesActor.SYSTEM)
                    .guard(LEASE_EXPIRED, RefundTransitionContext::leaseExpired)
                    .guard(ATTEMPTS_LEFT, RefundTransitionContext::attemptsLeft)
                    .add()
            .on(RefundTrigger.SUCCEED)
                    .from(RefundStatus.PROCESSING).to(RefundStatus.SUCCEEDED)
                    .by(AfterSalesActor.SYSTEM)
                    .alreadyDoneWhen((status, context) -> status == RefundStatus.SUCCEEDED)
                    .add()
            .on(RefundTrigger.RETRY_LATER)
                    .from(RefundStatus.PROCESSING).to(RefundStatus.PENDING)
                    .by(AfterSalesActor.SYSTEM)
                    .guard(ATTEMPTS_LEFT, RefundTransitionContext::attemptsLeft)
                    .add()
            .on(RefundTrigger.GIVE_UP)
                    .from(RefundStatus.PROCESSING).to(RefundStatus.FAILED)
                    .by(AfterSalesActor.SYSTEM)
                    .add()
            .on(RefundTrigger.ADMIN_RETRY)
                    .from(RefundStatus.FAILED).to(RefundStatus.PENDING)
                    .by(AfterSalesActor.ADMIN)
                    .alreadyDoneWhen((status, context) -> status == RefundStatus.PENDING)
                    .add()
            .build();


    private RefundStateMachine() {
    }
}
