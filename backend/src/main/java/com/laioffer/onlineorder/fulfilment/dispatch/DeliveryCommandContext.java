package com.laioffer.onlineorder.fulfilment.dispatch;


import java.util.Objects;


/**
 * What {@link DeliveryTrigger#REASSIGN} reads; the other triggers read no context and are decided with null.
 *
 * @param currentRiderId   the rider the locked delivery has
 * @param requestedRiderId the rider the admin asked for
 */
public record DeliveryCommandContext(Long currentRiderId, Long requestedRiderId) {

    /**
     * True when the delivery already has the requested rider: a retried reassignment.
     */
    public boolean sameRider() {
        return Objects.equals(currentRiderId, requestedRiderId);
    }
}
