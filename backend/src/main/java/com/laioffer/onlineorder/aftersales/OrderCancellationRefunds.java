package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderPaymentEntity;
import com.laioffer.onlineorder.fulfilment.AfterCommit;
import com.laioffer.onlineorder.model.OrderTrigger;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderPaymentRepository;
import com.laioffer.onlineorder.service.CancellationRefunds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


import java.math.BigDecimal;
import java.util.Optional;


/**
 * Opens the automatic refund of a cancelled paid order (ADR 0002 D5, D12 and D16) for the order module, which calls
 * {@link CancellationRefunds} in the cancel's transaction. The refund is an ORDER_CANCELLED case of the whole order,
 * opened by the single writer ({@link AfterSalesTransitions#openOrderCancelled}) together with its refund row. Only an
 * order with a captured payment and a total above 0.00 gets one; the cancel of any other order goes through without
 * one, so a 0.00 order or an order an admin marked PAID can still be cancelled (and its accept timeout still fires).
 * A completed order cannot be cancelled and a cancelled one cannot complete, so the case never meets a customer's
 * request on the same order.
 *
 * <p>The case refunds the order's total, except when the customer cancels an order the rider already picked up
 * (section 27, follow-up 17): then it refunds {@code app.after-sales.picked-up-cancel-refund-percent} of the total,
 * rounded half-even to the currency's minor unit, and nothing when that is 0.00. An admin's cancel after the pickup
 * still refunds the whole total: it is the platform's decision. The case's requested amount stays the total and its
 * approved amount is the refund.
 *
 * <p>The refund is not checked against the capture here (ADR 0002 section 24, deviation 2): a refund above the capture
 * still opens the case, and the refund executor gives its refund up before calling the gateway (section 19.4). So that
 * support sees such an order before then, its opening is logged as a WARN and counted in
 * onlineorder.aftersales.cancellations.undercaptured once the cancel committed.
 */
@Service
public class OrderCancellationRefunds implements CancellationRefunds {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderCancellationRefunds.class);

    private final OrderPaymentRepository orderPaymentRepository;
    private final OrderHistoryItemRepository orderHistoryItemRepository;
    private final AfterSalesTransitions transitions;
    private final ApplicationMetricsService metricsService;
    private final PickedUpRefundShare pickedUpRefundShare;


    public OrderCancellationRefunds(
            OrderPaymentRepository orderPaymentRepository,
            OrderHistoryItemRepository orderHistoryItemRepository,
            AfterSalesTransitions transitions,
            ApplicationMetricsService metricsService,
            @Value("${app.after-sales.picked-up-cancel-refund-percent:50}") int pickedUpRefundPercent
    ) {
        this.orderPaymentRepository = orderPaymentRepository;
        this.orderHistoryItemRepository = orderHistoryItemRepository;
        this.transitions = transitions;
        this.metricsService = metricsService;
        this.pickedUpRefundShare = new PickedUpRefundShare(pickedUpRefundPercent);
    }


    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void refundCancelledOrder(Cancellation cancellation) {
        OrderEntity cancelledOrder = cancellation.order();
        Money total = cancelledOrder.total();
        boolean cancelledAfterPickup = cancellation.trigger() == OrderTrigger.CUSTOMER_CANCEL
                && cancellation.pickedUpAt() != null;
        Money refund = cancelledAfterPickup ? total.multiply(pickedUpRefundShare.factor()) : total;
        if (!refund.isPositive()) {
            if (cancelledAfterPickup && total.isPositive()) {
                LOGGER.info("Order {} was cancelled by the customer after its pickup at {}; its refund share of {} is "
                        + "nothing", cancelledOrder.id(), cancellation.pickedUpAt(), total);
            }
            return;
        }
        Optional<OrderPaymentEntity> payment = orderPaymentRepository.findByOrderId(cancelledOrder.id());
        if (payment.isEmpty()) {
            // Paid before payments were recorded, or marked PAID by an admin: nothing was captured to refund
            // automatically, so support refunds it by hand (ADR 0002 D16).
            LOGGER.warn("Order {} was cancelled after payment without a captured payment; no automatic refund",
                    cancelledOrder.id());
            return;
        }
        AfterSalesCaseEntity opened = transitions.openOrderCancelled(new AfterSalesTransitions.OrderCancellation(
                cancelledOrder,
                payment.get(),
                orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(cancelledOrder.id()),
                refund,
                cancellation.cancelledAt()
        ));
        if (cancelledAfterPickup) {
            AfterCommit.run(() -> LOGGER.info("Order {} was cancelled by the customer after its pickup at {}; case {} "
                    + "refunds {} of its {}", cancelledOrder.id(), cancellation.pickedUpAt(), opened.id(), refund, total));
        }
        Money captured = payment.get().captured();
        // The opening refused a capture in another currency; only a smaller amount is left to see.
        if (refund.currency().equals(captured.currency()) && refund.isGreaterThan(captured)) {
            AfterCommit.run(() -> {
                LOGGER.warn("Order {} was cancelled with a refund of {} but its payment {} captured only {}; the "
                                + "refund of case {} will be given up before the gateway is called, for support to "
                                + "refund by hand",
                        cancelledOrder.id(), refund, payment.get().providerReference(), captured, opened.id());
                metricsService.recordAfterSalesUndercapturedCancellation();
            });
        }
    }


    /**
     * app.after-sales.picked-up-cancel-refund-percent, checked when the application starts: 0 to 100. The check sits in
     * this record rather than in the service's constructor, which Spring subclasses for its transaction proxy.
     *
     * @param percent the share of the total a customer's cancel after the pickup refunds
     */
    record PickedUpRefundShare(int percent) {

        PickedUpRefundShare {
            if (percent < 0 || percent > 100) {
                throw new IllegalArgumentException("app.after-sales.picked-up-cancel-refund-percent must be between 0 "
                        + "and 100, got " + percent);
            }
        }


        /**
         * The percent as a factor: 50 is 0.50.
         */
        BigDecimal factor() {
            return BigDecimal.valueOf(percent).movePointLeft(2);
        }
    }
}
