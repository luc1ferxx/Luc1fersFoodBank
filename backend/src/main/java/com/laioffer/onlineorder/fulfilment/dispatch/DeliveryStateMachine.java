package com.laioffer.onlineorder.fulfilment.dispatch;


import com.laioffer.onlineorder.statemachine.StateMachine;


/**
 * The delivery lifecycle (ADR 0002 section 4.2). A delivery is created ASSIGNED by dispatch (insertAssignedIfAbsent,
 * outside the machine); the rider picks it up and delivers it, and order.cancelled cancels it while it is open.
 * Reassignment is an internal transition: the delivery keeps its status and only its rider changes.
 *
 * <p>Every command declares when it already happened, so that a retry gets the delivery unchanged: a repeated pick-up
 * or delivery, a cancellation of a cancelled delivery, and a reassignment to the rider the delivery already has
 * (checked before the status, so it also answers for a finished delivery). {@link DispatchService} locks the delivery
 * row, decides here and writes the move with a compare-and-set on the status and rider it read.
 */
public final class DeliveryStateMachine {

    /**
     * The 4.2 table: PICK_UP and DELIVER by the rider or an admin, CANCEL_FOR_ORDER by the order.cancelled consumer,
     * REASSIGN by an admin.
     */
    public static final StateMachine<DeliveryStatus, DeliveryTrigger, DeliveryActor, DeliveryCommandContext>
            DEFINITION = StateMachine.builder(
                    "delivery",
                    DeliveryStatus.class,
                    DeliveryTrigger.class,
                    DeliveryActor.class,
                    DeliveryCommandContext.class
            )
            .initial(DeliveryStatus.ASSIGNED)
            .terminal(DeliveryStatus.DELIVERED, DeliveryStatus.CANCELLED)
            .on(DeliveryTrigger.PICK_UP)
                    .from(DeliveryStatus.ASSIGNED).to(DeliveryStatus.PICKED_UP)
                    .by(DeliveryActor.RIDER, DeliveryActor.ADMIN)
                    .alreadyDoneWhen((status, context) -> status == DeliveryStatus.PICKED_UP)
                    .add()
            .on(DeliveryTrigger.DELIVER)
                    .from(DeliveryStatus.PICKED_UP).to(DeliveryStatus.DELIVERED)
                    .by(DeliveryActor.RIDER, DeliveryActor.ADMIN)
                    .alreadyDoneWhen((status, context) -> status == DeliveryStatus.DELIVERED)
                    .add()
            .on(DeliveryTrigger.CANCEL_FOR_ORDER)
                    .from(DeliveryStatus.ASSIGNED, DeliveryStatus.PICKED_UP).to(DeliveryStatus.CANCELLED)
                    .by(DeliveryActor.SYSTEM)
                    .alreadyDoneWhen((status, context) -> status == DeliveryStatus.CANCELLED)
                    .add()
            .on(DeliveryTrigger.REASSIGN)
                    .from(DeliveryStatus.ASSIGNED, DeliveryStatus.PICKED_UP).stay()
                    .by(DeliveryActor.ADMIN)
                    .alreadyDoneWhen((status, context) -> context.sameRider())
                    .add()
            .build();


    private DeliveryStateMachine() {
    }


    /**
     * The trigger of a rider's move (or an admin's simulated one) to {@code target}.
     *
     * @throws IllegalArgumentException for a target other than PICKED_UP and DELIVERED
     */
    public static DeliveryTrigger advanceTrigger(DeliveryStatus target) {
        if (target == DeliveryStatus.PICKED_UP) {
            return DeliveryTrigger.PICK_UP;
        }
        if (target == DeliveryStatus.DELIVERED) {
            return DeliveryTrigger.DELIVER;
        }
        throw new IllegalArgumentException("A rider cannot move a delivery to " + target);
    }
}
