package com.laioffer.onlineorder.fulfilment.dispatch;


import com.laioffer.onlineorder.statemachine.Decision;
import com.laioffer.onlineorder.statemachine.StateMachine;
import com.laioffer.onlineorder.statemachine.Transition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;


import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * The delivery declaration of ADR 0002 section 4.2: its moves are exactly the transition table {@link DeliveryStatus}
 * had before the machine (invariant I16), each trigger's actors, and what each command decides from every status.
 */
class DeliveryStateMachineTests {

    private static final StateMachine<DeliveryStatus, DeliveryTrigger, DeliveryActor, DeliveryCommandContext> MACHINE =
            DeliveryStateMachine.DEFINITION;

    /**
     * DeliveryStatus.TRANSITIONS before the migration, written out.
     */
    private static final Map<DeliveryStatus, Set<DeliveryStatus>> OLD_TRANSITIONS = Map.of(
            DeliveryStatus.ASSIGNED, EnumSet.of(DeliveryStatus.PICKED_UP, DeliveryStatus.CANCELLED),
            DeliveryStatus.PICKED_UP, EnumSet.of(DeliveryStatus.DELIVERED, DeliveryStatus.CANCELLED),
            DeliveryStatus.DELIVERED, EnumSet.noneOf(DeliveryStatus.class),
            DeliveryStatus.CANCELLED, EnumSet.noneOf(DeliveryStatus.class)
    );


    @Test
    void movesOfTheTransitions_shouldBeTheOldDeliveryStatusTable() {
        Map<DeliveryStatus, Set<DeliveryStatus>> moves = new EnumMap<>(DeliveryStatus.class);
        for (DeliveryStatus status : DeliveryStatus.values()) {
            moves.put(status, EnumSet.noneOf(DeliveryStatus.class));
        }
        for (Transition<DeliveryStatus, DeliveryTrigger, DeliveryActor, DeliveryCommandContext> transition
                : MACHINE.transitions()) {
            if (!transition.stays()) {
                for (DeliveryStatus from : transition.from()) {
                    moves.get(from).add(transition.to());
                }
            }
        }

        Assertions.assertEquals(OLD_TRANSITIONS, moves);
        for (DeliveryStatus from : DeliveryStatus.values()) {
            Assertions.assertEquals(OLD_TRANSITIONS.get(from), MACHINE.targets(from), from.name());
            for (DeliveryStatus to : DeliveryStatus.values()) {
                boolean expected = OLD_TRANSITIONS.get(from).contains(to);
                Assertions.assertEquals(expected, MACHINE.canMove(from, to), from + " -> " + to);
                Assertions.assertEquals(expected, from.canTransitionTo(to), from + " -> " + to);
            }
        }
    }


    @Test
    void theDeclaration_shouldStartAssignedAndEndDeliveredOrCancelled() {
        Assertions.assertEquals("delivery", MACHINE.name());
        Assertions.assertEquals(Set.of(DeliveryStatus.ASSIGNED), MACHINE.initialStates());
        Assertions.assertEquals(Set.of(DeliveryStatus.DELIVERED, DeliveryStatus.CANCELLED), MACHINE.terminalStates());
        Assertions.assertEquals(
                List.of(
                        DeliveryTrigger.PICK_UP,
                        DeliveryTrigger.DELIVER,
                        DeliveryTrigger.CANCEL_FOR_ORDER,
                        DeliveryTrigger.REASSIGN
                ),
                MACHINE.transitions().stream().map(Transition::trigger).toList()
        );
        Assertions.assertTrue(MACHINE.transition(DeliveryTrigger.REASSIGN).stays());
        // Every status that holds a rider can be left, and only those: leaving one releases the rider.
        for (DeliveryStatus status : DeliveryStatus.values()) {
            Assertions.assertEquals(status.holdsRider(), !MACHINE.targets(status).isEmpty(), status.name());
        }
    }


    @Test
    void actors_shouldBeThoseOfTheTable() {
        Assertions.assertEquals(
                Set.of(DeliveryActor.RIDER, DeliveryActor.ADMIN), MACHINE.transition(DeliveryTrigger.PICK_UP).actors());
        Assertions.assertEquals(
                Set.of(DeliveryActor.RIDER, DeliveryActor.ADMIN), MACHINE.transition(DeliveryTrigger.DELIVER).actors());
        Assertions.assertEquals(
                Set.of(DeliveryActor.SYSTEM), MACHINE.transition(DeliveryTrigger.CANCEL_FOR_ORDER).actors());
        Assertions.assertEquals(Set.of(DeliveryActor.ADMIN), MACHINE.transition(DeliveryTrigger.REASSIGN).actors());
    }


    @ParameterizedTest
    @CsvSource({
            "PICK_UP, SYSTEM",
            "DELIVER, SYSTEM",
            "CANCEL_FOR_ORDER, RIDER",
            "CANCEL_FOR_ORDER, ADMIN",
            "REASSIGN, RIDER",
            "REASSIGN, SYSTEM"
    })
    void decide_byAnActorOutsideTheTable_shouldThrow(DeliveryTrigger trigger, DeliveryActor actor) {
        IllegalStateException refused = Assertions.assertThrows(
                IllegalStateException.class,
                () -> MACHINE.decide(DeliveryStatus.ASSIGNED, trigger, actor, new DeliveryCommandContext(3L, 4L))
        );

        Assertions.assertEquals("delivery: " + actor + " may not fire " + trigger, refused.getMessage());
    }


