package com.laioffer.onlineorder.model;


import com.laioffer.onlineorder.fulfilment.order.OrderActor;
import com.laioffer.onlineorder.statemachine.StateMachine;
import com.laioffer.onlineorder.statemachine.Transition;


import java.util.List;
import java.util.Set;


/**
 * The order lifecycle (ADR 0002 section 4.1). Checkout creates an order PLACED or PAID (outside the machine); the
 * customer, the merchant, the deadlines, the delivery and admins move it from there, and COMPLETED and CANCELLED are
 * final. Every caller of the order module names its own trigger, so the same move (PAID -> CANCELLED, say) is a
 * different command with its own actors and its own retry rule for the customer, the merchant, the accept timeout and
 * an admin. Each ADMIN_* trigger covers one target of the admin PATCH; together they allow every move the machine
 * has.
 *
 * <p>A command that already happened answers a retry unchanged (alreadyDoneWhen): a cancel of a cancelled order, a
 * merchant or admin move to the status the order is in, a reject of an order rejected for the same reason code. The
 * timeouts and the delivery steps declare none: their callers only fire them from the status they move from.
 * {@code OrderService} locks the order row, decides here and writes the move with a compare-and-set on the status it
 * read.
 */
public final class OrderStateMachine {

    /**
     * The 4.1 table. The merchant and delivery triggers also allow SYSTEM, which jobs and tests use for them.
     */
    public static final StateMachine<OrderStatus, OrderTrigger, OrderActor.Type, OrderTransitionContext>
            DEFINITION = StateMachine.builder(
                    "order",
                    OrderStatus.class,
                    OrderTrigger.class,
                    OrderActor.Type.class,
                    OrderTransitionContext.class
            )
            .initial(OrderStatus.PLACED, OrderStatus.PAID)
            .terminal(OrderStatus.COMPLETED, OrderStatus.CANCELLED)
            .on(OrderTrigger.CUSTOMER_CANCEL)
                    .from(OrderStatus.PLACED, OrderStatus.PAID, OrderStatus.ACCEPTED).to(OrderStatus.CANCELLED)
                    .by(OrderActor.Type.CUSTOMER)
                    // Whatever cancelled it: the order keeps its reason.
                    .alreadyDoneWhen((status, context) -> status == OrderStatus.CANCELLED)
                    .add()
            .on(OrderTrigger.MERCHANT_ACCEPT)
                    .from(OrderStatus.PAID).to(OrderStatus.ACCEPTED)
                    .by(OrderActor.Type.MERCHANT, OrderActor.Type.SYSTEM)
                    .alreadyDoneWhen((status, context) -> status == OrderStatus.ACCEPTED)
                    .add()
            .on(OrderTrigger.MERCHANT_REJECT)
                    .from(OrderStatus.PAID).to(OrderStatus.CANCELLED)
                    .by(OrderActor.Type.MERCHANT, OrderActor.Type.SYSTEM)
                    .alreadyDoneWhen((status, context) ->
                            status == OrderStatus.CANCELLED && context.sameCancelReasonCode())
                    .add()
            .on(OrderTrigger.MERCHANT_START_PREPARING)
                    .from(OrderStatus.ACCEPTED).to(OrderStatus.PREPARING)
                    .by(OrderActor.Type.MERCHANT, OrderActor.Type.SYSTEM)
                    .alreadyDoneWhen((status, context) -> status == OrderStatus.PREPARING)
                    .add()
            .on(OrderTrigger.PAYMENT_TIMEOUT)
                    .from(OrderStatus.PLACED).to(OrderStatus.CANCELLED)
                    .by(OrderActor.Type.SYSTEM)
                    .add()
            .on(OrderTrigger.ACCEPT_TIMEOUT)
                    .from(OrderStatus.PAID).to(OrderStatus.CANCELLED)
                    .by(OrderActor.Type.SYSTEM)
                    .add()
            .on(OrderTrigger.DELIVERY_PREPARE)
                    .from(OrderStatus.ACCEPTED).to(OrderStatus.PREPARING)
                    .by(OrderActor.Type.RIDER, OrderActor.Type.SYSTEM)
                    .add()
            .on(OrderTrigger.DELIVERY_COMPLETE)
                    .from(OrderStatus.PREPARING).to(OrderStatus.COMPLETED)
                    .by(OrderActor.Type.RIDER, OrderActor.Type.SYSTEM)
                    .add()
            .on(OrderTrigger.ADMIN_MARK_PAID)
                    .from(OrderStatus.PLACED).to(OrderStatus.PAID)
                    .by(OrderActor.Type.ADMIN, OrderActor.Type.SYSTEM)
                    .alreadyDoneWhen((status, context) -> status == OrderStatus.PAID)
                    .add()
            .on(OrderTrigger.ADMIN_ACCEPT)
                    .from(OrderStatus.PAID).to(OrderStatus.ACCEPTED)
                    .by(OrderActor.Type.ADMIN, OrderActor.Type.SYSTEM)
                    .alreadyDoneWhen((status, context) -> status == OrderStatus.ACCEPTED)
                    .add()
            .on(OrderTrigger.ADMIN_START_PREPARING)
                    .from(OrderStatus.ACCEPTED).to(OrderStatus.PREPARING)
                    .by(OrderActor.Type.ADMIN, OrderActor.Type.SYSTEM)
                    .alreadyDoneWhen((status, context) -> status == OrderStatus.PREPARING)
                    .add()
            .on(OrderTrigger.ADMIN_COMPLETE)
                    .from(OrderStatus.PREPARING).to(OrderStatus.COMPLETED)
                    .by(OrderActor.Type.ADMIN, OrderActor.Type.SYSTEM)
                    .alreadyDoneWhen((status, context) -> status == OrderStatus.COMPLETED)
                    .add()
            .on(OrderTrigger.ADMIN_CANCEL)
                    .from(OrderStatus.PLACED, OrderStatus.PAID, OrderStatus.ACCEPTED).to(OrderStatus.CANCELLED)
                    .by(OrderActor.Type.ADMIN, OrderActor.Type.SYSTEM)
                    .alreadyDoneWhen((status, context) -> status == OrderStatus.CANCELLED)
                    .add()
            .on(OrderTrigger.ADMIN_KEEP_PLACED)
                    // No order moves back to PLACED: only ever a no-op.
                    .to(OrderStatus.PLACED)
                    .by(OrderActor.Type.ADMIN, OrderActor.Type.SYSTEM)
                    .alreadyDoneWhen((status, context) -> status == OrderStatus.PLACED)
                    .add()
            .build();

