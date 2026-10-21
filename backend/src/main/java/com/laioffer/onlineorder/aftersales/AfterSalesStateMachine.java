package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.statemachine.StateMachine;
import com.laioffer.onlineorder.statemachine.Transition;


import java.util.List;
import java.util.Set;


/**
 * The after-sales case lifecycle (ADR 0002 sections 5.2, 5.3 and 19). A case is opened outside the machine: a
 * customer's request in PENDING_MERCHANT, the automatic refund of a cancelled paid order in REFUNDING. From there the
 * merchant answers once (approve, reject or a counter-offer), the customer answers a counter-offer or a rejection, an
 * admin arbitrates an escalated case, the deadline poller fires the timeout of each open status, and the refund
 * executor marks the refunded case.
 *
 * <p>Deadlines (section 19.1): the customer's and the merchant's answers are refused from the open status's deadline
 * on, even before the poller fired the timeout (guard {@value #BEFORE_DEADLINE}); a timeout fires only once its deadline
 * is due ({@value #DEADLINE_DUE}). Withdrawing and arbitrating have no deadline: until the poller fires, whoever locks
 * the case first wins.
 *
 * <p>A command that already happened answers a retry unchanged (alreadyDoneWhen): the merchant's own answer (a
 * counter-offer only with the same amount), an accepted or declined offer, an escalation, a withdrawal and the refund.
 * The timeouts and the admin decisions declare none: the poller only fires a timeout from the status it claimed, and an
 * admin's retry is answered by its idempotency key. The single writer locks the case row, decides here and saves the
 * move with the case's optimistic lock.
 */
public final class AfterSalesStateMachine {

    /**
     * The guard on the customer's and the merchant's answers; GUARD_FAILED with it maps to 409
     * AFTER_SALES_DEADLINE_PASSED.
     */
    public static final String BEFORE_DEADLINE = "beforeDeadline";

    /**
     * The guard on the timeouts.
     */
    public static final String DEADLINE_DUE = "deadlineDue";

    /**
     * The 5.2 table with section 19's deadline rules.
     */
    public static final StateMachine<AfterSalesStatus, AfterSalesTrigger, AfterSalesActor, AfterSalesTransitionContext>
            DEFINITION = StateMachine.builder(
                    "after-sales",
                    AfterSalesStatus.class,
                    AfterSalesTrigger.class,
                    AfterSalesActor.class,
                    AfterSalesTransitionContext.class
            )
            .initial(AfterSalesStatus.PENDING_MERCHANT, AfterSalesStatus.REFUNDING)
            .terminal(AfterSalesStatus.REFUNDED, AfterSalesStatus.CLOSED)
            .on(AfterSalesTrigger.MERCHANT_APPROVE)
                    .from(AfterSalesStatus.PENDING_MERCHANT).to(AfterSalesStatus.REFUNDING)
                    .by(AfterSalesActor.MERCHANT)
                    .guard(BEFORE_DEADLINE, AfterSalesTransitionContext::beforeDeadline)
                    .alreadyDoneWhen((status, context) ->
                            context.merchantDecided(AfterSalesMerchantDecision.APPROVED))
                    .add()
            .on(AfterSalesTrigger.MERCHANT_REJECT)
                    .from(AfterSalesStatus.PENDING_MERCHANT).to(AfterSalesStatus.REJECTED)
                    .by(AfterSalesActor.MERCHANT)
                    .guard(BEFORE_DEADLINE, AfterSalesTransitionContext::beforeDeadline)
                    .alreadyDoneWhen((status, context) ->
                            context.merchantDecided(AfterSalesMerchantDecision.REJECTED))
                    .add()
            .on(AfterSalesTrigger.MERCHANT_OFFER)
                    .from(AfterSalesStatus.PENDING_MERCHANT).to(AfterSalesStatus.COUNTER_OFFERED)
                    .by(AfterSalesActor.MERCHANT)
                    .guard(BEFORE_DEADLINE, AfterSalesTransitionContext::beforeDeadline)
                    // Another amount is a second answer, not a repeat.
                    .alreadyDoneWhen((status, context) -> context.sameOffer())
                    .add()
            .on(AfterSalesTrigger.MERCHANT_TIMEOUT)
                    .from(AfterSalesStatus.PENDING_MERCHANT).to(AfterSalesStatus.REFUNDING)
                    .by(AfterSalesActor.SYSTEM)
                    .guard(DEADLINE_DUE, AfterSalesTransitionContext::deadlineDue)
                    .add()
            .on(AfterSalesTrigger.CUSTOMER_ACCEPT_OFFER)
                    .from(AfterSalesStatus.COUNTER_OFFERED).to(AfterSalesStatus.REFUNDING)
                    .by(AfterSalesActor.CUSTOMER)
                    .guard(BEFORE_DEADLINE, AfterSalesTransitionContext::beforeDeadline)
                    .alreadyDoneWhen((status, context) -> context.resolvedAs(AfterSalesResolution.OFFER_ACCEPTED))
                    .add()
            .on(AfterSalesTrigger.CUSTOMER_DECLINE_OFFER)
                    .from(AfterSalesStatus.COUNTER_OFFERED).to(AfterSalesStatus.REJECTED)
                    .by(AfterSalesActor.CUSTOMER)
                    .guard(BEFORE_DEADLINE, AfterSalesTransitionContext::beforeDeadline)
                    .alreadyDoneWhen((status, context) -> status == AfterSalesStatus.REJECTED
                            && context.merchantDecided(AfterSalesMerchantDecision.OFFERED))
                    .add()
            .on(AfterSalesTrigger.OFFER_TIMEOUT)
                    .from(AfterSalesStatus.COUNTER_OFFERED).to(AfterSalesStatus.REFUNDING)
                    .by(AfterSalesActor.SYSTEM)
                    .guard(DEADLINE_DUE, AfterSalesTransitionContext::deadlineDue)
                    .add()
            .on(AfterSalesTrigger.CUSTOMER_ESCALATE)
                    .from(AfterSalesStatus.REJECTED).to(AfterSalesStatus.IN_ARBITRATION)
                    .by(AfterSalesActor.CUSTOMER)
                    .guard(BEFORE_DEADLINE, AfterSalesTransitionContext::beforeDeadline)
                    .alreadyDoneWhen((status, context) -> status == AfterSalesStatus.IN_ARBITRATION)
                    .add()
            .on(AfterSalesTrigger.CUSTOMER_TIMEOUT)
                    .from(AfterSalesStatus.REJECTED).to(AfterSalesStatus.CLOSED)
                    .by(AfterSalesActor.SYSTEM)
                    .guard(DEADLINE_DUE, AfterSalesTransitionContext::deadlineDue)
                    .add()
            .on(AfterSalesTrigger.CUSTOMER_WITHDRAW)
                    // Every open status, without a deadline guard.
                    .from(
                            AfterSalesStatus.PENDING_MERCHANT,
                            AfterSalesStatus.COUNTER_OFFERED,
                            AfterSalesStatus.REJECTED,
                            AfterSalesStatus.IN_ARBITRATION
                    )
                    .to(AfterSalesStatus.CLOSED)
                    .by(AfterSalesActor.CUSTOMER)
                    .alreadyDoneWhen((status, context) -> status == AfterSalesStatus.CLOSED
                            && context.closedAs(AfterSalesCloseReason.WITHDRAWN))
                    .add()
            .on(AfterSalesTrigger.ADMIN_APPROVE_FULL)
                    .from(AfterSalesStatus.IN_ARBITRATION).to(AfterSalesStatus.REFUNDING)
                    .by(AfterSalesActor.ADMIN)
                    .add()
            .on(AfterSalesTrigger.ADMIN_APPROVE_PARTIAL)
                    .from(AfterSalesStatus.IN_ARBITRATION).to(AfterSalesStatus.REFUNDING)
                    .by(AfterSalesActor.ADMIN)
                    .add()
            .on(AfterSalesTrigger.ADMIN_REJECT)
                    .from(AfterSalesStatus.IN_ARBITRATION).to(AfterSalesStatus.CLOSED)
                    .by(AfterSalesActor.ADMIN)
                    .add()
            .on(AfterSalesTrigger.ARBITRATION_TIMEOUT)
                    .from(AfterSalesStatus.IN_ARBITRATION).to(AfterSalesStatus.REFUNDING)
                    .by(AfterSalesActor.SYSTEM)
                    .guard(DEADLINE_DUE, AfterSalesTransitionContext::deadlineDue)
                    .add()
            .on(AfterSalesTrigger.REFUND_SUCCEEDED)
                    .from(AfterSalesStatus.REFUNDING).to(AfterSalesStatus.REFUNDED)
                    .by(AfterSalesActor.SYSTEM)
                    .alreadyDoneWhen((status, context) -> status == AfterSalesStatus.REFUNDED)
                    .add()
            .build();

