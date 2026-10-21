package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.money.CurrencyMismatchException;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.statemachine.Decision;
import com.laioffer.onlineorder.statemachine.Guard;
import com.laioffer.onlineorder.statemachine.StateMachine;
import com.laioffer.onlineorder.statemachine.Transition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * The after-sales declaration of ADR 0002 section 5.2 with section 19's deadline rules: what every trigger decides from
 * every status on either side of the deadline, each trigger's actors, guards and retry rule, and the diagram of section
 * 5.3.
 */
class AfterSalesStateMachineTests {

    private static final StateMachine<AfterSalesStatus, AfterSalesTrigger, AfterSalesActor, AfterSalesTransitionContext>
            MACHINE = AfterSalesStateMachine.DEFINITION;

    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 10, 21, 12, 0);

    private static final long ONE_HOUR_IN_NANOS = 3_600_000_000_000L;

    private static final Set<AfterSalesStatus> OPEN = EnumSet.of(
            AfterSalesStatus.PENDING_MERCHANT,
            AfterSalesStatus.COUNTER_OFFERED,
            AfterSalesStatus.REJECTED,
            AfterSalesStatus.IN_ARBITRATION
    );

    /**
     * The one kind of actor that fires each trigger (the 5.2 table).
     */
    private static final Map<AfterSalesTrigger, AfterSalesActor> ACTORS = actors();

    /**
     * What each trigger decides from every status, on a case that records nothing but its status (and its deadline in
     * an open status), up to the last instant before the deadline. One row per trigger, then one column per status in
     * {@link AfterSalesStatus} order (PENDING_MERCHANT, COUNTER_OFFERED, REJECTED, IN_ARBITRATION, REFUNDING,
     * REFUNDED, CLOSED): a status name is Apply with that target, "done" is AlreadyDone, "-" is Rejected
     * NOT_FROM_CURRENT_STATE and a guard's name is Rejected GUARD_FAILED by that guard.
     */
    private static final String BEFORE_THE_DEADLINE = """
        MERCHANT_APPROVE       | REFUNDING       | -              | -              | -           | -        | -    | -
        MERCHANT_REJECT        | REJECTED        | -              | -              | -           | -        | -    | -
        MERCHANT_OFFER         | COUNTER_OFFERED | -              | -              | -           | -        | -    | -
        MERCHANT_TIMEOUT       | deadlineDue     | -              | -              | -           | -        | -    | -
        CUSTOMER_ACCEPT_OFFER  | -               | REFUNDING      | -              | -           | -        | -    | -
        CUSTOMER_DECLINE_OFFER | -               | REJECTED       | -              | -           | -        | -    | -
        OFFER_TIMEOUT          | -               | deadlineDue    | -              | -           | -        | -    | -
        CUSTOMER_ESCALATE      | -               | -              | IN_ARBITRATION | done        | -        | -    | -
        CUSTOMER_TIMEOUT       | -               | -              | deadlineDue    | -           | -        | -    | -
        CUSTOMER_WITHDRAW      | CLOSED          | CLOSED         | CLOSED         | CLOSED      | -        | -    | -
        ADMIN_APPROVE_FULL     | -               | -              | -              | REFUNDING   | -        | -    | -
        ADMIN_APPROVE_PARTIAL  | -               | -              | -              | REFUNDING   | -        | -    | -
        ADMIN_REJECT           | -               | -              | -              | CLOSED      | -        | -    | -
        ARBITRATION_TIMEOUT    | -               | -              | -              | deadlineDue | -        | -    | -
        REFUND_SUCCEEDED       | -               | -              | -              | -           | REFUNDED | done | -
        """;

    /**
     * The same from the deadline on: the customer's and the merchant's answers are refused (a hard deadline) and the
     * timeouts fire, while withdrawing and arbitrating are unaffected until the poller moved the case (section 19.1).
     */
    private static final String FROM_THE_DEADLINE_ON = """
        MERCHANT_APPROVE       | beforeDeadline  | -              | -              | -           | -        | -    | -
        MERCHANT_REJECT        | beforeDeadline  | -              | -              | -           | -        | -    | -
        MERCHANT_OFFER         | beforeDeadline  | -              | -              | -           | -        | -    | -
        MERCHANT_TIMEOUT       | REFUNDING       | -              | -              | -           | -        | -    | -
        CUSTOMER_ACCEPT_OFFER  | -               | beforeDeadline | -              | -           | -        | -    | -
        CUSTOMER_DECLINE_OFFER | -               | beforeDeadline | -              | -           | -        | -    | -
        OFFER_TIMEOUT          | -               | REFUNDING      | -              | -           | -        | -    | -
        CUSTOMER_ESCALATE      | -               | -              | beforeDeadline | done        | -        | -    | -
        CUSTOMER_TIMEOUT       | -               | -              | CLOSED         | -           | -        | -    | -
        CUSTOMER_WITHDRAW      | CLOSED          | CLOSED         | CLOSED         | CLOSED      | -        | -    | -
        ADMIN_APPROVE_FULL     | -               | -              | -              | REFUNDING   | -        | -    | -
        ADMIN_APPROVE_PARTIAL  | -               | -              | -              | REFUNDING   | -        | -    | -
        ADMIN_REJECT           | -               | -              | -              | CLOSED      | -        | -    | -
        ARBITRATION_TIMEOUT    | -               | -              | -              | REFUNDING   | -        | -    | -
        REFUND_SUCCEEDED       | -               | -              | -              | -           | REFUNDED | done | -
        """;

    /**
     * The diagram of ADR 0002 section 5.3, as written there.
     */
    private static final String ADR_DIAGRAM = """
            stateDiagram-v2
                [*] --> PENDING_MERCHANT: OPEN (CUSTOMER)
                [*] --> REFUNDING: ORDER_CANCELLED (SYSTEM, paid order cancelled)
                PENDING_MERCHANT --> REFUNDING: MERCHANT_APPROVE / MERCHANT_TIMEOUT
                PENDING_MERCHANT --> COUNTER_OFFERED: MERCHANT_OFFER
                PENDING_MERCHANT --> REJECTED: MERCHANT_REJECT
                COUNTER_OFFERED --> REFUNDING: CUSTOMER_ACCEPT_OFFER / OFFER_TIMEOUT
                COUNTER_OFFERED --> REJECTED: CUSTOMER_DECLINE_OFFER
                REJECTED --> IN_ARBITRATION: CUSTOMER_ESCALATE
                REJECTED --> CLOSED: CUSTOMER_TIMEOUT
                IN_ARBITRATION --> REFUNDING: ADMIN_APPROVE_FULL / ADMIN_APPROVE_PARTIAL / ARBITRATION_TIMEOUT
                IN_ARBITRATION --> CLOSED: ADMIN_REJECT
                PENDING_MERCHANT --> CLOSED: CUSTOMER_WITHDRAW
                COUNTER_OFFERED --> CLOSED: CUSTOMER_WITHDRAW
                REJECTED --> CLOSED: CUSTOMER_WITHDRAW
                IN_ARBITRATION --> CLOSED: CUSTOMER_WITHDRAW
                REFUNDING --> REFUNDED: REFUND_SUCCEEDED
                REFUNDED --> [*]
                CLOSED --> [*]
            """;


    @ParameterizedTest
    @ValueSource(longs = {1, ONE_HOUR_IN_NANOS})
    void everyTrigger_fromEveryStatus_beforeTheDeadline_shouldDecideAsTheTable(long nanosBeforeTheDeadline) {
        LocalDateTime now = DEADLINE.minusNanos(nanosBeforeTheDeadline);

        Assertions.assertEquals(table(BEFORE_THE_DEADLINE), decisionsFromEveryStatus(now));
    }


    @ParameterizedTest
    @ValueSource(longs = {0, 1, ONE_HOUR_IN_NANOS})
    void everyTrigger_fromEveryStatus_fromTheDeadlineOn_shouldDecideAsTheTable(long nanosAfterTheDeadline) {
        LocalDateTime now = DEADLINE.plusNanos(nanosAfterTheDeadline);

        Assertions.assertEquals(table(FROM_THE_DEADLINE_ON), decisionsFromEveryStatus(now));
    }


    @Test
    void theDeclaration_shouldOpenInPendingMerchantOrRefundingAndEndRefundedOrClosed() {
        Assertions.assertEquals("after-sales", MACHINE.name());
        Assertions.assertEquals(
                Set.of(AfterSalesStatus.PENDING_MERCHANT, AfterSalesStatus.REFUNDING), MACHINE.initialStates());
        Assertions.assertEquals(Set.of(AfterSalesStatus.REFUNDED, AfterSalesStatus.CLOSED), MACHINE.terminalStates());
        // One transition per trigger, declared in the enum's order; none of them is internal or a pure no-op.
        Assertions.assertEquals(
                List.of(AfterSalesTrigger.values()),
                MACHINE.transitions().stream().map(Transition::trigger).toList()
        );
        Assertions.assertTrue(MACHINE.transitions().stream().noneMatch(Transition::stays));
        Assertions.assertTrue(MACHINE.transitions().stream().noneMatch(transition -> transition.from().isEmpty()));
    }


    @Test
    void theOpenStatuses_shouldBeTheOnesWithADeadlineAndTheOnesTheCustomerCanWithdrawFrom() {
        Set<AfterSalesStatus> open = EnumSet.noneOf(AfterSalesStatus.class);
        for (AfterSalesStatus status : AfterSalesStatus.values()) {
            if (status.isOpen()) {
                open.add(status);
            }
        }

        Assertions.assertEquals(OPEN, open);
        Assertions.assertEquals(OPEN, MACHINE.transition(AfterSalesTrigger.CUSTOMER_WITHDRAW).from());
        // Each open status has exactly one timeout, which fires from that status alone.
        Set<AfterSalesStatus> timedOut = EnumSet.noneOf(AfterSalesStatus.class);
        for (Transition<AfterSalesStatus, AfterSalesTrigger, AfterSalesActor, AfterSalesTransitionContext> transition
                : MACHINE.transitions()) {
            if (guardNames(transition).contains(AfterSalesStateMachine.DEADLINE_DUE)) {
                Assertions.assertEquals(1, transition.from().size(), transition.trigger().name());
                Assertions.assertTrue(timedOut.addAll(transition.from()), transition.trigger().name());
            }
        }
        Assertions.assertEquals(OPEN, timedOut);
    }


    @Test
    void actors_shouldBeThoseOfTheTable() {
        Assertions.assertEquals(EnumSet.allOf(AfterSalesTrigger.class), ACTORS.keySet());
        for (AfterSalesTrigger trigger : AfterSalesTrigger.values()) {
            Assertions.assertEquals(Set.of(ACTORS.get(trigger)), MACHINE.transition(trigger).actors(), trigger.name());
        }
    }


    @Test
    void decide_byAnyOtherActor_shouldThrow() {
        int refusals = 0;
        for (AfterSalesTrigger trigger : AfterSalesTrigger.values()) {
            for (AfterSalesActor actor : AfterSalesActor.values()) {
                if (actor == ACTORS.get(trigger)) {
                    continue;
                }
                for (AfterSalesStatus status : AfterSalesStatus.values()) {
                    AfterSalesTransitionContext context = new AfterSalesTransitionContext(caseIn(status), DEADLINE);
                    IllegalStateException refused = Assertions.assertThrows(
                            IllegalStateException.class,
                            () -> MACHINE.decide(status, trigger, actor, context),
                            trigger + " by " + actor + " in " + status
                    );
                    Assertions.assertEquals("after-sales: " + actor + " may not fire " + trigger, refused.getMessage());
                    refusals++;
                }
            }
        }
        // 15 triggers with one actor each, three other actors, seven statuses.
        Assertions.assertEquals(15 * 3 * 7, refusals);
    }


    @Test
    void guards_shouldBeTheDeadlineGuardsOfSection19() {
        List<String> beforeDeadline = List.of(AfterSalesStateMachine.BEFORE_DEADLINE);
        List<String> deadlineDue = List.of(AfterSalesStateMachine.DEADLINE_DUE);
        Map<AfterSalesTrigger, List<String>> expected = new EnumMap<>(AfterSalesTrigger.class);
        // The customer's and the merchant's answers have a hard deadline.
        for (AfterSalesTrigger trigger : List.of(
                AfterSalesTrigger.MERCHANT_APPROVE,
                AfterSalesTrigger.MERCHANT_REJECT,
                AfterSalesTrigger.MERCHANT_OFFER,
                AfterSalesTrigger.CUSTOMER_ACCEPT_OFFER,
                AfterSalesTrigger.CUSTOMER_DECLINE_OFFER,
                AfterSalesTrigger.CUSTOMER_ESCALATE
        )) {
            expected.put(trigger, beforeDeadline);
        }
        for (AfterSalesTrigger trigger : List.of(
                AfterSalesTrigger.MERCHANT_TIMEOUT,
                AfterSalesTrigger.OFFER_TIMEOUT,
                AfterSalesTrigger.CUSTOMER_TIMEOUT,
                AfterSalesTrigger.ARBITRATION_TIMEOUT
        )) {
            expected.put(trigger, deadlineDue);
        }
        // Withdrawing, arbitrating and the refund have none.
        for (AfterSalesTrigger trigger : List.of(
                AfterSalesTrigger.CUSTOMER_WITHDRAW,
                AfterSalesTrigger.ADMIN_APPROVE_FULL,
                AfterSalesTrigger.ADMIN_APPROVE_PARTIAL,
                AfterSalesTrigger.ADMIN_REJECT,
                AfterSalesTrigger.REFUND_SUCCEEDED
        )) {
            expected.put(trigger, List.of());
        }

        Assertions.assertEquals(EnumSet.allOf(AfterSalesTrigger.class), expected.keySet());
        for (AfterSalesTrigger trigger : AfterSalesTrigger.values()) {
            Assertions.assertEquals(expected.get(trigger), guardNames(MACHINE.transition(trigger)), trigger.name());
        }
    }


    @Test
    void theTimeoutsAndTheAdminDecisions_shouldDeclareNoAlreadyDone() {
        Set<AfterSalesTrigger> withoutRetryRule = EnumSet.of(
                AfterSalesTrigger.MERCHANT_TIMEOUT,
                AfterSalesTrigger.OFFER_TIMEOUT,
                AfterSalesTrigger.CUSTOMER_TIMEOUT,
                AfterSalesTrigger.ARBITRATION_TIMEOUT,
                AfterSalesTrigger.ADMIN_APPROVE_FULL,
                AfterSalesTrigger.ADMIN_APPROVE_PARTIAL,
                AfterSalesTrigger.ADMIN_REJECT
        );

        for (AfterSalesTrigger trigger : AfterSalesTrigger.values()) {
            Assertions.assertEquals(
                    withoutRetryRule.contains(trigger),
                    MACHINE.transition(trigger).alreadyDone() == null,
                    trigger.name()
            );
        }
    }


    @Test
    void merchantApproveAndReject_shouldBeDoneInEveryLaterStatusOfACaseTheMerchantGaveThatAnswer() {
        // Approved by the merchant, then refunded.
        for (AfterSalesStatus status : List.of(AfterSalesStatus.REFUNDING, AfterSalesStatus.REFUNDED)) {
            AfterSalesCaseEntity approved = caseIn(status, "APPROVED", null, "MERCHANT_APPROVED", null);
            Assertions.assertEquals(
                    "done", decide(approved, AfterSalesTrigger.MERCHANT_APPROVE, DEADLINE), approved.status());
            Assertions.assertEquals(
                    "-", decide(approved, AfterSalesTrigger.MERCHANT_REJECT, DEADLINE), approved.status());
        }
        // Rejected by the merchant, then escalated, then rejected by arbitration.
        for (AfterSalesCaseEntity rejected : List.of(
                caseIn(AfterSalesStatus.REJECTED, "REJECTED", null, null, null),
                caseIn(AfterSalesStatus.IN_ARBITRATION, "REJECTED", null, null, null),
                caseIn(AfterSalesStatus.CLOSED, "REJECTED", null, null, "ARBITRATION_REJECTED")
        )) {
            // A repeat answers unchanged even after the deadline.
            LocalDateTime late = DEADLINE.plusDays(1);
            Assertions.assertEquals(
                    "done", decide(rejected, AfterSalesTrigger.MERCHANT_REJECT, late), rejected.status());
            Assertions.assertEquals(
                    "-", decide(rejected, AfterSalesTrigger.MERCHANT_APPROVE, DEADLINE), rejected.status());
        }
    }


    @ParameterizedTest
    @EnumSource(value = AfterSalesTrigger.class, names = {"MERCHANT_APPROVE", "MERCHANT_REJECT", "MERCHANT_OFFER"})
    void aMerchantAnswer_afterTheMerchantTimeoutApprovedTheCase_shouldBeRejectedByTheStatus(AfterSalesTrigger trigger) {
        // The timeout records no merchant decision: the merchant's late answer is a 409, not a repeat.
        AfterSalesCaseEntity timedOut = caseIn(AfterSalesStatus.REFUNDING, null, null, "MERCHANT_TIMEOUT", null);

        Assertions.assertEquals(
                new Decision.Rejected<>(
                        trigger, AfterSalesStatus.REFUNDING, Decision.Reason.NOT_FROM_CURRENT_STATE, null),
                MACHINE.decide(
                        AfterSalesStatus.REFUNDING,
                        trigger,
                        AfterSalesActor.MERCHANT,
                        new AfterSalesTransitionContext(timedOut, DEADLINE, Money.of("12.00", "USD"))
                )
        );
    }


    @Test
    void merchantOffer_shouldOnlyBeDoneWithTheSameAmountComparedByValue() {
        // A scale the column would not have, so that BigDecimal.equals would tell it from 12.00.
        AfterSalesCaseEntity offered = caseIn(
                AfterSalesStatus.COUNTER_OFFERED, "OFFERED", new BigDecimal("12.0"), null, null);
        AfterSalesCaseEntity accepted = caseIn(
                AfterSalesStatus.REFUNDING, "OFFERED", new BigDecimal("12.00"), "OFFER_ACCEPTED", null);
        AfterSalesCaseEntity rejected = caseIn(AfterSalesStatus.REJECTED, "REJECTED", null, null, null);
        // Not possible in the table (an offer always has its amount), but no offer to repeat either.
        AfterSalesCaseEntity offeredWithoutAmount = caseIn(
                AfterSalesStatus.COUNTER_OFFERED, "OFFERED", null, null, null);
        LocalDateTime inTime = DEADLINE.minusDays(1);
        Money twelve = Money.of("12.00", "USD");

        Assertions.assertEquals("done", decideOffer(offered, twelve, DEADLINE));
        Assertions.assertEquals("done", decideOffer(accepted, twelve, DEADLINE));
        Assertions.assertEquals("-", decideOffer(offered, Money.of("12.01", "USD"), inTime));
        Assertions.assertEquals("-", decideOffer(offered, null, inTime));
        Assertions.assertEquals("-", decideOffer(rejected, twelve, DEADLINE));
        Assertions.assertEquals("-", decideOffer(offeredWithoutAmount, twelve, inTime));
        Assertions.assertEquals(
                "COUNTER_OFFERED", decideOffer(caseIn(AfterSalesStatus.PENDING_MERCHANT), twelve, inTime));
    }


    @Test
    void merchantOffer_inAnotherCurrency_shouldThrow() {
        AfterSalesCaseEntity offered = caseIn(
                AfterSalesStatus.COUNTER_OFFERED, "OFFERED", new BigDecimal("12.00"), null, null);

        Assertions.assertThrows(
                CurrencyMismatchException.class,
                () -> decideOffer(offered, Money.of("12.00", "EUR"), DEADLINE)
        );
    }


    @Test
    void customerAcceptOffer_shouldBeDoneOnlyWhenTheCustomerAcceptedIt() {
        for (AfterSalesStatus status : List.of(AfterSalesStatus.REFUNDING, AfterSalesStatus.REFUNDED)) {
            AfterSalesCaseEntity accepted = caseIn(status, "OFFERED", new BigDecimal("12.00"), "OFFER_ACCEPTED", null);
            AfterSalesCaseEntity timedOut = caseIn(status, "OFFERED", new BigDecimal("12.00"), "OFFER_TIMEOUT", null);

            Assertions.assertEquals("done", decide(accepted, AfterSalesTrigger.CUSTOMER_ACCEPT_OFFER, DEADLINE));
            // The offer timeout got there first: the customer's accept is a 409.
            Assertions.assertEquals("-", decide(timedOut, AfterSalesTrigger.CUSTOMER_ACCEPT_OFFER, DEADLINE));
        }
    }


    @Test
    void customerDeclineOffer_shouldBeDoneOnlyWhileTheDeclinedOfferIsRejected() {
        AfterSalesCaseEntity declined = caseIn(AfterSalesStatus.REJECTED, "OFFERED", new BigDecimal("12.00"), null,
                null);
        AfterSalesCaseEntity merchantRejected = caseIn(AfterSalesStatus.REJECTED, "REJECTED", null, null, null);
        AfterSalesCaseEntity escalated = caseIn(AfterSalesStatus.IN_ARBITRATION, "OFFERED", new BigDecimal("12.00"),
                null, null);

        Assertions.assertEquals("done", decide(declined, AfterSalesTrigger.CUSTOMER_DECLINE_OFFER, DEADLINE));
        Assertions.assertEquals("-", decide(merchantRejected, AfterSalesTrigger.CUSTOMER_DECLINE_OFFER, DEADLINE));
        Assertions.assertEquals("-", decide(escalated, AfterSalesTrigger.CUSTOMER_DECLINE_OFFER, DEADLINE));
    }


    @Test
    void customerWithdraw_shouldBeDoneOnlyForAWithdrawnCase() {
        AfterSalesCaseEntity withdrawn = caseIn(AfterSalesStatus.CLOSED, null, null, null, "WITHDRAWN");

        Assertions.assertEquals("done", decide(withdrawn, AfterSalesTrigger.CUSTOMER_WITHDRAW, DEADLINE));
        for (String otherReason : List.of("CUSTOMER_TIMEOUT", "ARBITRATION_REJECTED")) {
            AfterSalesCaseEntity closed = caseIn(AfterSalesStatus.CLOSED, "REJECTED", null, null, otherReason);
            Assertions.assertEquals("-", decide(closed, AfterSalesTrigger.CUSTOMER_WITHDRAW, DEADLINE), otherReason);
        }
        // A case the merchant approved meanwhile can no longer be withdrawn.
        Assertions.assertEquals(
                "-",
                decide(caseIn(AfterSalesStatus.REFUNDING, "APPROVED", null, "MERCHANT_APPROVED", null),
                        AfterSalesTrigger.CUSTOMER_WITHDRAW, DEADLINE)
        );
    }


    @Test
    void timeoutTrigger_shouldNameTheTimeoutOfEachOpenStatus() {
        Assertions.assertEquals(
                AfterSalesTrigger.MERCHANT_TIMEOUT,
                AfterSalesStateMachine.timeoutTrigger(AfterSalesStatus.PENDING_MERCHANT)
        );
        Assertions.assertEquals(
                AfterSalesTrigger.OFFER_TIMEOUT,
                AfterSalesStateMachine.timeoutTrigger(AfterSalesStatus.COUNTER_OFFERED)
        );
        Assertions.assertEquals(
                AfterSalesTrigger.CUSTOMER_TIMEOUT,
                AfterSalesStateMachine.timeoutTrigger(AfterSalesStatus.REJECTED)
        );
        Assertions.assertEquals(
                AfterSalesTrigger.ARBITRATION_TIMEOUT,
                AfterSalesStateMachine.timeoutTrigger(AfterSalesStatus.IN_ARBITRATION)
        );
    }


    @ParameterizedTest
    @EnumSource(value = AfterSalesStatus.class, names = {"REFUNDING", "REFUNDED", "CLOSED"})
    void timeoutTrigger_forAStatusWithoutADeadline_shouldThrow(AfterSalesStatus status) {
        IllegalArgumentException refused = Assertions.assertThrows(
                IllegalArgumentException.class, () -> AfterSalesStateMachine.timeoutTrigger(status));

        Assertions.assertEquals("No deadline runs out on an after-sales case in " + status, refused.getMessage());
    }


    @Test
    void verbs_shouldBeThoseOfTheConflictMessages() {
        Map<AfterSalesTrigger, String> expected = new EnumMap<>(AfterSalesTrigger.class);
        expected.put(AfterSalesTrigger.MERCHANT_APPROVE, "approved");
        expected.put(AfterSalesTrigger.MERCHANT_REJECT, "rejected");
        expected.put(AfterSalesTrigger.MERCHANT_OFFER, "given a counter-offer");
        expected.put(AfterSalesTrigger.MERCHANT_TIMEOUT, "timed out");
        expected.put(AfterSalesTrigger.CUSTOMER_ACCEPT_OFFER, "accepted");
        expected.put(AfterSalesTrigger.CUSTOMER_DECLINE_OFFER, "declined");
        expected.put(AfterSalesTrigger.OFFER_TIMEOUT, "timed out");
        expected.put(AfterSalesTrigger.CUSTOMER_ESCALATE, "escalated");
        expected.put(AfterSalesTrigger.CUSTOMER_TIMEOUT, "timed out");
        expected.put(AfterSalesTrigger.CUSTOMER_WITHDRAW, "withdrawn");
        expected.put(AfterSalesTrigger.ADMIN_APPROVE_FULL, "arbitrated");
        expected.put(AfterSalesTrigger.ADMIN_APPROVE_PARTIAL, "arbitrated");
        expected.put(AfterSalesTrigger.ADMIN_REJECT, "arbitrated");
        expected.put(AfterSalesTrigger.ARBITRATION_TIMEOUT, "timed out");
        expected.put(AfterSalesTrigger.REFUND_SUCCEEDED, "marked refunded");

        Assertions.assertEquals(EnumSet.allOf(AfterSalesTrigger.class), expected.keySet());
        for (AfterSalesTrigger trigger : AfterSalesTrigger.values()) {
            Assertions.assertEquals(expected.get(trigger), trigger.verb(), trigger.name());
        }
    }


    @Test
    void statusConflictMessage_shouldNameTheStatusesTheTriggerFiresFrom() {
        Assertions.assertEquals(
                "After-sales case is REFUNDING; only PENDING_MERCHANT cases can be approved",
                AfterSalesStateMachine.statusConflictMessage(
                        AfterSalesTrigger.MERCHANT_APPROVE, AfterSalesStatus.REFUNDING)
        );
        Assertions.assertEquals(
                "After-sales case is PENDING_MERCHANT; only COUNTER_OFFERED cases can be declined",
                AfterSalesStateMachine.statusConflictMessage(
                        AfterSalesTrigger.CUSTOMER_DECLINE_OFFER, AfterSalesStatus.PENDING_MERCHANT)
        );
        Assertions.assertEquals(
                "After-sales case is REFUNDED; only PENDING_MERCHANT or COUNTER_OFFERED or REJECTED or IN_ARBITRATION "
                        + "cases can be withdrawn",
                AfterSalesStateMachine.statusConflictMessage(
                        AfterSalesTrigger.CUSTOMER_WITHDRAW, AfterSalesStatus.REFUNDED)
        );
        Assertions.assertEquals(
                "After-sales case is CLOSED; only IN_ARBITRATION cases can be arbitrated",
                AfterSalesStateMachine.statusConflictMessage(AfterSalesTrigger.ADMIN_REJECT, AfterSalesStatus.CLOSED)
        );
    }


    @Test
    void transitionContext_shouldRequireTheCaseAndTheTime() {
        AfterSalesCaseEntity open = caseIn(AfterSalesStatus.PENDING_MERCHANT);

        Assertions.assertThrows(NullPointerException.class, () -> new AfterSalesTransitionContext(null, DEADLINE));
        Assertions.assertThrows(NullPointerException.class, () -> new AfterSalesTransitionContext(open, null));
        Assertions.assertNull(new AfterSalesTransitionContext(open, DEADLINE).amount());
        Assertions.assertEquals(
                Money.of("3.50", "USD"),
                new AfterSalesTransitionContext(open, DEADLINE, Money.of("3.50", "USD")).amount()
        );
    }


    @Test
    void transitionContext_deadlineGuards_shouldSwitchExactlyAtTheDeadline() {
        AfterSalesCaseEntity open = caseIn(AfterSalesStatus.PENDING_MERCHANT);
        AfterSalesTransitionContext justBefore = new AfterSalesTransitionContext(open, DEADLINE.minusNanos(1));
        AfterSalesTransitionContext atTheDeadline = new AfterSalesTransitionContext(open, DEADLINE);

        Assertions.assertTrue(justBefore.beforeDeadline());
        Assertions.assertFalse(justBefore.deadlineDue());
        Assertions.assertFalse(atTheDeadline.beforeDeadline());
        Assertions.assertTrue(atTheDeadline.deadlineDue());
    }


    @Test
    void transitionContext_ofACaseWithoutADeadline_shouldRefuseTheDeadlineGuards() {
        AfterSalesTransitionContext refunding = new AfterSalesTransitionContext(
                caseIn(AfterSalesStatus.REFUNDING), DEADLINE);

        NullPointerException refused = Assertions.assertThrows(NullPointerException.class, refunding::beforeDeadline);
        Assertions.assertEquals("An open after-sales case has a deadline", refused.getMessage());
        Assertions.assertThrows(NullPointerException.class, refunding::deadlineDue);
    }


    @Test
    void transitionContext_shouldReadTheRecordedOutcomes() {
        AfterSalesTransitionContext context = new AfterSalesTransitionContext(
                caseIn(AfterSalesStatus.CLOSED, "REJECTED", null, null, "CUSTOMER_TIMEOUT"), DEADLINE);
        AfterSalesTransitionContext refunded = new AfterSalesTransitionContext(
                caseIn(AfterSalesStatus.REFUNDED, null, null, "ARBITRATION_TIMEOUT", null), DEADLINE);

        Assertions.assertTrue(context.merchantDecided(AfterSalesMerchantDecision.REJECTED));
        Assertions.assertFalse(context.merchantDecided(AfterSalesMerchantDecision.APPROVED));
        Assertions.assertTrue(context.closedAs(AfterSalesCloseReason.CUSTOMER_TIMEOUT));
        Assertions.assertFalse(context.closedAs(AfterSalesCloseReason.WITHDRAWN));
        Assertions.assertFalse(context.resolvedAs(AfterSalesResolution.ARBITRATION_TIMEOUT));
        Assertions.assertTrue(refunded.resolvedAs(AfterSalesResolution.ARBITRATION_TIMEOUT));
        Assertions.assertFalse(refunded.merchantDecided(AfterSalesMerchantDecision.REJECTED));
        Assertions.assertFalse(refunded.closedAs(AfterSalesCloseReason.CUSTOMER_TIMEOUT));
    }


    @Test
    void toMermaid_shouldDrawTheAfterSalesLifecycle() {
        Assertions.assertEquals("""
                stateDiagram-v2
                    [*] --> PENDING_MERCHANT
                    [*] --> REFUNDING
                    PENDING_MERCHANT --> REFUNDING: MERCHANT_APPROVE
                    PENDING_MERCHANT --> REJECTED: MERCHANT_REJECT
                    PENDING_MERCHANT --> COUNTER_OFFERED: MERCHANT_OFFER
                    PENDING_MERCHANT --> REFUNDING: MERCHANT_TIMEOUT
                    COUNTER_OFFERED --> REFUNDING: CUSTOMER_ACCEPT_OFFER
                    COUNTER_OFFERED --> REJECTED: CUSTOMER_DECLINE_OFFER
                    COUNTER_OFFERED --> REFUNDING: OFFER_TIMEOUT
                    REJECTED --> IN_ARBITRATION: CUSTOMER_ESCALATE
                    REJECTED --> CLOSED: CUSTOMER_TIMEOUT
                    PENDING_MERCHANT --> CLOSED: CUSTOMER_WITHDRAW
                    COUNTER_OFFERED --> CLOSED: CUSTOMER_WITHDRAW
                    REJECTED --> CLOSED: CUSTOMER_WITHDRAW
                    IN_ARBITRATION --> CLOSED: CUSTOMER_WITHDRAW
                    IN_ARBITRATION --> REFUNDING: ADMIN_APPROVE_FULL
                    IN_ARBITRATION --> REFUNDING: ADMIN_APPROVE_PARTIAL
                    IN_ARBITRATION --> CLOSED: ADMIN_REJECT
                    IN_ARBITRATION --> REFUNDING: ARBITRATION_TIMEOUT
                    REFUNDING --> REFUNDED: REFUND_SUCCEEDED
                    REFUNDED --> [*]
                    CLOSED --> [*]
                """, MACHINE.toMermaid());
    }


    @Test
    void toMermaid_shouldHaveTheEdgesOfTheDiagramInSection53() {
        // The ADR draws one line per pair of statuses with its triggers joined by " / " and labels the initial
        // statuses with the opening; the machine draws one line per trigger and from-status.
        Assertions.assertEquals(edges(ADR_DIAGRAM), edges(MACHINE.toMermaid()));
        Assertions.assertEquals(endpoints(ADR_DIAGRAM), endpoints(MACHINE.toMermaid()));
        Assertions.assertEquals(
                Set.of("[*] --> PENDING_MERCHANT", "[*] --> REFUNDING", "REFUNDED --> [*]", "CLOSED --> [*]"),
                endpoints(MACHINE.toMermaid())
        );
    }


    /**
     * What every trigger decides from every status at {@code now}, by the trigger's own actor, on a case that records
     * nothing but its status: one row per trigger in the shape of {@link #BEFORE_THE_DEADLINE}.
     */
    private static Map<AfterSalesTrigger, List<String>> decisionsFromEveryStatus(LocalDateTime now) {
        Map<AfterSalesTrigger, List<String>> rows = new EnumMap<>(AfterSalesTrigger.class);
        for (AfterSalesTrigger trigger : AfterSalesTrigger.values()) {
            List<String> row = new ArrayList<>();
            for (AfterSalesStatus status : AfterSalesStatus.values()) {
                // The amount only matters to MERCHANT_OFFER; a case that records nothing has offered nothing.
                AfterSalesTransitionContext context = new AfterSalesTransitionContext(
                        caseIn(status), now, Money.of("12.00", "USD"));
                row.add(decide(status, trigger, context));
            }
            rows.put(trigger, row);
        }
        return rows;
    }


    private static String decide(AfterSalesCaseEntity current, AfterSalesTrigger trigger, LocalDateTime now) {
        return decide(
                AfterSalesStatus.valueOf(current.status()), trigger, new AfterSalesTransitionContext(current, now));
    }


    private static String decideOffer(AfterSalesCaseEntity current, Money amount, LocalDateTime now) {
        return decide(
                AfterSalesStatus.valueOf(current.status()),
                AfterSalesTrigger.MERCHANT_OFFER,
                new AfterSalesTransitionContext(current, now, amount)
        );
    }


    /**
     * The decision as a table cell: the target status, "done", "-" or the name of the guard that failed.
     */
    private static String decide(
            AfterSalesStatus status,
            AfterSalesTrigger trigger,
            AfterSalesTransitionContext context
    ) {
        Decision<AfterSalesStatus, AfterSalesTrigger> decision =
                MACHINE.decide(status, trigger, ACTORS.get(trigger), context);
        Assertions.assertEquals(trigger, decision.trigger());
        if (decision instanceof Decision.Apply<AfterSalesStatus, AfterSalesTrigger> apply) {
            Assertions.assertEquals(status, apply.from());
            return apply.to().name();
        }
        if (decision instanceof Decision.AlreadyDone<AfterSalesStatus, AfterSalesTrigger> done) {
            Assertions.assertEquals(status, done.current());
            return "done";
        }
        if (decision instanceof Decision.Rejected<AfterSalesStatus, AfterSalesTrigger> rejected) {
            Assertions.assertEquals(status, rejected.current());
            return rejected.reason() == Decision.Reason.NOT_FROM_CURRENT_STATE ? "-" : rejected.guard();
        }
        throw new AssertionError("Unknown decision " + decision);
    }


    /**
     * A decision table: one row per trigger, the cells of each row in {@link AfterSalesStatus} order.
     */
    private static Map<AfterSalesTrigger, List<String>> table(String text) {
        Map<AfterSalesTrigger, List<String>> rows = new EnumMap<>(AfterSalesTrigger.class);
        for (String line : text.strip().split("\n")) {
            List<String> cells = Arrays.stream(line.split("\\|")).map(String::strip).toList();
            Assertions.assertEquals(1 + AfterSalesStatus.values().length, cells.size(), line);
            Assertions.assertNull(
                    rows.put(AfterSalesTrigger.valueOf(cells.get(0)), cells.subList(1, cells.size())), line);
        }
        Assertions.assertEquals(EnumSet.allOf(AfterSalesTrigger.class), rows.keySet());
        return rows;
    }


    /**
     * A case in {@code status} that records nothing else: no merchant decision, offer, resolution or close reason. It
     * has a deadline in an open status, as every open case has, and the requested amount approved in REFUNDING and
     * REFUNDED.
     */
    private static AfterSalesCaseEntity caseIn(AfterSalesStatus status) {
        return caseIn(status, null, null, null, null);
    }


    private static AfterSalesCaseEntity caseIn(
            AfterSalesStatus status,
            String merchantDecision,
            BigDecimal offeredAmount,
            String resolution,
            String closeReason
    ) {
        boolean refunding = status == AfterSalesStatus.REFUNDING || status == AfterSalesStatus.REFUNDED;
        BigDecimal requested = new BigDecimal("20.00");
        return new AfterSalesCaseEntity(
                7L,
                11L,
                13L,
                17L,
                "CUSTOMER_REQUEST",
                1,
                status.name(),
                "MISSING_ITEM",
                "The fries were missing",
                new BigDecimal("25.00"),
                requested,
                offeredAmount,
                refunding ? requested : null,
                status == AfterSalesStatus.REFUNDED ? requested : null,
                "USD",
                merchantDecision,
                null,
                resolution,
                closeReason,
                null,
                status.isOpen() ? DEADLINE : null,
                null,
                0,
                3L,
                DEADLINE.minusDays(1),
                DEADLINE.minusHours(1)
        );
    }


    private static Map<AfterSalesTrigger, AfterSalesActor> actors() {
        Map<AfterSalesTrigger, AfterSalesActor> actors = new EnumMap<>(AfterSalesTrigger.class);
        actors.put(AfterSalesTrigger.MERCHANT_APPROVE, AfterSalesActor.MERCHANT);
        actors.put(AfterSalesTrigger.MERCHANT_REJECT, AfterSalesActor.MERCHANT);
        actors.put(AfterSalesTrigger.MERCHANT_OFFER, AfterSalesActor.MERCHANT);
        actors.put(AfterSalesTrigger.MERCHANT_TIMEOUT, AfterSalesActor.SYSTEM);
        actors.put(AfterSalesTrigger.CUSTOMER_ACCEPT_OFFER, AfterSalesActor.CUSTOMER);
        actors.put(AfterSalesTrigger.CUSTOMER_DECLINE_OFFER, AfterSalesActor.CUSTOMER);
        actors.put(AfterSalesTrigger.OFFER_TIMEOUT, AfterSalesActor.SYSTEM);
        actors.put(AfterSalesTrigger.CUSTOMER_ESCALATE, AfterSalesActor.CUSTOMER);
        actors.put(AfterSalesTrigger.CUSTOMER_TIMEOUT, AfterSalesActor.SYSTEM);
        actors.put(AfterSalesTrigger.CUSTOMER_WITHDRAW, AfterSalesActor.CUSTOMER);
        actors.put(AfterSalesTrigger.ADMIN_APPROVE_FULL, AfterSalesActor.ADMIN);
        actors.put(AfterSalesTrigger.ADMIN_APPROVE_PARTIAL, AfterSalesActor.ADMIN);
        actors.put(AfterSalesTrigger.ADMIN_REJECT, AfterSalesActor.ADMIN);
        actors.put(AfterSalesTrigger.ARBITRATION_TIMEOUT, AfterSalesActor.SYSTEM);
        actors.put(AfterSalesTrigger.REFUND_SUCCEEDED, AfterSalesActor.SYSTEM);
        return actors;
    }


    private static List<String> guardNames(
            Transition<AfterSalesStatus, AfterSalesTrigger, AfterSalesActor, AfterSalesTransitionContext> transition
    ) {
        return transition.guards().stream().map(Guard::name).toList();
    }


    /**
     * The "FROM --> TO: TRIGGER" edges between statuses of a diagram, with a label of several triggers ("A / B")
     * split into one edge per trigger.
     */
    private static Set<String> edges(String diagram) {
        Set<String> edges = new LinkedHashSet<>();
        for (String line : diagramLines(diagram)) {
            if (line.contains("[*]")) {
                continue;
            }
            String[] edgeAndLabel = line.split(": ", 2);
            for (String trigger : edgeAndLabel[1].split(" / ")) {
                Assertions.assertTrue(edges.add(edgeAndLabel[0] + ": " + trigger), line);
            }
        }
        return edges.stream().sorted().collect(Collectors.toCollection(LinkedHashSet::new));
    }


    /**
     * The "[*] --> X" and "X --> [*]" lines of a diagram, without their labels.
     */
    private static Set<String> endpoints(String diagram) {
        Set<String> endpoints = new LinkedHashSet<>();
        for (String line : diagramLines(diagram)) {
            if (line.contains("[*]")) {
                endpoints.add(line.split(": ", 2)[0]);
            }
        }
        return endpoints;
    }


    private static List<String> diagramLines(String diagram) {
        List<String> lines = new ArrayList<>();
        for (String line : diagram.strip().split("\n")) {
            if (!line.strip().equals("stateDiagram-v2")) {
                lines.add(line.strip());
            }
        }
        return lines;
    }
}
