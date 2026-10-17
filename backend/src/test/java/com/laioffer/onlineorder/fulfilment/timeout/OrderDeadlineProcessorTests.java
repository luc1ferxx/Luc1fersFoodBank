package com.laioffer.onlineorder.fulfilment.timeout;


import com.laioffer.onlineorder.fulfilment.order.OrderActor;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;


import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


@ExtendWith(MockitoExtension.class)
class OrderDeadlineProcessorTests {

    @Mock
    private OrderDeadlineRepository orderDeadlineRepository;

    @Mock
    private OrderLifecycleCommands orderLifecycleCommands;

    @Mock
    private ApplicationMetricsService metricsService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private OrderDeadlineProcessor processor;


    @BeforeEach
    void setup() {
        Mockito.lenient().when(transactionManager.getTransaction(Mockito.any()))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        processor = new OrderDeadlineProcessor(
                orderDeadlineRepository,
                orderLifecycleCommands,
                metricsService,
                transactionManager,
                100,
                Duration.ofSeconds(30),
                true
        );
    }


    @Test
    void dueAcceptDeadline_shouldCancelTheStillPaidOrderAsMerchantTimeout() {
        Mockito.when(orderDeadlineRepository.claimNextDue(Mockito.any()))
                .thenReturn(deadline(1L, 10L, "ACCEPT"))
                .thenReturn(null);
        Mockito.when(orderLifecycleCommands.cancelIfStillIn(10L, OrderStatus.PAID, "MERCHANT_TIMEOUT", OrderActor.system()))
                .thenReturn(Optional.of(cancelledOrder(10L, "MERCHANT_TIMEOUT")));

        Assertions.assertEquals(1, processor.fireDueDeadlines());

        Mockito.verify(orderDeadlineRepository).deleteById(1L);
        Mockito.verify(metricsService).recordFulfilmentTimeout("ACCEPT", "cancelled");
        Mockito.verify(metricsService).recordMerchantDecision("timeout");
        Mockito.verify(transactionManager, Mockito.times(2)).commit(Mockito.any(TransactionStatus.class));
    }


    @Test
    void duePaymentDeadline_shouldCancelTheStillPlacedOrderAsPaymentTimeout() {
        Mockito.when(orderDeadlineRepository.claimNextDue(Mockito.any()))
                .thenReturn(deadline(2L, 11L, "PAYMENT"))
                .thenReturn(null);
        Mockito.when(orderLifecycleCommands.cancelIfStillIn(11L, OrderStatus.PLACED, "PAYMENT_TIMEOUT", OrderActor.system()))
                .thenReturn(Optional.of(cancelledOrder(11L, "PAYMENT_TIMEOUT")));

        processor.fireDueDeadlines();

        Mockito.verify(orderDeadlineRepository).deleteById(2L);
        Mockito.verify(metricsService).recordFulfilmentTimeout("PAYMENT", "cancelled");
        Mockito.verify(metricsService, Mockito.never()).recordMerchantDecision(Mockito.anyString());
    }


    @Test
    void deadlineOfAnOrderThatMovedOn_shouldBeDroppedWithoutCancelling() {
        Mockito.when(orderDeadlineRepository.claimNextDue(Mockito.any()))
                .thenReturn(deadline(1L, 10L, "ACCEPT"))
                .thenReturn(null);
        Mockito.when(orderLifecycleCommands.cancelIfStillIn(10L, OrderStatus.PAID, "MERCHANT_TIMEOUT", OrderActor.system()))
                .thenReturn(Optional.empty());

        processor.fireDueDeadlines();

        Mockito.verify(orderDeadlineRepository).deleteById(1L);
        Mockito.verify(metricsService).recordFulfilmentTimeout("ACCEPT", "skipped");
        Mockito.verify(metricsService, Mockito.never()).recordMerchantDecision(Mockito.anyString());
    }


