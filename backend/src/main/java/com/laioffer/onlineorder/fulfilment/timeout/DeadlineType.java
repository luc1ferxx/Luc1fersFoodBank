package com.laioffer.onlineorder.fulfilment.timeout;


import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands;
import com.laioffer.onlineorder.model.OrderStatus;


/**
 * What an order is waiting for, the status it waits in, and the cancel reason when the wait runs out.
 */
public enum DeadlineType {

    /**
     * A PLACED order waits for payment (app.fulfilment.payment-timeout).
     */
    PAYMENT(OrderStatus.PLACED, OrderLifecycleCommands.PAYMENT_TIMEOUT),

    /**
     * A PAID order waits for the merchant to accept it (app.fulfilment.accept-timeout).
     */
    ACCEPT(OrderStatus.PAID, OrderLifecycleCommands.MERCHANT_TIMEOUT);

    private final OrderStatus waitingStatus;
    private final String cancelReason;


    DeadlineType(OrderStatus waitingStatus, String cancelReason) {
        this.waitingStatus = waitingStatus;
        this.cancelReason = cancelReason;
    }


    public OrderStatus waitingStatus() {
        return waitingStatus;
    }


    public String cancelReason() {
        return cancelReason;
    }
}
