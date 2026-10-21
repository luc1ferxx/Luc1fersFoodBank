package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.model.OrderTrigger;


import java.time.LocalDateTime;
import java.util.Objects;


/**
 * The automatic refund of a paid order that is cancelled (ADR 0002 D5, D12 and D16). {@link OrderService} calls it in
 * the transaction of every PAID or ACCEPTED -> CANCELLED move (the customer's cancel, the merchant's reject, the
 * accept timeout and an admin's cancel), with the order's row still locked and after the order's own event; a PLACED
 * order was never paid and gets no call. The after-sales module implements it, so the order module does not depend on
 * that module (it owns the interface, the after-sales module depends on the order module).
 */
public interface CancellationRefunds {

    /**
     * Opens the refund of the cancelled order in the caller's transaction (propagation MANDATORY), or does nothing
     * when there is nothing to refund: an order without a captured payment (one an admin moved to PAID, or one paid
     * before payments were recorded) or with a total of 0.00. How much it refunds is the after-sales module's rule:
     * the whole total, or a share of it when the customer cancels after the rider picked the order up (section 27,
     * follow-up 17). Whatever this writes rolls back with the cancel, and a failure here rolls the cancel back.
     */
    void refundCancelledOrder(Cancellation cancellation);


    /**
     * A cancel of a paid order, as the order module wrote it.
     *
     * @param order       the order as the cancel wrote it, still locked
     * @param trigger     the trigger that cancelled it (CUSTOMER_CANCEL, MERCHANT_REJECT, ACCEPT_TIMEOUT, ADMIN_CANCEL)
     * @param pickedUpAt  when the rider picked it up, read under the order's lock; null while no pickup is recorded
     * @param cancelledAt the time of the cancel, read after the order's lock
     */
    record Cancellation(OrderEntity order, OrderTrigger trigger, LocalDateTime pickedUpAt, LocalDateTime cancelledAt) {

        public Cancellation {
            Objects.requireNonNull(order, "order");
            Objects.requireNonNull(trigger, "trigger");
            Objects.requireNonNull(cancelledAt, "cancelledAt");
        }
    }
}
