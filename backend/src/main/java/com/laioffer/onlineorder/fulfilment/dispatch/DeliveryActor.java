package com.laioffer.onlineorder.fulfilment.dispatch;


/**
 * Who fires a {@link DeliveryTrigger}: the delivery's rider (the rider API), an admin (the simulated rider actions and
 * reassignment) or the system (the order.cancelled consumer). The dispatch module's own type; it does not use the
 * order module's actor types.
 */
public enum DeliveryActor {
    RIDER,
    ADMIN,
    SYSTEM
}
