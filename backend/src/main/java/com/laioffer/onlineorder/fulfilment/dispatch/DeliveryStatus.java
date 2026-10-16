package com.laioffer.onlineorder.fulfilment.dispatch;


import com.laioffer.onlineorder.fulfilment.FulfilmentEvents;


import java.util.EnumSet;
import java.util.Map;
import java.util.Set;


/**
 * ASSIGNED -> PICKED_UP -> DELIVERED; ASSIGNED or PICKED_UP -> CANCELLED when the order is cancelled. A rider must
 * pick an order up before delivering it.
 */
public enum DeliveryStatus {
    ASSIGNED,
    PICKED_UP,
    DELIVERED,
    CANCELLED;

    private static final Map<DeliveryStatus, Set<DeliveryStatus>> TRANSITIONS = Map.of(
            ASSIGNED, EnumSet.of(PICKED_UP, CANCELLED),
            PICKED_UP, EnumSet.of(DELIVERED, CANCELLED),
            DELIVERED, EnumSet.noneOf(DeliveryStatus.class),
            CANCELLED, EnumSet.noneOf(DeliveryStatus.class)
    );


    public boolean canTransitionTo(DeliveryStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }


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
