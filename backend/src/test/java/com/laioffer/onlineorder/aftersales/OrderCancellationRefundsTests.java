package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderPaymentEntity;
import com.laioffer.onlineorder.model.OrderTrigger;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderPaymentRepository;
import com.laioffer.onlineorder.service.CancellationRefunds;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;


/**
 * ADR 0002 D12 and D16: the cancel of a paid order is refunded only when the order has a captured payment and a total
 * above 0.00, and then in full, every order line included; otherwise nothing is opened and the cancel goes through.
 */
@ExtendWith(MockitoExtension.class)
class OrderCancellationRefundsTests {

    @Mock
    private OrderPaymentRepository orderPaymentRepository;

    @Mock
    private OrderHistoryItemRepository orderHistoryItemRepository;

    @Mock
    private AfterSalesTransitions transitions;

    @Mock
    private ApplicationMetricsService metricsService;

    private OrderCancellationRefunds refunds;


    @BeforeEach
    void setup() {
        refunds = refundsWithShare(50);
    }


    @Test
    void aCancelledOrderWithACapturedPayment_shouldOpenTheRefundOfEveryLineAtTheTimeOfTheCancel() {
        OrderEntity order = AfterSalesTransitionsTests.cancelledOrder("USD");
        OrderPaymentEntity payment = AfterSalesTransitionsTests.payment("16.09", "USD");
        Mockito.when(orderPaymentRepository.findByOrderId(order.id())).thenReturn(Optional.of(payment));
        Mockito.when(orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(order.id()))
                .thenReturn(AfterSalesTransitionsTests.ORDER_ITEMS);

        refunds.refundCancelledOrder(customerCancel(order, null));

        Mockito.verify(transitions).openOrderCancelled(new AfterSalesTransitions.OrderCancellation(
                order, payment, AfterSalesTransitionsTests.ORDER_ITEMS, order.total(), AfterSalesTransitionsTests.NOW));
        Mockito.verifyNoMoreInteractions(transitions);
        Mockito.verifyNoInteractions(metricsService);
    }


