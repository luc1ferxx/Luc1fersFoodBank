package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderPaymentEntity;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderPaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.math.BigDecimal;
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

    private OrderCancellationRefunds refunds;


    @BeforeEach
    void setup() {
        refunds = new OrderCancellationRefunds(orderPaymentRepository, orderHistoryItemRepository, transitions);
    }


    @Test
    void aCancelledOrderWithACapturedPayment_shouldOpenTheRefundOfEveryLineAtTheTimeOfTheCancel() {
        OrderEntity order = AfterSalesTransitionsTests.cancelledOrder("USD");
        OrderPaymentEntity payment = AfterSalesTransitionsTests.payment("16.09", "USD");
        Mockito.when(orderPaymentRepository.findByOrderId(order.id())).thenReturn(Optional.of(payment));
        Mockito.when(orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(order.id()))
                .thenReturn(AfterSalesTransitionsTests.ORDER_ITEMS);

        refunds.refundCancelledOrder(order, AfterSalesTransitionsTests.NOW);

        Mockito.verify(transitions).openOrderCancelled(new AfterSalesTransitions.OrderCancellation(
                order, payment, AfterSalesTransitionsTests.ORDER_ITEMS, AfterSalesTransitionsTests.NOW));
        Mockito.verifyNoMoreInteractions(transitions);
    }


    /**
     * An order an admin marked PAID, or one paid before payments were recorded, has no capture to refund.
     */
    @Test
    void aCancelledOrderWithoutACapturedPayment_shouldOpenNothing() {
        OrderEntity order = AfterSalesTransitionsTests.cancelledOrder("USD");
        Mockito.when(orderPaymentRepository.findByOrderId(order.id())).thenReturn(Optional.empty());

        refunds.refundCancelledOrder(order, AfterSalesTransitionsTests.NOW);

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

        refunds.refundCancelledOrder(free, AfterSalesTransitionsTests.NOW);

        Mockito.verifyNoInteractions(orderPaymentRepository, orderHistoryItemRepository, transitions);
    }
}
