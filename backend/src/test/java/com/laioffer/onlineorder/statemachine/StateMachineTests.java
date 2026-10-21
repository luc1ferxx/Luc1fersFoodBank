package com.laioffer.onlineorder.statemachine;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;


/**
 * The state machine component on a small parcel lifecycle that has every kind of transition: several from-states, an
 * internal ({@code stay()}) transition, guards, alreadyDoneWhen checks and a command that is only ever a no-op.
 */
class StateMachineTests {

    private static final StateMachine<State, Trigger, Actor, Ctx> PARCEL = declareAll(parcel()).build();


    @Test
    void build_shouldKeepTheDeclaration() {
        Assertions.assertEquals("parcel", PARCEL.name());
        Assertions.assertEquals(
                List.of(Trigger.CLAIM, Trigger.HAND_OVER, Trigger.FINISH, Trigger.DROP, Trigger.KEEP_OPEN),
                PARCEL.transitions().stream().map(Transition::trigger).toList()
        );
        Assertions.assertEquals(Set.of(State.OPEN), PARCEL.initialStates());
        Assertions.assertEquals(Set.of(State.DONE, State.DROPPED), PARCEL.terminalStates());

        Transition<State, Trigger, Actor, Ctx> finish = PARCEL.transition(Trigger.FINISH);
        Assertions.assertEquals(Set.of(State.CLAIMED), finish.from());
        Assertions.assertEquals(State.DONE, finish.to());
        Assertions.assertFalse(finish.stays());
        Assertions.assertEquals(Set.of(Actor.WORKER), finish.actors());
        Assertions.assertEquals(List.of("paid", "signed"), finish.guards().stream().map(Guard::name).toList());
        Assertions.assertNotNull(finish.alreadyDone());

        Transition<State, Trigger, Actor, Ctx> handOver = PARCEL.transition(Trigger.HAND_OVER);
        Assertions.assertTrue(handOver.stays());
        Assertions.assertNull(handOver.to());
        Assertions.assertNull(PARCEL.transition(Trigger.DROP).alreadyDone());
        Assertions.assertEquals(Set.of(), PARCEL.transition(Trigger.KEEP_OPEN).from());
    }


    @Test
    void decide_shouldApplyAMoveFromAFromState() {
        Assertions.assertEquals(
                new Decision.Apply<>(Trigger.CLAIM, State.OPEN, State.CLAIMED),
                PARCEL.decide(State.OPEN, Trigger.CLAIM, Actor.WORKER, null)
        );
        Assertions.assertEquals(
                new Decision.Apply<>(Trigger.DROP, State.CLAIMED, State.DROPPED),
                PARCEL.decide(State.CLAIMED, Trigger.DROP, Actor.SYSTEM, null)
        );
        Assertions.assertEquals(
                new Decision.Apply<>(Trigger.FINISH, State.CLAIMED, State.DONE),
                PARCEL.decide(State.CLAIMED, Trigger.FINISH, Actor.WORKER, Ctx.paidAndSigned())
        );
    }


    @Test
    void decide_aStayTransition_shouldApplyWithoutChangingTheState() {
        Decision<State, Trigger> decision = PARCEL.decide(State.CLAIMED, Trigger.HAND_OVER, Actor.ADMIN, Ctx.owners(1L, 2L));

        Assertions.assertEquals(new Decision.Apply<>(Trigger.HAND_OVER, State.CLAIMED, State.CLAIMED), decision);
        Assertions.assertEquals(Trigger.HAND_OVER, decision.trigger());
        Assertions.assertEquals(
                new Decision.Rejected<>(Trigger.HAND_OVER, State.DONE, Decision.Reason.NOT_FROM_CURRENT_STATE, null),
                PARCEL.decide(State.DONE, Trigger.HAND_OVER, Actor.ADMIN, Ctx.owners(1L, 2L))
        );
    }


