package com.laioffer.onlineorder.fulfilment.dispatch;


import com.laioffer.onlineorder.fulfilment.FulfilmentEvents;


/**
 * ASSIGNED -> PICKED_UP -> DELIVERED; ASSIGNED or PICKED_UP -> CANCELLED when the order is cancelled. A rider must
 * pick an order up before delivering it. The transitions are declared in {@link DeliveryStateMachine}.
 */
public enum DeliveryStatus {
    ASSIGNED,
    PICKED_UP,
    DELIVERED,
    CANCELLED;


    /**
     * True when some delivery command moves this status to {@code target}; staying in the same status is not a move.
     */
    public boolean canTransitionTo(DeliveryStatus target) {
        return DeliveryStateMachine.DEFINITION.canMove(this, target);
    }


    /**
     * True while the delivery is open (ASSIGNED or PICKED_UP): it counts towards its rider's load.
     */
    public boolean holdsRider() {
        return this == ASSIGNED || this == PICKED_UP;
    }


    public String eventType() {
        return switch (this) {
            case ASSIGNED -> FulfilmentEvents.DELIVERY_ASSIGNED;
            case PICKED_UP -> FulfilmentEvents.DELIVERY_PICKED_UP;
            case DELIVERED -> FulfilmentEvents.DELIVERY_DELIVERED;
            case CANCELLED -> FulfilmentEvents.DELIVERY_CANCELLED;
        };
    }
}