    @ParameterizedTest
    @EnumSource(value = DeliveryActor.class, names = {"RIDER", "ADMIN"})
    void pickUpAndDeliver_shouldMoveOnceAndAnswerARetryUnchanged(DeliveryActor actor) {
        Assertions.assertEquals(
                List.of(
                        "ASSIGNED: apply ASSIGNED -> PICKED_UP",
                        "PICKED_UP: already done",
                        "DELIVERED: rejected",
                        "CANCELLED: rejected"
                ),
                decisionsFromEveryStatus(DeliveryTrigger.PICK_UP, actor, null)
        );
        Assertions.assertEquals(
                List.of(
                        "ASSIGNED: rejected",
                        "PICKED_UP: apply PICKED_UP -> DELIVERED",
                        "DELIVERED: already done",
                        "CANCELLED: rejected"
                ),
                decisionsFromEveryStatus(DeliveryTrigger.DELIVER, actor, null)
        );
    }


    @Test
    void cancelForOrder_shouldCancelAnOpenDeliveryAndLeaveAFinishedOneAlone() {
        Assertions.assertEquals(
                List.of(
                        "ASSIGNED: apply ASSIGNED -> CANCELLED",
                        "PICKED_UP: apply PICKED_UP -> CANCELLED",
                        "DELIVERED: rejected",
                        "CANCELLED: already done"
                ),
                decisionsFromEveryStatus(DeliveryTrigger.CANCEL_FOR_ORDER, DeliveryActor.SYSTEM, null)
        );
    }


    @Test
    void reassign_shouldKeepTheStatusAndAnswerTheSameRiderBeforeTheStatus() {
        Assertions.assertEquals(
                List.of(
                        "ASSIGNED: apply ASSIGNED -> ASSIGNED",
                        "PICKED_UP: apply PICKED_UP -> PICKED_UP",
                        "DELIVERED: rejected",
                        "CANCELLED: rejected"
                ),
                decisionsFromEveryStatus(
                        DeliveryTrigger.REASSIGN, DeliveryActor.ADMIN, new DeliveryCommandContext(3L, 4L))
        );
        Assertions.assertEquals(
                List.of(
                        "ASSIGNED: already done",
                        "PICKED_UP: already done",
                        "DELIVERED: already done",
                        "CANCELLED: already done"
                ),
                decisionsFromEveryStatus(
                        DeliveryTrigger.REASSIGN, DeliveryActor.ADMIN, new DeliveryCommandContext(3L, 3L))
        );
    }


    @Test
    void commandContext_shouldCompareTheRiders() {
        Assertions.assertTrue(new DeliveryCommandContext(3L, 3L).sameRider());
        Assertions.assertFalse(new DeliveryCommandContext(3L, 4L).sameRider());
        Assertions.assertFalse(new DeliveryCommandContext(3L, null).sameRider());
    }


    @Test
    void advanceTrigger_shouldNameTheMoveToPickedUpOrDelivered() {
        Assertions.assertEquals(DeliveryTrigger.PICK_UP, DeliveryStateMachine.advanceTrigger(DeliveryStatus.PICKED_UP));
        Assertions.assertEquals(DeliveryTrigger.DELIVER, DeliveryStateMachine.advanceTrigger(DeliveryStatus.DELIVERED));
    }


    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"ASSIGNED", "CANCELLED"})
    void advanceTrigger_forAnotherStatus_shouldThrow(DeliveryStatus target) {
        IllegalArgumentException refused = Assertions.assertThrows(
                IllegalArgumentException.class, () -> DeliveryStateMachine.advanceTrigger(target));

        Assertions.assertEquals("A rider cannot move a delivery to " + target, refused.getMessage());
    }


    @Test
    void toMermaid_shouldDrawTheDeliveryLifecycle() {
        Assertions.assertEquals("""
                stateDiagram-v2
                    [*] --> ASSIGNED
                    ASSIGNED --> PICKED_UP: PICK_UP
                    PICKED_UP --> DELIVERED: DELIVER
                    ASSIGNED --> CANCELLED: CANCEL_FOR_ORDER
                    PICKED_UP --> CANCELLED: CANCEL_FOR_ORDER
                    ASSIGNED --> ASSIGNED: REASSIGN
                    PICKED_UP --> PICKED_UP: REASSIGN
                    DELIVERED --> [*]
                    CANCELLED --> [*]
                """, MACHINE.toMermaid());
    }


    /**
     * What {@code trigger} decides from each status, in enum order, as "STATUS: apply FROM -> TO", "STATUS: already
     * done" or "STATUS: rejected" (always NOT_FROM_CURRENT_STATE: the delivery machine has no guards).
     */
    private static List<String> decisionsFromEveryStatus(
            DeliveryTrigger trigger,
            DeliveryActor actor,
            DeliveryCommandContext context
    ) {
        List<String> decisions = new ArrayList<>();
        for (DeliveryStatus status : DeliveryStatus.values()) {
            Decision<DeliveryStatus, DeliveryTrigger> decision = MACHINE.decide(status, trigger, actor, context);
            Assertions.assertEquals(trigger, decision.trigger());
            if (decision instanceof Decision.Apply<DeliveryStatus, DeliveryTrigger> apply) {
                Assertions.assertEquals(status, apply.from());
                decisions.add(status + ": apply " + apply.from() + " -> " + apply.to());
            } else if (decision instanceof Decision.AlreadyDone) {
                decisions.add(status + ": already done");
            } else {
                Assertions.assertEquals(
                        new Decision.Rejected<>(trigger, status, Decision.Reason.NOT_FROM_CURRENT_STATE, null),
                        decision
                );
                decisions.add(status + ": rejected");
            }
        }
        return decisions;
    }
}
