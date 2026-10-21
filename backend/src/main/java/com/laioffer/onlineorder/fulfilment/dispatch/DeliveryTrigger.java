package com.laioffer.onlineorder.fulfilment.dispatch;


/**
 * The commands that change a delivery, each declared once in {@link DeliveryStateMachine}.
 */
public enum DeliveryTrigger {
    /**
     * The rider (or an admin) picked the order up.
     */
    PICK_UP,
    /**
     * The rider (or an admin) delivered the order.
     */
    DELIVER,
    /**
     * The order was cancelled (order.cancelled): an open delivery is cancelled and its rider released.
     */
    CANCEL_FOR_ORDER,
    /**
     * An admin moves an open delivery to another rider; the delivery keeps its status.
     */
    REASSIGN
}