    @Test
    void dueAcceptDeadline_whenTheAcceptTimeoutWasSwitchedOff_shouldBeDroppedWithoutCancelling() {
        OrderDeadlineProcessor acceptTimeoutOff = new OrderDeadlineProcessor(
                orderDeadlineRepository, orderLifecycleCommands, metricsService, transactionManager, 100,
                Duration.ofSeconds(30), false);
        Mockito.when(orderDeadlineRepository.claimNextDue(Mockito.any()))
                .thenReturn(deadline(1L, 10L, "ACCEPT"))
                .thenReturn(deadline(2L, 11L, "PAYMENT"))
                .thenReturn(null);
        Mockito.when(orderLifecycleCommands.cancelIfStillIn(11L, OrderStatus.PLACED, "PAYMENT_TIMEOUT", OrderActor.system()))
                .thenReturn(Optional.of(cancelledOrder(11L, "PAYMENT_TIMEOUT")));

        Assertions.assertEquals(2, acceptTimeoutOff.fireDueDeadlines());

        Mockito.verify(orderLifecycleCommands, Mockito.never())
                .cancelIfStillIn(Mockito.eq(10L), Mockito.any(), Mockito.anyString(), Mockito.any());
        Mockito.verify(orderDeadlineRepository).deleteById(1L);
        Mockito.verify(metricsService).recordFulfilmentTimeout("ACCEPT", "skipped");
        Mockito.verify(metricsService, Mockito.never()).recordMerchantDecision(Mockito.anyString());
        // The payment timeout does not depend on the flag.
        Mockito.verify(orderDeadlineRepository).deleteById(2L);
        Mockito.verify(metricsService).recordFulfilmentTimeout("PAYMENT", "cancelled");
    }


    @Test
    void failingDeadline_shouldBeRescheduledAndTheOthersStillFired() {
        Mockito.when(orderDeadlineRepository.claimNextDue(Mockito.any()))
                .thenReturn(deadline(1L, 10L, "ACCEPT"))
                .thenReturn(deadline(2L, 11L, "ACCEPT"))
                .thenReturn(null);
        Mockito.when(orderLifecycleCommands.cancelIfStillIn(10L, OrderStatus.PAID, "MERCHANT_TIMEOUT", OrderActor.system()))
                .thenThrow(new CannotAcquireLockException("lock timeout"));
        Mockito.when(orderLifecycleCommands.cancelIfStillIn(11L, OrderStatus.PAID, "MERCHANT_TIMEOUT", OrderActor.system()))
                .thenReturn(Optional.of(cancelledOrder(11L, "MERCHANT_TIMEOUT")));

        LocalDateTime before = LocalDateTime.now();
        Assertions.assertEquals(2, processor.fireDueDeadlines());

        Mockito.verify(transactionManager).rollback(Mockito.any(TransactionStatus.class));
        Mockito.verify(orderDeadlineRepository).reschedule(
                Mockito.eq(1L),
                Mockito.argThat(retryAt -> !retryAt.isBefore(before.plusSeconds(30))),
                Mockito.contains("lock timeout"),
                Mockito.any()
        );
        Mockito.verify(orderDeadlineRepository, Mockito.never()).deleteById(1L);
        Mockito.verify(orderDeadlineRepository).deleteById(2L);
        Mockito.verify(metricsService, Mockito.times(1)).recordFulfilmentTimeout("ACCEPT", "cancelled");
    }


    @Test
    void run_shouldStopAtTheBatchSize() {
        OrderDeadlineProcessor smallBatches = new OrderDeadlineProcessor(
                orderDeadlineRepository, orderLifecycleCommands, metricsService, transactionManager, 2, Duration.ofSeconds(30),
                true);
        Mockito.when(orderDeadlineRepository.claimNextDue(Mockito.any()))
                .thenReturn(deadline(1L, 10L, "ACCEPT"), deadline(2L, 11L, "ACCEPT"), deadline(3L, 12L, "ACCEPT"));
        Mockito.when(orderLifecycleCommands.cancelIfStillIn(Mockito.anyLong(), Mockito.any(), Mockito.anyString(), Mockito.eq(OrderActor.system())))
                .thenReturn(Optional.empty());

        Assertions.assertEquals(2, smallBatches.fireDueDeadlines());
        Mockito.verify(orderDeadlineRepository, Mockito.times(2)).claimNextDue(Mockito.any());
    }


    private OrderDeadlineEntity deadline(Long id, Long orderId, String type) {
        LocalDateTime dueAt = LocalDateTime.now().minusSeconds(1);
        return new OrderDeadlineEntity(id, orderId, type, dueAt, 0, null, dueAt, dueAt);
    }


    private OrderDto cancelledOrder(Long orderId, String reason) {
        return new OrderDto(orderId, Money.of(BigDecimal.TEN, "USD"), "CANCELLED", LocalDateTime.now(), List.of(), 2L, reason);
    }
}
