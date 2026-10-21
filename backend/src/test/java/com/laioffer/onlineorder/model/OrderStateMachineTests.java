package com.laioffer.onlineorder.model;


import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands;
import com.laioffer.onlineorder.fulfilment.timeout.DeadlineType;
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
 * The order declaration of ADR 0002 section 4.1: its moves are exactly the transition table {@link OrderStatus} had
 * before the machine (invariant I1, the P1 matrix of OrderStatusTransitionTableTests), checkout creates orders in its
 * initial states, each trigger's actors, what each command decides from every status, and the helpers that name the
 * caller's trigger.
 */
class OrderStateMachineTests {

    private static final StateMachine<OrderStatus, OrderTrigger, OrderActor.Type, OrderTransitionContext> MACHINE =
            OrderStateMachine.DEFINITION;

    /**
     * OrderStatus.TRANSITIONS before the migration, written out (the "X" cells of the P1 matrix).
     */
    private static final Map<OrderStatus, Set<OrderStatus>> OLD_TRANSITIONS = Map.of(
            OrderStatus.PLACED, EnumSet.of(OrderStatus.PAID, OrderStatus.CANCELLED),
            OrderStatus.PAID, EnumSet.of(OrderStatus.ACCEPTED, OrderStatus.CANCELLED),
            OrderStatus.ACCEPTED, EnumSet.of(OrderStatus.PREPARING, OrderStatus.CANCELLED),
            OrderStatus.PREPARING, EnumSet.of(OrderStatus.COMPLETED),
            OrderStatus.COMPLETED, EnumSet.noneOf(OrderStatus.class),
            OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class)
    );

    private static final List<OrderTrigger> ADMIN_TRIGGERS = List.of(
            OrderTrigger.ADMIN_MARK_PAID,
            OrderTrigger.ADMIN_ACCEPT,
            OrderTrigger.ADMIN_START_PREPARING,
            OrderTrigger.ADMIN_COMPLETE,
            OrderTrigger.ADMIN_CANCEL,
            OrderTrigger.ADMIN_KEEP_PLACED
    );


    @Test
    void movesOfTheTransitions_shouldBeTheOldOrderStatusTable() {
        Assertions.assertEquals(OLD_TRANSITIONS, movesOf(MACHINE.transitions()));
        int pairs = 0;
        for (OrderStatus from : OrderStatus.values()) {
            Assertions.assertEquals(OLD_TRANSITIONS.get(from), MACHINE.targets(from), from.name());
            for (OrderStatus to : OrderStatus.values()) {
                pairs++;
                boolean expected = OLD_TRANSITIONS.get(from).contains(to);
                Assertions.assertEquals(expected, MACHINE.canMove(from, to), from + " -> " + to);
                Assertions.assertEquals(expected, from.canTransitionTo(to), from + " -> " + to);
            }
        }
        Assertions.assertEquals(36, pairs);
    }


    @Test
    void theAdminTriggers_shouldTogetherAllowEveryMoveOfTheTable() {
        // An admin PATCH may make any move (ADR 0002 I8), and only those.
        List<Transition<OrderStatus, OrderTrigger, OrderActor.Type, OrderTransitionContext>> admin = ADMIN_TRIGGERS
                .stream()
                .map(MACHINE::transition)
                .toList();

        Assertions.assertEquals(OLD_TRANSITIONS, movesOf(admin));
        List<OrderTrigger> allAdminTriggers = EnumSet.allOf(OrderTrigger.class).stream()
                .filter(trigger -> trigger.name().startsWith("ADMIN_"))
                .toList();
        Assertions.assertEquals(allAdminTriggers, ADMIN_TRIGGERS);
    }


    @Test
    void theDeclaration_shouldStartPlacedOrPaidAndEndCompletedOrCancelled() {
        Assertions.assertEquals("order", MACHINE.name());
        Assertions.assertEquals(Set.of(OrderStatus.PLACED, OrderStatus.PAID), MACHINE.initialStates());
        Assertions.assertEquals(Set.of(OrderStatus.COMPLETED, OrderStatus.CANCELLED), MACHINE.terminalStates());
        // One transition per trigger, declared in the enum's order; none of them is internal.
        Assertions.assertEquals(
                List.of(OrderTrigger.values()),
                MACHINE.transitions().stream().map(Transition::trigger).toList()
        );
        Assertions.assertTrue(MACHINE.transitions().stream().noneMatch(Transition::stays));
        // ADMIN_KEEP_PLACED fires from no status: it can only be a no-op.
        Assertions.assertEquals(Set.of(), MACHINE.transition(OrderTrigger.ADMIN_KEEP_PLACED).from());
    }


    @Test
    void checkout_shouldCreateOrdersInTheMachinesInitialStates() {
        // Checkout inserts the order outside the machine (ADR 0002 section 4.1).
        Assertions.assertEquals(MACHINE.initialStates(), OrderStatus.CHECKOUT_INITIAL_STATES);
        Set<OrderStatus> creatable = EnumSet.noneOf(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            try {
                creatable.add(OrderStatus.normalizeForCheckout(status.name()));
            } catch (BadRequestException ex) {
                Assertions.assertFalse(MACHINE.initialStates().contains(status), status.name());
            }
        }
        Assertions.assertEquals(MACHINE.initialStates(), creatable);
    }


    @Test
    void actors_shouldBeThoseOfTheTable() {
        Set<OrderActor.Type> merchant = EnumSet.of(OrderActor.Type.MERCHANT, OrderActor.Type.SYSTEM);
        Set<OrderActor.Type> delivery = EnumSet.of(OrderActor.Type.RIDER, OrderActor.Type.SYSTEM);
        Set<OrderActor.Type> admin = EnumSet.of(OrderActor.Type.ADMIN, OrderActor.Type.SYSTEM);
        Map<OrderTrigger, Set<OrderActor.Type>> expected = new EnumMap<>(OrderTrigger.class);
        expected.put(OrderTrigger.CUSTOMER_CANCEL, EnumSet.of(OrderActor.Type.CUSTOMER));
        expected.put(OrderTrigger.MERCHANT_ACCEPT, merchant);
        expected.put(OrderTrigger.MERCHANT_REJECT, merchant);
        expected.put(OrderTrigger.MERCHANT_START_PREPARING, merchant);
        expected.put(OrderTrigger.PAYMENT_TIMEOUT, EnumSet.of(OrderActor.Type.SYSTEM));
        expected.put(OrderTrigger.ACCEPT_TIMEOUT, EnumSet.of(OrderActor.Type.SYSTEM));
        expected.put(OrderTrigger.DELIVERY_PREPARE, delivery);
        expected.put(OrderTrigger.DELIVERY_COMPLETE, delivery);
        for (OrderTrigger trigger : ADMIN_TRIGGERS) {
            expected.put(trigger, admin);
        }

        Assertions.assertEquals(EnumSet.allOf(OrderTrigger.class), expected.keySet());
        for (OrderTrigger trigger : OrderTrigger.values()) {
            Assertions.assertEquals(expected.get(trigger), MACHINE.transition(trigger).actors(), trigger.name());
        }
    }


    @ParameterizedTest
    @CsvSource({
            "CUSTOMER_CANCEL, ADMIN",
            "CUSTOMER_CANCEL, SYSTEM",
            "MERCHANT_ACCEPT, CUSTOMER",
            "MERCHANT_REJECT, ADMIN",
            "MERCHANT_START_PREPARING, RIDER",
            "PAYMENT_TIMEOUT, ADMIN",
            "ACCEPT_TIMEOUT, MERCHANT",
            "DELIVERY_PREPARE, MERCHANT",
            "DELIVERY_COMPLETE, CUSTOMER",
            "ADMIN_CANCEL, CUSTOMER",
            "ADMIN_KEEP_PLACED, MERCHANT"
    })
    void decide_byAnActorOutsideTheTable_shouldThrow(OrderTrigger trigger, OrderActor.Type actor) {
        IllegalStateException refused = Assertions.assertThrows(
                IllegalStateException.class,
                () -> MACHINE.decide(OrderStatus.PAID, trigger, actor, new OrderTransitionContext(null, null))
        );

        Assertions.assertEquals("order: " + actor + " may not fire " + trigger, refused.getMessage());
    }


    @Test
    void customerCancel_shouldCancelAnOpenOrderAndAnswerACancelledOneUnchanged() {
        Assertions.assertEquals(
                List.of(
                        "PLACED: apply PLACED -> CANCELLED",
                        "PAID: apply PAID -> CANCELLED",
                        "ACCEPTED: apply ACCEPTED -> CANCELLED",
                        "PREPARING: rejected",
                        "COMPLETED: rejected",
                        "CANCELLED: already done"
                ),
                decisionsFromEveryStatus(OrderTrigger.CUSTOMER_CANCEL, OrderActor.Type.CUSTOMER, null)
        );
    }


    @ParameterizedTest
    @EnumSource(value = OrderActor.Type.class, names = {"MERCHANT", "SYSTEM"})
    void merchantAcceptAndStartPreparing_shouldMoveOnlyFromTheRequiredStatus(OrderActor.Type actor) {
        Assertions.assertEquals(
                List.of(
                        "PLACED: rejected",
                        "PAID: apply PAID -> ACCEPTED",
                        "ACCEPTED: already done",
                        "PREPARING: rejected",
                        "COMPLETED: rejected",
                        "CANCELLED: rejected"
                ),
                decisionsFromEveryStatus(OrderTrigger.MERCHANT_ACCEPT, actor, null)
        );
        Assertions.assertEquals(
                List.of(
                        "PLACED: rejected",
                        "PAID: rejected",
                        "ACCEPTED: apply ACCEPTED -> PREPARING",
                        "PREPARING: already done",
                        "COMPLETED: rejected",
                        "CANCELLED: rejected"
                ),
                decisionsFromEveryStatus(OrderTrigger.MERCHANT_START_PREPARING, actor, null)
        );
    }


    @Test
    void merchantReject_shouldOnlyBeDoneWhenTheOrderWasCancelledWithTheSameReasonCode() {
        OrderTransitionContext sameCode = new OrderTransitionContext(
                "MERCHANT_REJECTED: out of buns", "MERCHANT_REJECTED: sold out");
        OrderTransitionContext otherCode = new OrderTransitionContext(
                OrderLifecycleCommands.CUSTOMER_CANCELLED, "MERCHANT_REJECTED: sold out");

        Assertions.assertEquals(
                List.of(
                        "PLACED: rejected",
                        "PAID: apply PAID -> CANCELLED",
                        "ACCEPTED: rejected",
                        "PREPARING: rejected",
                        "COMPLETED: rejected",
                        "CANCELLED: already done"
                ),
                decisionsFromEveryStatus(OrderTrigger.MERCHANT_REJECT, OrderActor.Type.MERCHANT, sameCode)
        );
        // A reject of an order the customer (or the accept timeout) cancelled is not a repeat.
        Assertions.assertEquals(
                List.of(
                        "PLACED: rejected",
                        "PAID: apply PAID -> CANCELLED",
                        "ACCEPTED: rejected",
                        "PREPARING: rejected",
                        "COMPLETED: rejected",
                        "CANCELLED: rejected"
                ),
                decisionsFromEveryStatus(OrderTrigger.MERCHANT_REJECT, OrderActor.Type.MERCHANT, otherCode)
        );
    }


    @Test
    void timeoutsAndDeliverySteps_shouldMoveOnlyFromTheirStatusAndNeverCountAsDone() {
        Assertions.assertEquals(
                List.of(
                        "PLACED: apply PLACED -> CANCELLED",
                        "PAID: rejected",
                        "ACCEPTED: rejected",
                        "PREPARING: rejected",
                        "COMPLETED: rejected",
                        "CANCELLED: rejected"
                ),
                decisionsFromEveryStatus(OrderTrigger.PAYMENT_TIMEOUT, OrderActor.Type.SYSTEM, null)
        );
        Assertions.assertEquals(
                List.of(
                        "PLACED: rejected",
                        "PAID: apply PAID -> CANCELLED",
                        "ACCEPTED: rejected",
                        "PREPARING: rejected",
                        "COMPLETED: rejected",
                        "CANCELLED: rejected"
                ),
                decisionsFromEveryStatus(OrderTrigger.ACCEPT_TIMEOUT, OrderActor.Type.SYSTEM, null)
        );
        Assertions.assertEquals(
                List.of(
                        "PLACED: rejected",
                        "PAID: rejected",
                        "ACCEPTED: apply ACCEPTED -> PREPARING",
                        "PREPARING: rejected",
                        "COMPLETED: rejected",
                        "CANCELLED: rejected"
                ),
                decisionsFromEveryStatus(OrderTrigger.DELIVERY_PREPARE, OrderActor.Type.RIDER, null)
        );
        Assertions.assertEquals(
                List.of(
                        "PLACED: rejected",
                        "PAID: rejected",
                        "ACCEPTED: rejected",
                        "PREPARING: apply PREPARING -> COMPLETED",
                        "COMPLETED: rejected",
                        "CANCELLED: rejected"
                ),
                decisionsFromEveryStatus(OrderTrigger.DELIVERY_COMPLETE, OrderActor.Type.RIDER, null)
        );
    }


    @ParameterizedTest
    @EnumSource(value = OrderActor.Type.class, names = {"ADMIN", "SYSTEM"})
    void adminTriggers_shouldMakeTheOldTableMoveToTheirTargetAndAnswerTheTargetUnchanged(OrderActor.Type actor) {
        for (OrderStatus target : OrderStatus.values()) {
            OrderTrigger trigger = OrderStateMachine.adminTrigger(target);
            List<String> expected = new ArrayList<>();
            for (OrderStatus current : OrderStatus.values()) {
                if (current == target) {
                    expected.add(current + ": already done");
                } else if (OLD_TRANSITIONS.get(current).contains(target)) {
                    expected.add(current + ": apply " + current + " -> " + target);
                } else {
                    expected.add(current + ": rejected");
                }
            }

            Assertions.assertEquals(expected, decisionsFromEveryStatus(trigger, actor, null), trigger.name());
        }
    }


    @Test
    void adminTrigger_shouldNameOneAdminTriggerPerTarget() {
        Map<OrderStatus, OrderTrigger> expected = Map.of(
                OrderStatus.PLACED, OrderTrigger.ADMIN_KEEP_PLACED,
                OrderStatus.PAID, OrderTrigger.ADMIN_MARK_PAID,
                OrderStatus.ACCEPTED, OrderTrigger.ADMIN_ACCEPT,
                OrderStatus.PREPARING, OrderTrigger.ADMIN_START_PREPARING,
                OrderStatus.COMPLETED, OrderTrigger.ADMIN_COMPLETE,
                OrderStatus.CANCELLED, OrderTrigger.ADMIN_CANCEL
        );

        for (OrderStatus target : OrderStatus.values()) {
            OrderTrigger trigger = OrderStateMachine.adminTrigger(target);
            Assertions.assertEquals(expected.get(target), trigger, target.name());
            Assertions.assertEquals(target, MACHINE.transition(trigger).to(), target.name());
        }
    }


    @Test
    void restaurantTrigger_shouldNameTheMerchantTriggerOfTheThreeMerchantCommands() {
        Assertions.assertEquals(
                OrderTrigger.MERCHANT_ACCEPT,
                OrderStateMachine.restaurantTrigger(OrderStatus.PAID, OrderStatus.ACCEPTED)
        );
        Assertions.assertEquals(
                OrderTrigger.MERCHANT_REJECT,
                OrderStateMachine.restaurantTrigger(OrderStatus.PAID, OrderStatus.CANCELLED)
        );
        Assertions.assertEquals(
                OrderTrigger.MERCHANT_START_PREPARING,
                OrderStateMachine.restaurantTrigger(OrderStatus.ACCEPTED, OrderStatus.PREPARING)
        );
        // Each one fires from the required status only, as the merchant module requires it.
        for (OrderTrigger trigger : List.of(
                OrderTrigger.MERCHANT_ACCEPT, OrderTrigger.MERCHANT_REJECT, OrderTrigger.MERCHANT_START_PREPARING)) {
            Assertions.assertEquals(1, MACHINE.transition(trigger).from().size(), trigger.name());
        }
    }


    @Test
    void restaurantTrigger_forAnyOtherCombination_shouldThrow() {
        Set<List<OrderStatus>> merchantCommands = Set.of(
                List.of(OrderStatus.PAID, OrderStatus.ACCEPTED),
                List.of(OrderStatus.PAID, OrderStatus.CANCELLED),
                List.of(OrderStatus.ACCEPTED, OrderStatus.PREPARING)
        );
        int refused = 0;
        for (OrderStatus required : OrderStatus.values()) {
            for (OrderStatus target : OrderStatus.values()) {
                if (merchantCommands.contains(List.of(required, target))) {
                    continue;
                }
                IllegalArgumentException refusal = Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> OrderStateMachine.restaurantTrigger(required, target),
                        required + " -> " + target
                );
                Assertions.assertEquals(
                        "No restaurant command moves an order from " + required + " to " + target,
                        refusal.getMessage()
                );
                refused++;
            }
        }
        // Moves the machine has, but not for a merchant, are refused too (PLACED -> PAID, ACCEPTED -> CANCELLED).
        Assertions.assertEquals(33, refused);
    }


    @Test
    void timeoutTrigger_shouldNameTheTimeoutOfEachDeadline() {
        Assertions.assertEquals(OrderTrigger.PAYMENT_TIMEOUT, OrderStateMachine.timeoutTrigger(OrderStatus.PLACED));
        Assertions.assertEquals(OrderTrigger.ACCEPT_TIMEOUT, OrderStateMachine.timeoutTrigger(OrderStatus.PAID));
        // The statuses the deadlines wait in (the real timeout path passes them to cancelIfStillIn).
        Assertions.assertEquals(
                OrderTrigger.PAYMENT_TIMEOUT, OrderStateMachine.timeoutTrigger(DeadlineType.PAYMENT.waitingStatus()));
        Assertions.assertEquals(
                OrderTrigger.ACCEPT_TIMEOUT, OrderStateMachine.timeoutTrigger(DeadlineType.ACCEPT.waitingStatus()));
    }


    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"ACCEPTED", "PREPARING", "COMPLETED", "CANCELLED"})
    void timeoutTrigger_forAStatusNoDeadlineWaitsIn_shouldThrow(OrderStatus waiting) {
        IllegalArgumentException refused = Assertions.assertThrows(
                IllegalArgumentException.class, () -> OrderStateMachine.timeoutTrigger(waiting));

        Assertions.assertEquals("No deadline cancels an order waiting in " + waiting, refused.getMessage());
    }


    @Test
    void soleMoves_shouldIndexTheTriggersThatFireFromOneStatusOnly() {
        Assertions.assertEquals(
                Map.of(
                        new OrderStateMachine.SoleMove(OrderStatus.PAID, OrderStatus.ACCEPTED),
                        OrderTrigger.MERCHANT_ACCEPT,
                        new OrderStateMachine.SoleMove(OrderStatus.PREPARING, OrderStatus.COMPLETED),
                        OrderTrigger.ADMIN_COMPLETE
                ),
                // CUSTOMER_CANCEL fires from three statuses; ADMIN_KEEP_PLACED from none.
                OrderStateMachine.soleMoves(List.of(
                        OrderTrigger.MERCHANT_ACCEPT,
                        OrderTrigger.CUSTOMER_CANCEL,
                        OrderTrigger.ADMIN_KEEP_PLACED,
                        OrderTrigger.ADMIN_COMPLETE
                ))
        );
    }


    @Test
    void soleMoves_withTwoTriggersOfTheSameMove_shouldThrow() {
        // The merchant's reject and the accept timeout both move PAID to CANCELLED: one family must not hold both.
        IllegalStateException refused = Assertions.assertThrows(
                IllegalStateException.class,
                () -> OrderStateMachine.soleMoves(List.of(OrderTrigger.MERCHANT_REJECT, OrderTrigger.ACCEPT_TIMEOUT))
        );

        Assertions.assertEquals(
                "MERCHANT_REJECT and ACCEPT_TIMEOUT both move an order from only PAID to CANCELLED",
                refused.getMessage()
        );
    }


    @Test
    void transitionContext_shouldCompareTheCodesOfTheCancelReasons() {
        Assertions.assertTrue(new OrderTransitionContext(
                "MERCHANT_REJECTED: out of buns", "MERCHANT_REJECTED: sold out").sameCancelReasonCode());
        Assertions.assertTrue(
                new OrderTransitionContext("MERCHANT_REJECTED", " MERCHANT_REJECTED : x").sameCancelReasonCode());
        Assertions.assertTrue(new OrderTransitionContext(null, null).sameCancelReasonCode());
        Assertions.assertFalse(new OrderTransitionContext(
                OrderLifecycleCommands.CUSTOMER_CANCELLED, "MERCHANT_REJECTED: sold out").sameCancelReasonCode());
        Assertions.assertFalse(new OrderTransitionContext(null, "MERCHANT_REJECTED: sold out").sameCancelReasonCode());

        Assertions.assertEquals(
                "MERCHANT_REJECTED", OrderTransitionContext.cancelReasonCode("MERCHANT_REJECTED: a: b"));
        Assertions.assertEquals("PAYMENT_TIMEOUT", OrderTransitionContext.cancelReasonCode("PAYMENT_TIMEOUT"));
        Assertions.assertNull(OrderTransitionContext.cancelReasonCode(null));
    }


    @Test
    void toMermaid_shouldDrawTheOrderLifecycle() {
        Assertions.assertEquals("""
                stateDiagram-v2
                    [*] --> PLACED
                    [*] --> PAID
                    PLACED --> CANCELLED: CUSTOMER_CANCEL
                    PAID --> CANCELLED: CUSTOMER_CANCEL
                    ACCEPTED --> CANCELLED: CUSTOMER_CANCEL
                    PAID --> ACCEPTED: MERCHANT_ACCEPT
                    PAID --> CANCELLED: MERCHANT_REJECT
                    ACCEPTED --> PREPARING: MERCHANT_START_PREPARING
                    PLACED --> CANCELLED: PAYMENT_TIMEOUT
                    PAID --> CANCELLED: ACCEPT_TIMEOUT
                    ACCEPTED --> PREPARING: DELIVERY_PREPARE
                    PREPARING --> COMPLETED: DELIVERY_COMPLETE
                    PLACED --> PAID: ADMIN_MARK_PAID
                    PAID --> ACCEPTED: ADMIN_ACCEPT
                    ACCEPTED --> PREPARING: ADMIN_START_PREPARING
                    PREPARING --> COMPLETED: ADMIN_COMPLETE
                    PLACED --> CANCELLED: ADMIN_CANCEL
                    PAID --> CANCELLED: ADMIN_CANCEL
                    ACCEPTED --> CANCELLED: ADMIN_CANCEL
                    COMPLETED --> [*]
                    CANCELLED --> [*]
                """, MACHINE.toMermaid());
    }


    /**
     * For every status, the statuses the given transitions move it to.
     */
    private static Map<OrderStatus, Set<OrderStatus>> movesOf(
            List<Transition<OrderStatus, OrderTrigger, OrderActor.Type, OrderTransitionContext>> transitions
    ) {
        Map<OrderStatus, Set<OrderStatus>> moves = new EnumMap<>(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            moves.put(status, EnumSet.noneOf(OrderStatus.class));
        }
        for (Transition<OrderStatus, OrderTrigger, OrderActor.Type, OrderTransitionContext> transition : transitions) {
            for (OrderStatus from : transition.from()) {
                moves.get(from).add(transition.to());
            }
        }
        return moves;
    }


    /**
     * What {@code trigger} decides from each status, in enum order, as "STATUS: apply FROM -> TO", "STATUS: already
     * done" or "STATUS: rejected" (always NOT_FROM_CURRENT_STATE: the order machine has no guards).
     */
    private static List<String> decisionsFromEveryStatus(
            OrderTrigger trigger,
            OrderActor.Type actor,
            OrderTransitionContext context
    ) {
        List<String> decisions = new ArrayList<>();
        for (OrderStatus status : OrderStatus.values()) {
            Decision<OrderStatus, OrderTrigger> decision = MACHINE.decide(status, trigger, actor, context);
            Assertions.assertEquals(trigger, decision.trigger());
            if (decision instanceof Decision.Apply<OrderStatus, OrderTrigger> apply) {
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