    @Test
    void decide_whenAlreadyDone_shouldBeAlreadyDone() {
        Decision<State, Trigger> decision = PARCEL.decide(State.CLAIMED, Trigger.CLAIM, Actor.WORKER, null);

        Assertions.assertEquals(new Decision.AlreadyDone<>(Trigger.CLAIM, State.CLAIMED), decision);
        Assertions.assertEquals(Trigger.CLAIM, decision.trigger());
    }


    @Test
    void decide_shouldCheckAlreadyDoneBeforeTheFromState() {
        // DONE is not a from-state of FINISH or HAND_OVER, but a retry of a command that already happened is not a
        // conflict.
        Assertions.assertEquals(
                new Decision.AlreadyDone<>(Trigger.FINISH, State.DONE),
                PARCEL.decide(State.DONE, Trigger.FINISH, Actor.WORKER, Ctx.unpaid())
        );
        Assertions.assertEquals(
                new Decision.AlreadyDone<>(Trigger.HAND_OVER, State.DONE),
                PARCEL.decide(State.DONE, Trigger.HAND_OVER, Actor.ADMIN, Ctx.owners(1L, 1L))
        );
    }


    @Test
    void decide_fromAnotherState_shouldBeRejected() {
        Decision<State, Trigger> decision = PARCEL.decide(State.DONE, Trigger.CLAIM, Actor.WORKER, null);

        Assertions.assertEquals(
                new Decision.Rejected<>(Trigger.CLAIM, State.DONE, Decision.Reason.NOT_FROM_CURRENT_STATE, null),
                decision
        );
        Assertions.assertEquals(Trigger.CLAIM, decision.trigger());
        // DROP declares no alreadyDoneWhen, so dropping a dropped parcel is a conflict.
        Assertions.assertEquals(
                new Decision.Rejected<>(Trigger.DROP, State.DROPPED, Decision.Reason.NOT_FROM_CURRENT_STATE, null),
                PARCEL.decide(State.DROPPED, Trigger.DROP, Actor.ADMIN, null)
        );
    }


    @Test
    void decide_aCommandWithoutFromStates_shouldOnlyBeANoOpOrRejected() {
        Assertions.assertEquals(
                new Decision.AlreadyDone<>(Trigger.KEEP_OPEN, State.OPEN),
                PARCEL.decide(State.OPEN, Trigger.KEEP_OPEN, Actor.ADMIN, null)
        );
        Assertions.assertEquals(
                new Decision.Rejected<>(Trigger.KEEP_OPEN, State.CLAIMED, Decision.Reason.NOT_FROM_CURRENT_STATE, null),
                PARCEL.decide(State.CLAIMED, Trigger.KEEP_OPEN, Actor.ADMIN, null)
        );
    }


    @Test
    void decide_shouldCheckTheFromStateBeforeTheGuards() {
        Assertions.assertEquals(
                new Decision.Rejected<>(Trigger.FINISH, State.OPEN, Decision.Reason.NOT_FROM_CURRENT_STATE, null),
                PARCEL.decide(State.OPEN, Trigger.FINISH, Actor.WORKER, Ctx.unpaid())
        );
    }


