package com.laioffer.onlineorder.aftersales;


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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * The refund declaration of ADR 0002 section 5.4 with the attempt cap of section 19.9: what every step decides from
 * every status for each combination of lease and attempts, each step's actors, guards and retry rule, and the steps
 * the refund executor takes when a refund runs out of attempts.
 */
class RefundStateMachineTests {

    private static final StateMachine<RefundStatus, RefundTrigger, AfterSalesActor, RefundTransitionContext> MACHINE =
            RefundStateMachine.DEFINITION;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 12, 0);

    private static final int MAX_ATTEMPTS = 8;

    /**
     * The one kind of actor that fires each step.
     */
    private static final Map<RefundTrigger, AfterSalesActor> ACTORS = Map.of(
            RefundTrigger.CLAIM, AfterSalesActor.SYSTEM,
            RefundTrigger.RECLAIM, AfterSalesActor.SYSTEM,
            RefundTrigger.SUCCEED, AfterSalesActor.SYSTEM,
            RefundTrigger.RETRY_LATER, AfterSalesActor.SYSTEM,
            RefundTrigger.GIVE_UP, AfterSalesActor.SYSTEM,
            RefundTrigger.ADMIN_RETRY, AfterSalesActor.ADMIN
    );


    @Test
    void everyStep_fromEveryStatus_withAnExpiredLeaseAndAttemptsLeft_shouldDecideAsTheTable() {
        Map<RefundTrigger, List<String>> expected = new EnumMap<>(RefundTrigger.class);
        // Columns: PENDING, PROCESSING, SUCCEEDED, FAILED.
        expected.put(RefundTrigger.CLAIM, List.of("PROCESSING", "-", "-", "-"));
        expected.put(RefundTrigger.RECLAIM, List.of("-", "PROCESSING", "-", "-"));
        expected.put(RefundTrigger.SUCCEED, List.of("-", "SUCCEEDED", "done", "-"));
        expected.put(RefundTrigger.RETRY_LATER, List.of("-", "PENDING", "-", "-"));
        expected.put(RefundTrigger.GIVE_UP, List.of("-", "FAILED", "-", "-"));
        expected.put(RefundTrigger.ADMIN_RETRY, List.of("done", "-", "-", "PENDING"));

        Assertions.assertEquals(expected, decisionsFromEveryStatus(MAX_ATTEMPTS - 1, NOW));
    }


    @Test
    void everyStep_fromEveryStatus_whileTheLeaseIsHeld_shouldOnlyRefuseTheReclaim() {
        Map<RefundTrigger, List<String>> expected = new EnumMap<>(RefundTrigger.class);
        expected.put(RefundTrigger.CLAIM, List.of("PROCESSING", "-", "-", "-"));
        expected.put(RefundTrigger.RECLAIM, List.of("-", RefundStateMachine.LEASE_EXPIRED, "-", "-"));
        expected.put(RefundTrigger.SUCCEED, List.of("-", "SUCCEEDED", "done", "-"));
        expected.put(RefundTrigger.RETRY_LATER, List.of("-", "PENDING", "-", "-"));
        expected.put(RefundTrigger.GIVE_UP, List.of("-", "FAILED", "-", "-"));
        expected.put(RefundTrigger.ADMIN_RETRY, List.of("done", "-", "-", "PENDING"));

        Assertions.assertEquals(expected, decisionsFromEveryStatus(MAX_ATTEMPTS - 1, NOW.plusNanos(1)));
    }


    @Test
    void everyStep_fromEveryStatus_withTheAttemptsUsedUp_shouldOnlyLetTheRefundSucceedOrBeGivenUp() {
        Map<RefundTrigger, List<String>> expected = new EnumMap<>(RefundTrigger.class);
        expected.put(RefundTrigger.CLAIM, List.of("PROCESSING", "-", "-", "-"));
        expected.put(RefundTrigger.RECLAIM, List.of("-", RefundStateMachine.ATTEMPTS_LEFT, "-", "-"));
        expected.put(RefundTrigger.SUCCEED, List.of("-", "SUCCEEDED", "done", "-"));
        expected.put(RefundTrigger.RETRY_LATER, List.of("-", RefundStateMachine.ATTEMPTS_LEFT, "-", "-"));
        expected.put(RefundTrigger.GIVE_UP, List.of("-", "FAILED", "-", "-"));
        expected.put(RefundTrigger.ADMIN_RETRY, List.of("done", "-", "-", "PENDING"));

        Assertions.assertEquals(expected, decisionsFromEveryStatus(MAX_ATTEMPTS, NOW));
        // A held lease is checked first.
        expected.put(RefundTrigger.RECLAIM, List.of("-", RefundStateMachine.LEASE_EXPIRED, "-", "-"));
        Assertions.assertEquals(expected, decisionsFromEveryStatus(MAX_ATTEMPTS, NOW.plusNanos(1)));
    }


    @Test
    void anExpiredLease_withTheAttemptsUsedUp_shouldBeGivenUpInsteadOfReclaimed() {
        // What the executor does with a refund its claim query returned (section 19.9).
        RefundTransitionContext context = new RefundTransitionContext(
                refundIn(RefundStatus.PROCESSING, MAX_ATTEMPTS, NOW.minusSeconds(1)), NOW, MAX_ATTEMPTS);

        Assertions.assertEquals(
                new Decision.Rejected<>(RefundTrigger.RECLAIM, RefundStatus.PROCESSING, Decision.Reason.GUARD_FAILED,
                        RefundStateMachine.ATTEMPTS_LEFT),
                MACHINE.decide(RefundStatus.PROCESSING, RefundTrigger.RECLAIM, AfterSalesActor.SYSTEM, context)
        );
        Assertions.assertEquals(
                new Decision.Apply<>(RefundTrigger.GIVE_UP, RefundStatus.PROCESSING, RefundStatus.FAILED),
                MACHINE.decide(RefundStatus.PROCESSING, RefundTrigger.GIVE_UP, AfterSalesActor.SYSTEM, context)
        );
    }


    @Test
    void theDeclaration_shouldStartPendingAndOnlyEndSucceeded() {
        Assertions.assertEquals("refund", MACHINE.name());
        Assertions.assertEquals(Set.of(RefundStatus.PENDING), MACHINE.initialStates());
        // FAILED is not final: an admin retry moves it back to PENDING.
        Assertions.assertEquals(Set.of(RefundStatus.SUCCEEDED), MACHINE.terminalStates());
        Assertions.assertEquals(Set.of(RefundStatus.PENDING), MACHINE.targets(RefundStatus.FAILED));
        Assertions.assertEquals(
                Set.of(RefundStatus.SUCCEEDED, RefundStatus.PENDING, RefundStatus.FAILED),
                MACHINE.targets(RefundStatus.PROCESSING)
        );
        Assertions.assertEquals(
                List.of(RefundTrigger.values()),
                MACHINE.transitions().stream().map(Transition::trigger).toList()
        );
        for (RefundTrigger trigger : RefundTrigger.values()) {
            Assertions.assertEquals(
                    trigger == RefundTrigger.RECLAIM, MACHINE.transition(trigger).stays(), trigger.name());
        }
    }


    @Test
    void guardsAndRetryRules_shouldBeThoseOfTheTable() {
        Map<RefundTrigger, List<String>> guards = new EnumMap<>(RefundTrigger.class);
        guards.put(RefundTrigger.CLAIM, List.of());
        guards.put(
                RefundTrigger.RECLAIM, List.of(RefundStateMachine.LEASE_EXPIRED, RefundStateMachine.ATTEMPTS_LEFT));
        guards.put(RefundTrigger.SUCCEED, List.of());
        guards.put(RefundTrigger.RETRY_LATER, List.of(RefundStateMachine.ATTEMPTS_LEFT));
        guards.put(RefundTrigger.GIVE_UP, List.of());
        guards.put(RefundTrigger.ADMIN_RETRY, List.of());
        Set<RefundTrigger> withRetryRule = EnumSet.of(RefundTrigger.SUCCEED, RefundTrigger.ADMIN_RETRY);

        for (RefundTrigger trigger : RefundTrigger.values()) {
            Transition<RefundStatus, RefundTrigger, AfterSalesActor, RefundTransitionContext> transition =
                    MACHINE.transition(trigger);
            Assertions.assertEquals(
                    guards.get(trigger), transition.guards().stream().map(Guard::name).toList(), trigger.name());
            Assertions.assertEquals(withRetryRule.contains(trigger), transition.alreadyDone() != null, trigger.name());
        }
    }


    @Test
    void actors_shouldBeThoseOfTheTable() {
        Assertions.assertEquals(EnumSet.allOf(RefundTrigger.class), ACTORS.keySet());
        for (RefundTrigger trigger : RefundTrigger.values()) {
            Assertions.assertEquals(Set.of(ACTORS.get(trigger)), MACHINE.transition(trigger).actors(), trigger.name());
        }
    }


    @Test
    void decide_byAnyOtherActor_shouldThrow() {
        int refusals = 0;
        for (RefundTrigger trigger : RefundTrigger.values()) {
            for (AfterSalesActor actor : AfterSalesActor.values()) {
                if (actor == ACTORS.get(trigger)) {
                    continue;
                }
                for (RefundStatus status : RefundStatus.values()) {
                    RefundTransitionContext context = contextFor(status, 1, NOW);
                    IllegalStateException refused = Assertions.assertThrows(
                            IllegalStateException.class,
                            () -> MACHINE.decide(status, trigger, actor, context),
                            trigger + " by " + actor + " in " + status
                    );
                    Assertions.assertEquals("refund: " + actor + " may not fire " + trigger, refused.getMessage());
                    refusals++;
                }
            }
        }
        // Six steps with one actor each, three other actors, four statuses.
        Assertions.assertEquals(6 * 3 * 4, refusals);
    }


    @ParameterizedTest
    @EnumSource(value = RefundTrigger.class, names = {"CLAIM", "SUCCEED", "GIVE_UP", "ADMIN_RETRY"})
    void theStepsWithoutGuards_shouldDecideWithoutAContext(RefundTrigger trigger) {
        for (RefundStatus status : RefundStatus.values()) {
            Assertions.assertEquals(
                    decide(status, trigger, contextFor(status, 1, NOW)),
                    decide(status, trigger, null),
                    trigger + " in " + status
            );
        }
    }


    @Test
    void leaseExpired_shouldBeTrueFromTheEndOfTheLeaseOn() {
        Assertions.assertFalse(contextFor(RefundStatus.PROCESSING, 1, NOW.plusNanos(1)).leaseExpired());
        Assertions.assertTrue(contextFor(RefundStatus.PROCESSING, 1, NOW).leaseExpired());
        Assertions.assertTrue(contextFor(RefundStatus.PROCESSING, 1, NOW.minusNanos(1)).leaseExpired());
    }


    @Test
    void leaseExpired_ofARefundWithoutALease_shouldThrow() {
        RefundTransitionContext pending = new RefundTransitionContext(
                refundIn(RefundStatus.PENDING, 0, null), NOW, MAX_ATTEMPTS);

        NullPointerException refused = Assertions.assertThrows(NullPointerException.class, pending::leaseExpired);
        Assertions.assertEquals("A PROCESSING refund has a lease", refused.getMessage());
    }


    @ParameterizedTest
    @ValueSource(ints = {0, 1, MAX_ATTEMPTS - 1})
    void attemptsLeft_belowTheMaximum_shouldBeTrue(int attempts) {
        Assertions.assertTrue(contextFor(RefundStatus.PROCESSING, attempts, NOW).attemptsLeft());
    }


    @ParameterizedTest
    @ValueSource(ints = {MAX_ATTEMPTS, MAX_ATTEMPTS + 1})
    void attemptsLeft_atTheMaximum_shouldBeFalse(int attempts) {
        Assertions.assertFalse(contextFor(RefundStatus.PROCESSING, attempts, NOW).attemptsLeft());
    }


    @Test
    void transitionContext_shouldRequireTheRefundTheTimeAndAPositiveMaximum() {
        RefundEntity refund = refundIn(RefundStatus.PROCESSING, 1, NOW);

        Assertions.assertThrows(NullPointerException.class, () -> new RefundTransitionContext(null, NOW, 1));
        Assertions.assertThrows(NullPointerException.class, () -> new RefundTransitionContext(refund, null, 1));
        IllegalArgumentException refused = Assertions.assertThrows(
                IllegalArgumentException.class, () -> new RefundTransitionContext(refund, NOW, 0));
        Assertions.assertEquals("A refund needs at least one attempt, got 0", refused.getMessage());
        Assertions.assertTrue(new RefundTransitionContext(refund, NOW, 1).leaseExpired());
        Assertions.assertFalse(new RefundTransitionContext(refund, NOW, 1).attemptsLeft());
    }


    @Test
    void toMermaid_shouldDrawTheRefundLifecycle() {
        Assertions.assertEquals("""
                stateDiagram-v2
                    [*] --> PENDING
                    PENDING --> PROCESSING: CLAIM
                    PROCESSING --> PROCESSING: RECLAIM
                    PROCESSING --> SUCCEEDED: SUCCEED
                    PROCESSING --> PENDING: RETRY_LATER
                    PROCESSING --> FAILED: GIVE_UP
                    FAILED --> PENDING: ADMIN_RETRY
                    SUCCEEDED --> [*]
                """, MACHINE.toMermaid());
    }


    /**
     * What every step decides from every status, by the step's own actor, on a refund with {@code attempts} made whose
     * lease (in PROCESSING) ends at {@code claimedUntil}, decided at {@link #NOW}. Cells in {@link RefundStatus}
     * order: the target status of an Apply, "done", "-" for NOT_FROM_CURRENT_STATE or the name of the failed guard.
     */
    private static Map<RefundTrigger, List<String>> decisionsFromEveryStatus(int attempts, LocalDateTime claimedUntil) {
        Map<RefundTrigger, List<String>> rows = new EnumMap<>(RefundTrigger.class);
        for (RefundTrigger trigger : RefundTrigger.values()) {
            List<String> row = new ArrayList<>();
            for (RefundStatus status : RefundStatus.values()) {
                row.add(decide(status, trigger, contextFor(status, attempts, claimedUntil)));
            }
            rows.put(trigger, row);
        }
        return rows;
    }


    private static String decide(RefundStatus status, RefundTrigger trigger, RefundTransitionContext context) {
        Decision<RefundStatus, RefundTrigger> decision = MACHINE.decide(status, trigger, ACTORS.get(trigger), context);
        Assertions.assertEquals(trigger, decision.trigger());
        if (decision instanceof Decision.Apply<RefundStatus, RefundTrigger> apply) {
            Assertions.assertEquals(status, apply.from());
            return apply.to().name();
        }
        if (decision instanceof Decision.AlreadyDone<RefundStatus, RefundTrigger> done) {
            Assertions.assertEquals(status, done.current());
            return "done";
        }
        if (decision instanceof Decision.Rejected<RefundStatus, RefundTrigger> rejected) {
            Assertions.assertEquals(status, rejected.current());
            return rejected.reason() == Decision.Reason.NOT_FROM_CURRENT_STATE ? "-" : rejected.guard();
        }
        throw new AssertionError("Unknown decision " + decision);
    }


    /**
     * The context of a refund in {@code status}; only a PROCESSING refund has a lease, which ends at
     * {@code claimedUntil}.
     */
    private static RefundTransitionContext contextFor(RefundStatus status, int attempts, LocalDateTime claimedUntil) {
        RefundEntity refund = refundIn(status, attempts, status == RefundStatus.PROCESSING ? claimedUntil : null);
        return new RefundTransitionContext(refund, NOW, MAX_ATTEMPTS);
    }


    private static RefundEntity refundIn(RefundStatus status, int attempts, LocalDateTime claimedUntil) {
        boolean succeeded = status == RefundStatus.SUCCEEDED;
        return new RefundEntity(
                5L,
                7L,
                11L,
                13L,
                "after-sales-case:7",
                "SIMPAY-0123456789abcdef0123456789abcdef",
                new BigDecimal("20.00"),
                "USD",
                status.name(),
                attempts,
                status == RefundStatus.PENDING ? NOW : null,
                claimedUntil,
                succeeded ? "SIMREF-0123456789abcdef0123" : null,
                status == RefundStatus.FAILED ? "simulated gateway timeout" : null,
                2L,
                NOW.minusHours(1),
                NOW.minusMinutes(1),
                succeeded ? NOW.minusMinutes(1) : null
        );
    }
}
