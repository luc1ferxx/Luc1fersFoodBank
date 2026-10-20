package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.OrderEntity;


import java.time.LocalDateTime;


/**
 * The automatic refund of a paid order that is cancelled (ADR 0002 D5, D12 and D16). {@link OrderService} calls it in
 * the transaction of every PAID or ACCEPTED -> CANCELLED move (the customer's cancel, the merchant's reject, the
 * accept timeout and an admin's cancel), with the order's row still locked and after the order's own event; a PLACED
 * order was never paid and gets no call. The after-sales module implements it, so the order module does not depend on
 * that module (it owns the interface, the after-sales module depends on the order module).
 */
public interface CancellationRefunds {

    /**
     * Opens the refund of {@code cancelledOrder} in the caller's transaction (propagation MANDATORY), or does nothing
     * when there is nothing to refund: an order without a captured payment (one an admin moved to PAID, or one paid
     * before payments were recorded) or with a total of 0.00. Whatever this writes rolls back with the cancel, and a
     * failure here rolls the cancel back.
     *
     * @param cancelledOrder the order as the cancel wrote it, still locked
     * @param cancelledAt    the time of the cancel, read after the order's lock
     */
    void refundCancelledOrder(OrderEntity cancelledOrder, LocalDateTime cancelledAt);
}
