package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderPaymentEntity;
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
 */
@Service
public class OrderCancellationRefunds implements CancellationRefunds {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderCancellationRefunds.class);

    private final OrderPaymentRepository orderPaymentRepository;
    private final OrderHistoryItemRepository orderHistoryItemRepository;
    private final AfterSalesTransitions transitions;


    public OrderCancellationRefunds(
            OrderPaymentRepository orderPaymentRepository,
            OrderHistoryItemRepository orderHistoryItemRepository,
            AfterSalesTransitions transitions
    ) {
        this.orderPaymentRepository = orderPaymentRepository;
        this.orderHistoryItemRepository = orderHistoryItemRepository;
        this.transitions = transitions;
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
        transitions.openOrderCancelled(new AfterSalesTransitions.OrderCancellation(
                cancelledOrder,
                payment.get(),
                orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(cancelledOrder.id()),
                cancelledAt
        ));
    }
}
