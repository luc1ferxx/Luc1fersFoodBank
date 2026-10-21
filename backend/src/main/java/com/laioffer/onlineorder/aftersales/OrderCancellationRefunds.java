package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderPaymentEntity;
import com.laioffer.onlineorder.fulfilment.AfterCommit;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderPaymentRepository;
import com.laioffer.onlineorder.service.CancellationRefunds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
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
 * <p>The case refunds the order's total, which is not checked against the capture here (ADR 0002 section 24,
 * deviation 2): a total above the capture still opens the case, and the refund executor gives its refund up before
 * calling the gateway (section 19.4). So that support sees such an order before then, its opening is logged as a WARN
 * and counted in onlineorder.aftersales.cancellations.undercaptured once the cancel committed.
 */
@Service
public class OrderCancellationRefunds implements CancellationRefunds {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderCancellationRefunds.class);

    private final OrderPaymentRepository orderPaymentRepository;
    private final OrderHistoryItemRepository orderHistoryItemRepository;
    private final AfterSalesTransitions transitions;
    private final ApplicationMetricsService metricsService;


    public OrderCancellationRefunds(
            OrderPaymentRepository orderPaymentRepository,
            OrderHistoryItemRepository orderHistoryItemRepository,
            AfterSalesTransitions transitions,
            ApplicationMetricsService metricsService
    ) {
        this.orderPaymentRepository = orderPaymentRepository;
        this.orderHistoryItemRepository = orderHistoryItemRepository;
        this.transitions = transitions;
        this.metricsService = metricsService;
    }


    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void refundCancelledOrder(OrderEntity cancelledOrder, LocalDateTime cancelledAt) {
        if (!cancelledOrder.total().isPositive()) {
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
                cancelledAt
        ));
        Money total = cancelledOrder.total();
        Money captured = payment.get().captured();
        // The opening refused a capture in another currency; only a smaller amount is left to see.
        if (total.currency().equals(captured.currency()) && total.isGreaterThan(captured)) {
            AfterCommit.run(() -> {
                LOGGER.warn("Order {} was cancelled with a total of {} but its payment {} captured only {}; the "
                                + "refund of case {} will be given up before the gateway is called, for support to "
                                + "refund by hand",
                        cancelledOrder.id(), total, payment.get().providerReference(), captured, opened.id());
                metricsService.recordAfterSalesUndercapturedCancellation();
            });
        }
    }
}