    /**
     * ADR 0002 section 24: a total above the capture still opens the case (the refund executor gives its refund up
     * before the gateway), but the opening is logged as a WARN and counted, so that support sees it first. Without a
     * transaction (as here) both happen at once; in the cancel's transaction, once it committed.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aCancelledOrderAboveItsCapture_shouldStillOpenTheRefundButWarnAndCountIt(CapturedOutput output) {
        OrderEntity order = AfterSalesTransitionsTests.cancelledOrder("USD");
        OrderPaymentEntity payment = AfterSalesTransitionsTests.payment("16.08", "USD");
        Mockito.when(orderPaymentRepository.findByOrderId(order.id())).thenReturn(Optional.of(payment));
        Mockito.when(orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(order.id()))
                .thenReturn(AfterSalesTransitionsTests.ORDER_ITEMS);
        Mockito.when(transitions.openOrderCancelled(Mockito.any())).thenReturn(opened(order));

        refunds.refundCancelledOrder(customerCancel(order, null));

        Mockito.verify(transitions).openOrderCancelled(new AfterSalesTransitions.OrderCancellation(
                order, payment, AfterSalesTransitionsTests.ORDER_ITEMS, order.total(), AfterSalesTransitionsTests.NOW));
        Mockito.verify(metricsService).recordAfterSalesUndercapturedCancellation();
        Assertions.assertTrue(output.getOut().contains("WARN"), output.getOut());
        Assertions.assertTrue(output.getOut().contains("Order " + order.id() + " was cancelled with a refund of "
                + "16.09 USD but its payment " + payment.providerReference() + " captured only 16.08 USD; the "
                + "refund of case 9001 will be given up before the gateway is called"), output.getOut());
    }


    /**
     * The metric counts only once the cancel commits: a cancel that rolls back opened nothing.
     */
    @Test
    void aCancelledOrderAboveItsCapture_shouldBeCountedOnlyOnceTheCancelCommits() {
        OrderEntity order = AfterSalesTransitionsTests.cancelledOrder("USD");
        Mockito.when(orderPaymentRepository.findByOrderId(order.id()))
                .thenReturn(Optional.of(AfterSalesTransitionsTests.payment("10.00", "USD")));
        Mockito.when(transitions.openOrderCancelled(Mockito.any())).thenReturn(opened(order));
        TransactionSynchronizationManager.initSynchronization();
        try {
            refunds.refundCancelledOrder(customerCancel(order, null));
            Mockito.verifyNoInteractions(metricsService);

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            Mockito.verify(metricsService).recordAfterSalesUndercapturedCancellation();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }


    /**
     * A capture of exactly the total, compared as money (16.090 is 16.09), is no warning.
     */
    @Test
    void aCancelledOrderCapturedInFull_shouldNotBeCounted() {
        OrderEntity order = AfterSalesTransitionsTests.cancelledOrder("USD");
        Mockito.when(orderPaymentRepository.findByOrderId(order.id()))
                .thenReturn(Optional.of(AfterSalesTransitionsTests.payment("16.090", "USD")));

        refunds.refundCancelledOrder(customerCancel(order, null));

        Mockito.verify(transitions).openOrderCancelled(Mockito.any());
        Mockito.verifyNoInteractions(metricsService);
    }


    /**
     * An order an admin marked PAID, or one paid before payments were recorded, has no capture to refund.
     */
    @Test
    void aCancelledOrderWithoutACapturedPayment_shouldOpenNothing() {
        OrderEntity order = AfterSalesTransitionsTests.cancelledOrder("USD");
        Mockito.when(orderPaymentRepository.findByOrderId(order.id())).thenReturn(Optional.empty());

        refunds.refundCancelledOrder(customerCancel(order, null));

        Mockito.verifyNoInteractions(transitions, orderHistoryItemRepository);
    }


    /**
     * A 0.00 order has nothing to refund, and a case of 0.00 would break chk_after_sales_case_amounts and with it the
     * cancel.
     */
    @Test
    void aCancelledOrderOfZero_shouldOpenNothingWithoutLookingForItsPayment() {
        OrderEntity paid = AfterSalesTransitionsTests.cancelledOrder("USD");
        OrderEntity free = new OrderEntity(paid.id(), paid.orderNo(), paid.customerId(), new BigDecimal("0.00"),
                paid.status(), paid.createdAt(), paid.restaurantId(), paid.cancelReason(), paid.updatedAt(),
                paid.currency());

        refunds.refundCancelledOrder(customerCancel(free, null));

        Mockito.verifyNoInteractions(orderPaymentRepository, orderHistoryItemRepository, transitions);
    }


    /**
     * Section 27, follow-up 17: a customer who cancels after the rider picked the order up gets the configured share of
     * the total back, rounded half-even (8.045 is 8.04); the case still requests the whole total.
     */
    @Test
    void aCustomerCancelAfterThePickup_shouldRefundTheConfiguredShareOfTheTotal() {
        OrderEntity order = AfterSalesTransitionsTests.cancelledOrder("USD");
        OrderPaymentEntity payment = AfterSalesTransitionsTests.payment("16.09", "USD");
        Mockito.when(orderPaymentRepository.findByOrderId(order.id())).thenReturn(Optional.of(payment));
        Mockito.when(orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(order.id()))
                .thenReturn(AfterSalesTransitionsTests.ORDER_ITEMS);
        Mockito.when(transitions.openOrderCancelled(Mockito.any())).thenReturn(opened(order));

        refunds.refundCancelledOrder(customerCancel(order, PICKED_UP_AT));

        Mockito.verify(transitions).openOrderCancelled(new AfterSalesTransitions.OrderCancellation(
                order, payment, AfterSalesTransitionsTests.ORDER_ITEMS, Money.of(new BigDecimal("8.04"), "USD"),
                AfterSalesTransitionsTests.NOW));
        Mockito.verifyNoInteractions(metricsService);
    }


    /**
     * An admin's cancel is the platform's decision: refunded in full even after the pickup, like every other cancel.
     */
    @Test
    void anAdminCancelAfterThePickup_shouldRefundTheWholeTotal() {
        OrderEntity order = AfterSalesTransitionsTests.cancelledOrder("USD");
        OrderPaymentEntity payment = AfterSalesTransitionsTests.payment("16.09", "USD");
        Mockito.when(orderPaymentRepository.findByOrderId(order.id())).thenReturn(Optional.of(payment));
        Mockito.when(orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(order.id()))
                .thenReturn(AfterSalesTransitionsTests.ORDER_ITEMS);

        refunds.refundCancelledOrder(new CancellationRefunds.Cancellation(
                order, OrderTrigger.ADMIN_CANCEL, PICKED_UP_AT, AfterSalesTransitionsTests.NOW));

        Mockito.verify(transitions).openOrderCancelled(new AfterSalesTransitions.OrderCancellation(
                order, payment, AfterSalesTransitionsTests.ORDER_ITEMS, order.total(), AfterSalesTransitionsTests.NOW));
    }


    /**
     * A share of 0 refunds nothing after the pickup, so no case opens and the cancel goes through without one.
     */
    @Test
    void aCustomerCancelAfterThePickupWithAShareOfZero_shouldOpenNothing() {
        OrderEntity order = AfterSalesTransitionsTests.cancelledOrder("USD");

        refundsWithShare(0).refundCancelledOrder(customerCancel(order, PICKED_UP_AT));

        Mockito.verifyNoInteractions(orderPaymentRepository, orderHistoryItemRepository, transitions);
    }


    @Test
    void aShareOutsideZeroToHundredPercent_shouldFailAtStartup() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> refundsWithShare(101));
        Assertions.assertThrows(IllegalArgumentException.class, () -> refundsWithShare(-1));
    }


    private static final LocalDateTime PICKED_UP_AT = AfterSalesTransitionsTests.NOW.minusMinutes(5);


    private OrderCancellationRefunds refundsWithShare(int percent) {
        return new OrderCancellationRefunds(
                orderPaymentRepository, orderHistoryItemRepository, transitions, metricsService, percent);
    }


    private static CancellationRefunds.Cancellation customerCancel(OrderEntity order, LocalDateTime pickedUpAt) {
        return new CancellationRefunds.Cancellation(
                order, OrderTrigger.CUSTOMER_CANCEL, pickedUpAt, AfterSalesTransitionsTests.NOW);
    }


    /**
     * The case the single writer opened for {@code order}, with id 9001.
     */
    private static AfterSalesCaseEntity opened(OrderEntity order) {
        return new AfterSalesCaseEntity(9001L, order.id(), order.customerId(), order.restaurantId(), "ORDER_CANCELLED",
                1, "REFUNDING", null, null, order.totalPrice(), order.totalPrice(), null, order.totalPrice(), null,
                order.currency(), null, null, "ORDER_CANCELLED", null, null, null, null, 0, 0L,
                AfterSalesTransitionsTests.NOW, AfterSalesTransitionsTests.NOW);
    }
}