    private static final List<AfterSalesTrigger> TIMEOUT_TRIGGERS = List.of(
            AfterSalesTrigger.MERCHANT_TIMEOUT,
            AfterSalesTrigger.OFFER_TIMEOUT,
            AfterSalesTrigger.CUSTOMER_TIMEOUT,
            AfterSalesTrigger.ARBITRATION_TIMEOUT
    );


    private AfterSalesStateMachine() {
    }


    /**
     * The trigger the deadline poller fires on a case whose deadline in {@code waiting} is due: MERCHANT_TIMEOUT,
     * OFFER_TIMEOUT, CUSTOMER_TIMEOUT or ARBITRATION_TIMEOUT. Chosen from the declaration, as the timeout that fires
     * from exactly that status.
     *
     * @throws IllegalArgumentException for a status without a deadline
     */
    public static AfterSalesTrigger timeoutTrigger(AfterSalesStatus waiting) {
        Set<AfterSalesStatus> only = Set.of(waiting);
        for (AfterSalesTrigger trigger : TIMEOUT_TRIGGERS) {
            if (DEFINITION.transition(trigger).from().equals(only)) {
                return trigger;
            }
        }
        throw new IllegalArgumentException("No deadline runs out on an after-sales case in " + waiting);
    }


    /**
     * The message of the 409 AFTER_SALES_STATUS_CONFLICT a {@code NOT_FROM_CURRENT_STATE} rejection maps to (section
     * 5.2), with the trigger's from-statuses in declaration order: "After-sales case is REFUNDING; only
     * PENDING_MERCHANT cases can be approved".
     */
    public static String statusConflictMessage(AfterSalesTrigger trigger, AfterSalesStatus current) {
        Transition<AfterSalesStatus, AfterSalesTrigger, AfterSalesActor, AfterSalesTransitionContext> transition =
                DEFINITION.transition(trigger);
        List<String> from = transition.from().stream().map(AfterSalesStatus::name).toList();
        return "After-sales case is " + current + "; only " + String.join(" or ", from) + " cases can be "
                + trigger.verb();
    }
}