    private static final List<OrderTrigger> RESTAURANT_TRIGGERS = List.of(
            OrderTrigger.MERCHANT_ACCEPT,
            OrderTrigger.MERCHANT_REJECT,
            OrderTrigger.MERCHANT_START_PREPARING
    );

    private static final List<OrderTrigger> TIMEOUT_TRIGGERS = List.of(
            OrderTrigger.PAYMENT_TIMEOUT,
            OrderTrigger.ACCEPT_TIMEOUT
    );


    private OrderStateMachine() {
    }


    /**
     * The trigger of an admin PATCH (or a status change without a known actor) to {@code target}: one ADMIN_* trigger
     * per status.
     */
    public static OrderTrigger adminTrigger(OrderStatus target) {
        return switch (target) {
            case PLACED -> OrderTrigger.ADMIN_KEEP_PLACED;
            case PAID -> OrderTrigger.ADMIN_MARK_PAID;
            case ACCEPTED -> OrderTrigger.ADMIN_ACCEPT;
            case PREPARING -> OrderTrigger.ADMIN_START_PREPARING;
            case COMPLETED -> OrderTrigger.ADMIN_COMPLETE;
            case CANCELLED -> OrderTrigger.ADMIN_CANCEL;
        };
    }


    /**
     * The merchant trigger that moves an order from exactly {@code required} to {@code target}: (PAID, ACCEPTED),
     * (PAID, CANCELLED) or (ACCEPTED, PREPARING). Chosen from the declaration, so the trigger fires from no status
     * other than the one the merchant module requires.
     *
     * @throws IllegalArgumentException for any other combination
     */
    public static OrderTrigger restaurantTrigger(OrderStatus required, OrderStatus target) {
        OrderTrigger trigger = firingOnlyFrom(RESTAURANT_TRIGGERS, required, target);
        if (trigger == null) {
            throw new IllegalArgumentException(
                    "No restaurant command moves an order from " + required + " to " + target
            );
        }
        return trigger;
    }


    /**
     * The trigger of the deadline an order {@code waiting} in a status runs out of: PAYMENT_TIMEOUT for PLACED,
     * ACCEPT_TIMEOUT for PAID.
     *
     * @throws IllegalArgumentException for a status no deadline waits in
     */
    public static OrderTrigger timeoutTrigger(OrderStatus waiting) {
        OrderTrigger trigger = firingOnlyFrom(TIMEOUT_TRIGGERS, waiting, OrderStatus.CANCELLED);
        if (trigger == null) {
            throw new IllegalArgumentException("No deadline cancels an order waiting in " + waiting);
        }
        return trigger;
    }


    /**
     * The first of {@code triggers} whose transition fires from {@code from} alone and moves to {@code to}; null when
     * there is none.
     */
    private static OrderTrigger firingOnlyFrom(List<OrderTrigger> triggers, OrderStatus from, OrderStatus to) {
        Set<OrderStatus> only = Set.of(from);
        for (OrderTrigger trigger : triggers) {
            Transition<OrderStatus, OrderTrigger, OrderActor.Type, OrderTransitionContext> transition =
                    DEFINITION.transition(trigger);
            if (transition.to() == to && transition.from().equals(only)) {
                return trigger;
            }
        }
        return null;
    }
}