    @Test
    void decide_shouldRunTheGuardsInDeclarationOrderAndStopAtTheFirstFailure() {
        List<String> evaluated = new ArrayList<>();
        StateMachine<State, Trigger, Actor, Ctx> machine = declareAll(parcel(), Trigger.FINISH)
                .on(Trigger.FINISH).from(State.CLAIMED).to(State.DONE).by(Actor.WORKER)
                .guard("paid", ctx -> evaluated.add("paid") && ctx.paid())
                .guard("signed", ctx -> evaluated.add("signed") && ctx.signed())
                .alreadyDoneWhen((state, ctx) -> evaluated.add("alreadyDone") && state == State.DONE)
                .add()
                .build();

        Assertions.assertEquals(
                new Decision.Rejected<>(Trigger.FINISH, State.CLAIMED, Decision.Reason.GUARD_FAILED, "paid"),
                machine.decide(State.CLAIMED, Trigger.FINISH, Actor.WORKER, new Ctx(false, false, null, null))
        );
        Assertions.assertEquals(List.of("alreadyDone", "paid"), evaluated);

        evaluated.clear();
        Assertions.assertEquals(
                new Decision.Rejected<>(Trigger.FINISH, State.CLAIMED, Decision.Reason.GUARD_FAILED, "signed"),
                machine.decide(State.CLAIMED, Trigger.FINISH, Actor.WORKER, new Ctx(true, false, null, null))
        );
        Assertions.assertEquals(List.of("alreadyDone", "paid", "signed"), evaluated);

        evaluated.clear();
        Assertions.assertEquals(
                new Decision.Apply<>(Trigger.FINISH, State.CLAIMED, State.DONE),
                machine.decide(State.CLAIMED, Trigger.FINISH, Actor.WORKER, Ctx.paidAndSigned())
        );
        Assertions.assertEquals(List.of("alreadyDone", "paid", "signed"), evaluated);

        // No guard runs for a retry or for a state the trigger does not fire from.
        evaluated.clear();
        Assertions.assertInstanceOf(
                Decision.AlreadyDone.class,
                machine.decide(State.DONE, Trigger.FINISH, Actor.WORKER, Ctx.unpaid())
        );
        Assertions.assertInstanceOf(
                Decision.Rejected.class,
                machine.decide(State.OPEN, Trigger.FINISH, Actor.WORKER, Ctx.unpaid())
        );
        Assertions.assertEquals(List.of("alreadyDone", "alreadyDone"), evaluated);

        // Nothing runs for an actor that may not fire the trigger.
        evaluated.clear();
        Assertions.assertThrows(
                IllegalStateException.class,
                () -> machine.decide(State.DONE, Trigger.FINISH, Actor.ADMIN, Ctx.paidAndSigned())
        );
        Assertions.assertEquals(List.of(), evaluated);
    }


    @Test
    void decide_byAnActorThatMayNotFireTheTrigger_shouldThrowEvenWhenItWouldBeANoOp() {
        IllegalStateException refused = Assertions.assertThrows(
                IllegalStateException.class,
                () -> PARCEL.decide(State.OPEN, Trigger.DROP, Actor.WORKER, null)
        );
        Assertions.assertEquals("parcel: WORKER may not fire DROP", refused.getMessage());

        // The actor is checked before alreadyDoneWhen: the same owner would be a no-op for an ADMIN.
        IllegalStateException refusedNoOp = Assertions.assertThrows(
                IllegalStateException.class,
                () -> PARCEL.decide(State.CLAIMED, Trigger.HAND_OVER, Actor.WORKER, Ctx.owners(1L, 1L))
        );
        Assertions.assertEquals("parcel: WORKER may not fire HAND_OVER", refusedNoOp.getMessage());
    }


    @Test
    void canMoveAndTargets_shouldFollowTheTransitionsThatChangeTheState() {
        Assertions.assertEquals(Set.of(State.CLAIMED, State.DROPPED), PARCEL.targets(State.OPEN));
        Assertions.assertEquals(Set.of(State.DONE, State.DROPPED), PARCEL.targets(State.CLAIMED));
        Assertions.assertEquals(Set.of(), PARCEL.targets(State.DONE));
        Assertions.assertEquals(Set.of(), PARCEL.targets(State.DROPPED));

        Set<List<State>> moves = new HashSet<>();
        for (State from : State.values()) {
            for (State to : State.values()) {
                if (PARCEL.canMove(from, to)) {
                    moves.add(List.of(from, to));
                }
            }
        }
        // HAND_OVER (a stay) and KEEP_OPEN (no from-state) add no move.
        Assertions.assertEquals(
                Set.of(
                        List.of(State.OPEN, State.CLAIMED),
                        List.of(State.OPEN, State.DROPPED),
                        List.of(State.CLAIMED, State.DONE),
                        List.of(State.CLAIMED, State.DROPPED)
                ),
                moves
        );
    }


    @Test
    void toMermaid_shouldDrawTheEdgesInDeclarationOrder() {
        Assertions.assertEquals(
                """
                        stateDiagram-v2
                            [*] --> OPEN
                            OPEN --> CLAIMED: CLAIM
                            OPEN --> OPEN: HAND_OVER
                            CLAIMED --> CLAIMED: HAND_OVER
                            CLAIMED --> DONE: FINISH
                            OPEN --> DROPPED: DROP
                            CLAIMED --> DROPPED: DROP
                            DONE --> [*]
                            DROPPED --> [*]
                        """,
                PARCEL.toMermaid()
        );
    }


    @Test
    void exposedCollections_shouldBeUnmodifiable() {
        Transition<State, Trigger, Actor, Ctx> claim = PARCEL.transition(Trigger.CLAIM);

        Assertions.assertThrows(UnsupportedOperationException.class, () -> PARCEL.transitions().add(claim));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> PARCEL.initialStates().add(State.DONE));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> PARCEL.terminalStates().remove(State.DONE));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> PARCEL.targets(State.OPEN).clear());
        Assertions.assertThrows(UnsupportedOperationException.class, () -> PARCEL.targets(State.DONE).add(State.OPEN));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> claim.from().add(State.DONE));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> claim.actors().add(Actor.SYSTEM));
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> PARCEL.transition(Trigger.FINISH).guards().add(new Guard<>("any", ctx -> true))
        );
    }


    @Test
    void build_shouldCopyTheDeclaration() {
        StateMachine.Builder<State, Trigger, Actor, Ctx> builder = declareAll(parcel());
        StateMachine<State, Trigger, Actor, Ctx> machine = builder.build();

        builder.initial(State.CLAIMED).terminal(State.OPEN);
        builder.on(Trigger.CLAIM).from(State.DONE).to(State.OPEN).by(Actor.SYSTEM).add();

        Assertions.assertEquals(Set.of(State.OPEN), machine.initialStates());
        Assertions.assertEquals(Set.of(State.DONE, State.DROPPED), machine.terminalStates());
        Assertions.assertEquals(5, machine.transitions().size());
        Assertions.assertEquals(Set.of(State.OPEN), machine.transition(Trigger.CLAIM).from());
    }


    @Test
    void transition_shouldCopyItsCollections() {
        Set<State> from = EnumSet.of(State.OPEN);
        Set<Actor> actors = EnumSet.of(Actor.WORKER);
        List<Guard<Ctx>> guards = new ArrayList<>();
        Transition<State, Trigger, Actor, Ctx> transition =
                new Transition<>(Trigger.CLAIM, from, State.CLAIMED, actors, guards, null);

        from.add(State.CLAIMED);
        actors.add(Actor.ADMIN);
        guards.add(new Guard<>("any", ctx -> true));

        Assertions.assertEquals(Set.of(State.OPEN), transition.from());
        Assertions.assertEquals(Set.of(Actor.WORKER), transition.actors());
        Assertions.assertEquals(List.of(), transition.guards());
    }


    @Test
    void rejected_shouldNameAGuardExactlyWhenAGuardFailed() {
        Assertions.assertEquals(
                "paid",
                new Decision.Rejected<>(Trigger.FINISH, State.CLAIMED, Decision.Reason.GUARD_FAILED, "paid").guard()
        );
        Assertions.assertNull(
                new Decision.Rejected<>(Trigger.FINISH, State.OPEN, Decision.Reason.NOT_FROM_CURRENT_STATE, null).guard()
        );
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new Decision.Rejected<>(Trigger.FINISH, State.CLAIMED, Decision.Reason.GUARD_FAILED, null)
        );
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new Decision.Rejected<>(Trigger.FINISH, State.OPEN, Decision.Reason.NOT_FROM_CURRENT_STATE, "paid")
        );
    }


    // build() validation: each case breaks one rule of a valid declaration and must report exactly that problem.

    @Test
    void build_withATriggerWithoutATransition_shouldFail() {
        assertProblems(declareAll(parcel(), Trigger.KEEP_OPEN), "KEEP_OPEN has no transition");
    }


    @Test
    void build_withATriggerDeclaredTwice_shouldFail() {
        StateMachine.Builder<State, Trigger, Actor, Ctx> builder = declareAll(parcel())
                .on(Trigger.CLAIM).from(State.OPEN).to(State.CLAIMED).by(Actor.WORKER).add();

        assertProblems(builder, "CLAIM is declared 2 times");
    }


    @Test
    void build_withATransitionWithoutActors_shouldFail() {
        StateMachine.Builder<State, Trigger, Actor, Ctx> builder = declareAll(parcel(), Trigger.CLAIM)
                .on(Trigger.CLAIM).from(State.OPEN).to(State.CLAIMED).add();

        assertProblems(builder, "CLAIM names no actor");
    }


    @Test
    void build_withAMoveToOneOfItsFromStates_shouldFail() {
        StateMachine.Builder<State, Trigger, Actor, Ctx> builder = declareAll(parcel(), Trigger.CLAIM)
                .on(Trigger.CLAIM).from(State.OPEN, State.CLAIMED).to(State.CLAIMED).by(Actor.WORKER).add();

        assertProblems(builder, "CLAIM moves CLAIMED to itself; declare stay() for an internal transition");
    }


    @Test
    void build_withAStayWithoutFromStates_shouldFail() {
        StateMachine.Builder<State, Trigger, Actor, Ctx> builder = declareAll(parcel(), Trigger.HAND_OVER)
                .on(Trigger.HAND_OVER).stay().by(Actor.ADMIN).alreadyDoneWhen((state, ctx) -> true).add();

        assertProblems(builder, "HAND_OVER stays but declares no from state");
    }


    @Test
    void build_withoutFromStatesAndWithoutAlreadyDoneWhen_shouldFail() {
        StateMachine.Builder<State, Trigger, Actor, Ctx> builder = declareAll(parcel(), Trigger.KEEP_OPEN)
                .on(Trigger.KEEP_OPEN).to(State.OPEN).by(Actor.ADMIN).add();

        assertProblems(builder, "KEEP_OPEN declares no from state and no alreadyDoneWhen");
    }


    @Test
    void build_withAMoveOutOfATerminalState_shouldFail() {
        StateMachine.Builder<State, Trigger, Actor, Ctx> builder = declareAll(parcel(), Trigger.DROP)
                .on(Trigger.DROP).from(State.OPEN, State.CLAIMED, State.DONE).to(State.DROPPED).by(Actor.ADMIN).add();

        assertProblems(builder, "DROP leaves terminal state DONE");
    }


    @Test
    void build_withAStayOnATerminalState_shouldFail() {
        StateMachine.Builder<State, Trigger, Actor, Ctx> builder = declareAll(parcel(), Trigger.HAND_OVER)
                .on(Trigger.HAND_OVER).from(State.CLAIMED, State.DROPPED).stay().by(Actor.ADMIN).add();

        assertProblems(builder, "HAND_OVER leaves terminal state DROPPED");
    }


    @Test
    void build_withAStateWithoutTransitionsThatIsNotDeclaredTerminal_shouldFail() {
        StateMachine.Builder<State, Trigger, Actor, Ctx> builder = declareAll(
                StateMachine.builder("parcel", State.class, Trigger.class, Actor.class, Ctx.class)
                        .initial(State.OPEN)
                        .terminal(State.DONE)
        );

        assertProblems(builder, "DROPPED has no transition to another state but is not declared terminal");
    }


    @Test
    void build_withoutAnInitialState_shouldFail() {
        StateMachine.Builder<State, Trigger, Actor, Ctx> builder = declareAll(
                StateMachine.builder("parcel", State.class, Trigger.class, Actor.class, Ctx.class)
                        .terminal(State.DONE, State.DROPPED)
        );

        assertProblems(builder, "no initial state is declared");
    }


    @Test
    void build_withAStateNotReachableFromTheInitialStates_shouldFail() {
        StateMachine.Builder<State, Trigger, Actor, Ctx> builder = declareAll(parcel(), Trigger.CLAIM)
                .on(Trigger.CLAIM).from(State.OPEN).to(State.DONE).by(Actor.WORKER).add();

        assertProblems(builder, "CLAIMED is not reachable from the initial states");
    }


    @Test
    void build_withStatesThatCannotReachATerminalState_shouldFail() {
        StateMachine.Builder<Spin, SpinTrigger, Actor, Void> builder = StateMachine
                .builder("spinner", Spin.class, SpinTrigger.class, Actor.class, Void.class)
                .initial(Spin.START)
                .terminal(Spin.END)
                .on(SpinTrigger.BEGIN).from(Spin.START).to(Spin.UP).by(Actor.SYSTEM).add()
                .on(SpinTrigger.FLIP).from(Spin.UP).to(Spin.DOWN).by(Actor.SYSTEM).add()
                .on(SpinTrigger.FLOP).from(Spin.DOWN).to(Spin.UP).by(Actor.SYSTEM).add()
                .on(SpinTrigger.STOP).from(Spin.START).to(Spin.END).by(Actor.SYSTEM).add();

        assertProblems(builder, "UP cannot reach a terminal state", "DOWN cannot reach a terminal state");
    }


    @Test
    void build_withATransitionWithoutATarget_shouldFail() {
        StateMachine.Builder<State, Trigger, Actor, Ctx> builder = declareAll(parcel(), Trigger.CLAIM)
                .on(Trigger.CLAIM).from(State.OPEN).by(Actor.WORKER).add();

        // Without a target the transition reads as a stay, so nothing moves to CLAIMED, nor through it to DONE.
        assertProblems(
                builder,
                "CLAIM declares neither to(...) nor stay()",
                "CLAIMED is not reachable from the initial states",
                "DONE is not reachable from the initial states"
        );
    }


    @Test
    void build_withATransitionWithTwoTargets_shouldFail() {
        StateMachine.Builder<State, Trigger, Actor, Ctx> builder = declareAll(parcel(), Trigger.CLAIM, Trigger.FINISH)
                .on(Trigger.CLAIM).from(State.OPEN).to(State.CLAIMED).stay().by(Actor.WORKER).add()
                .on(Trigger.FINISH).from(State.CLAIMED).to(State.DROPPED).to(State.DONE).by(Actor.WORKER).add();

        assertProblems(
                builder,
                "CLAIM declares more than one of to(...) and stay()",
                "FINISH declares more than one of to(...) and stay()"
        );
    }


    @Test
    void build_withAlreadyDoneWhenDeclaredTwice_shouldFail() {
        StateMachine.Builder<State, Trigger, Actor, Ctx> builder = declareAll(parcel(), Trigger.CLAIM)
                .on(Trigger.CLAIM).from(State.OPEN).to(State.CLAIMED).by(Actor.WORKER)
                .alreadyDoneWhen((state, ctx) -> state == State.CLAIMED)
                .alreadyDoneWhen((state, ctx) -> false)
                .add();

        assertProblems(builder, "CLAIM declares alreadyDoneWhen more than once");
    }


    @Test
    void build_shouldListEveryProblemAtOnce() {
        StateMachine.Builder<State, Trigger, Actor, Ctx> builder = declareAll(
                StateMachine.builder("parcel", State.class, Trigger.class, Actor.class, Ctx.class)
                        .terminal(State.DONE, State.DROPPED),
                Trigger.CLAIM,
                Trigger.KEEP_OPEN
        )
                .on(Trigger.CLAIM).from(State.OPEN).to(State.CLAIMED).add()
                .on(Trigger.CLAIM).from(State.OPEN, State.DONE).by(Actor.WORKER).add()
                .on(Trigger.HAND_OVER).from(State.CLAIMED).stay().by(Actor.ADMIN).add();

        IllegalStateException invalid = Assertions.assertThrows(IllegalStateException.class, builder::build);

        Assertions.assertEquals(
                """
                        The parcel state machine is invalid:
                         - CLAIM declares neither to(...) nor stay()
                         - CLAIM is declared 2 times
                         - HAND_OVER is declared 2 times
                         - KEEP_OPEN has no transition
                         - CLAIM names no actor
                         - CLAIM leaves terminal state DONE
                         - no initial state is declared""",
                invalid.getMessage()
        );
    }


    private static void assertProblems(StateMachine.Builder<?, ?, ?, ?> builder, String... expected) {
        IllegalStateException invalid = Assertions.assertThrows(IllegalStateException.class, builder::build);
        String[] lines = invalid.getMessage().split("\n");
        Assertions.assertTrue(lines[0].endsWith("state machine is invalid:"), invalid.getMessage());
        Assertions.assertEquals(
                Arrays.asList(expected),
                Arrays.stream(lines).skip(1).map(line -> line.substring(" - ".length())).toList()
        );
    }


    private static StateMachine.Builder<State, Trigger, Actor, Ctx> parcel() {
        return StateMachine.builder("parcel", State.class, Trigger.class, Actor.class, Ctx.class)
                .initial(State.OPEN)
                .terminal(State.DONE, State.DROPPED);
    }


    /**
     * Adds the valid parcel transitions to {@code builder}, except those of {@code left}, which the test declares
     * itself.
     */
    private static StateMachine.Builder<State, Trigger, Actor, Ctx> declareAll(
            StateMachine.Builder<State, Trigger, Actor, Ctx> builder,
            Trigger... left
    ) {
        Set<Trigger> skipped = EnumSet.noneOf(Trigger.class);
        skipped.addAll(Arrays.asList(left));
        if (!skipped.contains(Trigger.CLAIM)) {
            builder.on(Trigger.CLAIM).from(State.OPEN).to(State.CLAIMED).by(Actor.WORKER, Actor.ADMIN)
                    .alreadyDoneWhen((state, ctx) -> state == State.CLAIMED)
                    .add();
        }
        if (!skipped.contains(Trigger.HAND_OVER)) {
            builder.on(Trigger.HAND_OVER).from(State.OPEN, State.CLAIMED).stay().by(Actor.ADMIN)
                    .alreadyDoneWhen((state, ctx) -> Objects.equals(ctx.currentOwner(), ctx.requestedOwner()))
                    .add();
        }
        if (!skipped.contains(Trigger.FINISH)) {
            builder.on(Trigger.FINISH).from(State.CLAIMED).to(State.DONE).by(Actor.WORKER)
                    .guard("paid", Ctx::paid)
                    .guard("signed", Ctx::signed)
                    .alreadyDoneWhen((state, ctx) -> state == State.DONE)
                    .add();
        }
        if (!skipped.contains(Trigger.DROP)) {
            builder.on(Trigger.DROP).from(State.OPEN, State.CLAIMED).to(State.DROPPED).by(Actor.ADMIN, Actor.SYSTEM).add();
        }
        if (!skipped.contains(Trigger.KEEP_OPEN)) {
            builder.on(Trigger.KEEP_OPEN).to(State.OPEN).by(Actor.ADMIN)
                    .alreadyDoneWhen((state, ctx) -> state == State.OPEN)
                    .add();
        }
        return builder;
    }


    enum State {
        OPEN,
        CLAIMED,
        DONE,
        DROPPED
    }


    enum Trigger {
        CLAIM,
        HAND_OVER,
        FINISH,
        DROP,
        KEEP_OPEN
    }


    enum Actor {
        WORKER,
        ADMIN,
        SYSTEM
    }


    record Ctx(boolean paid, boolean signed, Long currentOwner, Long requestedOwner) {

        static Ctx paidAndSigned() {
            return new Ctx(true, true, null, null);
        }


        static Ctx unpaid() {
            return new Ctx(false, true, null, null);
        }


        static Ctx owners(Long currentOwner, Long requestedOwner) {
            return new Ctx(false, false, currentOwner, requestedOwner);
        }
    }


    enum Spin {
        START,
        UP,
        DOWN,
        END
    }


    enum SpinTrigger {
        BEGIN,
        FLIP,
        FLOP,
        STOP
    }
}
