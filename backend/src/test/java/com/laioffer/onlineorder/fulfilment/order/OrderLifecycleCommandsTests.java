package com.laioffer.onlineorder.fulfilment.order;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands.DeliveryCompletion;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands.RestaurantOrderTransition;
import com.laioffer.onlineorder.fulfilment.timeout.OrderDeadlineEntity;
import com.laioffer.onlineorder.fulfilment.timeout.OrderDeadlineProcessor;
import com.laioffer.onlineorder.fulfilment.timeout.OrderDeadlineRepository;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.IdempotencyRequestRepository;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.service.CancellationRefunds;
import com.laioffer.onlineorder.service.IdempotencyRequestInitializer;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import com.laioffer.onlineorder.service.OrderService;
import com.laioffer.onlineorder.service.OrderStatusHistoryService;
import com.laioffer.onlineorder.audit.AuditLogService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;


import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;


@ExtendWith(MockitoExtension.class)
class OrderLifecycleCommandsTests {

    private static final long ORDER_ID = 5L;
    private static final long RESTAURANT_ID = 2L;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderHistoryItemRepository orderHistoryItemRepository;

    @Mock
    private OrderEventOutboxService orderEventOutboxService;

    @Mock
    private IdempotencyRequestRepository idempotencyRequestRepository;

    @Mock
    private IdempotencyRequestInitializer idempotencyRequestInitializer;

    @Mock
    private ApplicationMetricsService metricsService;

    @Mock
    private OrderStatusHistoryService orderStatusHistoryService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CancellationRefunds cancellationRefunds;

    private OrderService orderService;


    @BeforeEach
    void setup() {
        orderService = new OrderService(
                orderRepository,
                orderHistoryItemRepository,
                orderEventOutboxService,
                metricsService,
                idempotencyRequestRepository,
                idempotencyRequestInitializer,
                orderStatusHistoryService,
                auditLogService,
                cancellationRefunds
        );
    }


    @Test
    void transitionRestaurantOrder_shouldLockTransitionAndWriteTheOrderEvent() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("PAID", RESTAURANT_ID));
        stubCompareAndSet();

        RestaurantOrderTransition transition = orderService.transitionRestaurantOrder(
                ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.ACCEPTED, null, OrderActor.system());
        OrderDto accepted = transition.order();

        Assertions.assertTrue(transition.changed());
        Assertions.assertEquals("ACCEPTED", accepted.status());
        Assertions.assertEquals(RESTAURANT_ID, accepted.restaurantId());
        Mockito.verify(orderEventOutboxService).enqueueOrderEvent(
                Mockito.argThat(order -> "ACCEPTED".equals(order.status())),
                Mockito.anyList(),
                Mockito.isNull(),
                Mockito.any(LocalDateTime.class)
        );
        Mockito.verify(metricsService).recordOrderTransition("PAID", "ACCEPTED");
    }


    @Test
    void transitionRestaurantOrder_whenOrderBelongsToAnotherRestaurant_shouldReturnNotFoundWithoutChanges() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("PAID", 99L));

        Assertions.assertThrows(
                ResourceNotFoundException.class,
                () -> orderService.transitionRestaurantOrder(ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.ACCEPTED, null, OrderActor.system())
        );

        Mockito.verify(orderRepository, Mockito.never())
                .compareAndSetStatus(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void transitionRestaurantOrder_whenOrderHasNoRestaurant_shouldReturnNotFound() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("PAID", null));

        Assertions.assertThrows(
                ResourceNotFoundException.class,
                () -> orderService.transitionRestaurantOrder(ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.ACCEPTED, null, OrderActor.system())
        );
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void transitionRestaurantOrder_whenOrderWasTimedOut_shouldConflictWithoutChanges() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("CANCELLED", RESTAURANT_ID));

        ConflictException conflict = Assertions.assertThrows(
                ConflictException.class,
                () -> orderService.transitionRestaurantOrder(ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.ACCEPTED, null, OrderActor.system())
        );

        Assertions.assertEquals(OrderLifecycleCommands.ORDER_STATUS_CONFLICT, conflict.getCode());
        Mockito.verify(orderRepository, Mockito.never())
                .compareAndSetStatus(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void transitionRestaurantOrder_whenAlreadyInTargetStatus_shouldReturnTheOrderUnchanged() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("ACCEPTED", RESTAURANT_ID));

        RestaurantOrderTransition transition = orderService.transitionRestaurantOrder(
                ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.ACCEPTED, null, OrderActor.system());

        Assertions.assertFalse(transition.changed());
        Assertions.assertEquals("ACCEPTED", transition.order().status());
        Mockito.verify(orderRepository, Mockito.never())
                .compareAndSetStatus(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void transitionRestaurantOrder_whenRejectingAnOrderTheCustomerCancelled_shouldConflict() {
        Mockito.when(orderRepository.lockById(ORDER_ID))
                .thenReturn(order("CANCELLED", RESTAURANT_ID, OrderLifecycleCommands.CUSTOMER_CANCELLED));

        ConflictException conflict = Assertions.assertThrows(
                ConflictException.class,
                () -> orderService.transitionRestaurantOrder(
                        ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.CANCELLED, "MERCHANT_REJECTED: sold out", OrderActor.system())
        );

        Assertions.assertEquals(OrderLifecycleCommands.ORDER_STATUS_CONFLICT, conflict.getCode());
        Assertions.assertTrue(conflict.getMessage().contains("CUSTOMER_CANCELLED"), conflict.getMessage());
        Mockito.verify(orderRepository, Mockito.never())
                .compareAndSetStatus(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void transitionRestaurantOrder_whenRejectingAnOrderTheAcceptTimeoutCancelled_shouldConflict() {
        Mockito.when(orderRepository.lockById(ORDER_ID))
                .thenReturn(order("CANCELLED", RESTAURANT_ID, OrderLifecycleCommands.MERCHANT_TIMEOUT));

        Assertions.assertThrows(
                ConflictException.class,
                () -> orderService.transitionRestaurantOrder(
                        ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.CANCELLED, "MERCHANT_REJECTED: sold out", OrderActor.system())
        );
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    /**
     * The merchant API's 409 text: the current status, the code of its cancel reason when it has one, the required
     * status and the target.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            CANCELLED | CUSTOMER_CANCELLED             | PAID     | ACCEPTED  | Order is CANCELLED (CUSTOMER_CANCELLED); only PAID orders can move to ACCEPTED
            ACCEPTED  |                                | PAID     | CANCELLED | Order is ACCEPTED; only PAID orders can move to CANCELLED
            PAID      |                                | ACCEPTED | PREPARING | Order is PAID; only ACCEPTED orders can move to PREPARING
            CANCELLED | CUSTOMER_CANCELLED             | PAID     | CANCELLED | Order is CANCELLED (CUSTOMER_CANCELLED); only PAID orders can move to CANCELLED
            CANCELLED | MERCHANT_REJECTED: out of buns | PAID     | ACCEPTED  | Order is CANCELLED (MERCHANT_REJECTED); only PAID orders can move to ACCEPTED
            CANCELLED |                                | PAID     | ACCEPTED  | Order is CANCELLED; only PAID orders can move to ACCEPTED
            PREPARING |                                | PAID     | ACCEPTED  | Order is PREPARING; only PAID orders can move to ACCEPTED
            COMPLETED |                                | ACCEPTED | PREPARING | Order is COMPLETED; only ACCEPTED orders can move to PREPARING
            """)
    void transitionRestaurantOrder_conflicts_shouldKeepTheirExactMessages(
            String currentStatus,
            String currentCancelReason,
            OrderStatus requiredStatus,
            OrderStatus targetStatus,
            String expectedMessage
    ) {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order(currentStatus, RESTAURANT_ID, currentCancelReason));
        String cancelReason = targetStatus == OrderStatus.CANCELLED ? "MERCHANT_REJECTED: sold out" : null;

        ConflictException conflict = Assertions.assertThrows(
                ConflictException.class,
                () -> orderService.transitionRestaurantOrder(
                        ORDER_ID, RESTAURANT_ID, requiredStatus, targetStatus, cancelReason, OrderActor.merchant(40L))
        );

        Assertions.assertEquals(OrderLifecycleCommands.ORDER_STATUS_CONFLICT, conflict.getCode());
        Assertions.assertEquals(expectedMessage, conflict.getMessage());
        // Only the lock: no status write, whichever repository method would carry it.
        Mockito.verify(orderRepository).lockById(ORDER_ID);
        Mockito.verifyNoMoreInteractions(orderRepository);
        Mockito.verifyNoInteractions(orderStatusHistoryService, orderEventOutboxService, metricsService);
    }


    @Test
    void transitionRestaurantOrder_whenTheRejectIsRepeated_shouldReturnTheFirstRejectionUnchanged() {
        Mockito.when(orderRepository.lockById(ORDER_ID))
                .thenReturn(order("CANCELLED", RESTAURANT_ID, "MERCHANT_REJECTED: out of buns"));

        RestaurantOrderTransition transition = orderService.transitionRestaurantOrder(
                ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.CANCELLED, "MERCHANT_REJECTED: sold out", OrderActor.system());

        Assertions.assertFalse(transition.changed());
        Assertions.assertEquals("MERCHANT_REJECTED: out of buns", transition.order().cancelReason());
        Mockito.verify(orderRepository, Mockito.never())
                .compareAndSetStatus(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void transitionRestaurantOrder_whenRejecting_shouldStoreTheCancelReason() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("PAID", RESTAURANT_ID));
        stubCompareAndSet();

        OrderDto rejected = orderService.transitionRestaurantOrder(
                ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.CANCELLED, "MERCHANT_REJECTED: sold out", OrderActor.system()).order();

        Assertions.assertEquals("CANCELLED", rejected.status());
        Assertions.assertEquals("MERCHANT_REJECTED: sold out", rejected.cancelReason());
        Mockito.verify(orderEventOutboxService).enqueueOrderEvent(
                Mockito.argThat(order -> "MERCHANT_REJECTED: sold out".equals(order.cancelReason())),
                Mockito.anyList(),
                Mockito.isNull(),
                Mockito.any(LocalDateTime.class)
        );
    }


    @Test
    void cancelIfStillIn_whenOrderIsStillPaid_shouldCancelWithTheReason() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("PAID", RESTAURANT_ID));
        stubCompareAndSet();

        Optional<OrderDto> cancelled = orderService.cancelIfStillIn(
                ORDER_ID, OrderStatus.PAID, OrderLifecycleCommands.MERCHANT_TIMEOUT, OrderActor.system());

        Assertions.assertTrue(cancelled.isPresent());
        Assertions.assertEquals("CANCELLED", cancelled.get().status());
        Assertions.assertEquals(OrderLifecycleCommands.MERCHANT_TIMEOUT, cancelled.get().cancelReason());
        Mockito.verify(metricsService).recordOrderTransition("PAID", "CANCELLED");
    }


    @Test
    void cancelIfStillIn_whenOrderWasAcceptedMeanwhile_shouldLeaveItAlone() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("ACCEPTED", RESTAURANT_ID));

        Optional<OrderDto> cancelled = orderService.cancelIfStillIn(
                ORDER_ID, OrderStatus.PAID, OrderLifecycleCommands.MERCHANT_TIMEOUT, OrderActor.system());

        Assertions.assertTrue(cancelled.isEmpty());
        Mockito.verify(orderRepository, Mockito.never())
                .compareAndSetStatus(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void cancelIfStillIn_whenOrderDoesNotExist_shouldDoNothing() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(null);

        Assertions.assertTrue(orderService.cancelIfStillIn(
                ORDER_ID, OrderStatus.PLACED, OrderLifecycleCommands.PAYMENT_TIMEOUT, OrderActor.system()).isEmpty());
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void cancelIfStillIn_forAMissingOrder_shouldBeEmpty() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(null);

        // Neither timeout throws for an order that is gone; the deadline is then simply dropped.
        Assertions.assertEquals(Optional.empty(), orderService.cancelIfStillIn(
                ORDER_ID, OrderStatus.PLACED, OrderLifecycleCommands.PAYMENT_TIMEOUT, OrderActor.system()));
        Assertions.assertEquals(Optional.empty(), orderService.cancelIfStillIn(
                ORDER_ID, OrderStatus.PAID, OrderLifecycleCommands.MERCHANT_TIMEOUT, OrderActor.system()));

        Mockito.verify(orderRepository, Mockito.times(2)).lockById(ORDER_ID);
        Mockito.verifyNoMoreInteractions(orderRepository);
        Mockito.verifyNoInteractions(orderStatusHistoryService, orderEventOutboxService, metricsService);
    }


    @Test
    void completeDeliveredOrder_fromPreparing_shouldComplete() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("PREPARING", RESTAURANT_ID));
        stubCompareAndSet();

        Assertions.assertEquals(DeliveryCompletion.COMPLETED, orderService.completeDeliveredOrder(ORDER_ID, OrderActor.system()));

        Mockito.verify(orderEventOutboxService).enqueueOrderEvent(
                Mockito.argThat(order -> "COMPLETED".equals(order.status())),
                Mockito.anyList(),
                Mockito.isNull(),
                Mockito.any(LocalDateTime.class)
        );
        Mockito.verifyNoMoreInteractions(orderEventOutboxService);
    }


    @Test
    void completeDeliveredOrder_fromAccepted_shouldPassThroughPreparingInOneTransaction() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("ACCEPTED", RESTAURANT_ID));
        stubCompareAndSet();

        Assertions.assertEquals(DeliveryCompletion.COMPLETED, orderService.completeDeliveredOrder(ORDER_ID, OrderActor.system()));

        ArgumentCaptor<OrderEntity> events = ArgumentCaptor.forClass(OrderEntity.class);
        Mockito.verify(orderEventOutboxService, Mockito.times(2)).enqueueOrderEvent(
                events.capture(),
                Mockito.anyList(),
                Mockito.isNull(),
                Mockito.any(LocalDateTime.class)
        );
        Assertions.assertEquals(
                List.of("PREPARING", "COMPLETED"),
                events.getAllValues().stream().map(OrderEntity::status).toList()
        );
        InOrder transitions = Mockito.inOrder(metricsService);
        transitions.verify(metricsService).recordOrderTransition("ACCEPTED", "PREPARING");
        transitions.verify(metricsService).recordOrderTransition("PREPARING", "COMPLETED");
    }


    @Test
    void completeDeliveredOrder_whenAlreadyCompleted_shouldBeANoOp() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("COMPLETED", RESTAURANT_ID));

        Assertions.assertEquals(DeliveryCompletion.ALREADY_COMPLETED, orderService.completeDeliveredOrder(ORDER_ID, OrderActor.system()));
        Mockito.verify(orderRepository, Mockito.never())
                .compareAndSetStatus(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void completeDeliveredOrder_whenOrderWasCancelled_shouldIgnoreTheDelivery() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("CANCELLED", RESTAURANT_ID));

        Assertions.assertEquals(DeliveryCompletion.IGNORED, orderService.completeDeliveredOrder(ORDER_ID, OrderActor.system()));
        Mockito.verify(orderRepository, Mockito.never())
                .compareAndSetStatus(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void completeDeliveredOrder_fromPlacedOrPaid_shouldBeIgnoredWithoutWrites() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(
                order("PLACED", RESTAURANT_ID),
                order("PAID", RESTAURANT_ID)
        );

        // An order that was never accepted is not completed by a delivery, and the call does not throw.
        Assertions.assertEquals(DeliveryCompletion.IGNORED, orderService.completeDeliveredOrder(ORDER_ID, OrderActor.rider(4L)));
        Assertions.assertEquals(DeliveryCompletion.IGNORED, orderService.completeDeliveredOrder(ORDER_ID, OrderActor.rider(4L)));

        // Only the locks: no status write, whichever repository method would carry it.
        Mockito.verify(orderRepository, Mockito.times(2)).lockById(ORDER_ID);
        Mockito.verifyNoMoreInteractions(orderRepository);
        Mockito.verifyNoInteractions(orderStatusHistoryService, orderEventOutboxService, metricsService);
    }


    @Test
    void completeDeliveredOrder_forAMissingOrder_shouldBeNotFound() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(null);

        Assertions.assertEquals(DeliveryCompletion.NOT_FOUND, orderService.completeDeliveredOrder(ORDER_ID, OrderActor.rider(4L)));

        Mockito.verify(orderRepository).lockById(ORDER_ID);
        Mockito.verifyNoMoreInteractions(orderRepository);
        Mockito.verifyNoInteractions(orderStatusHistoryService, orderEventOutboxService, metricsService);
    }


    @Test
    void cancelOrder_byCustomer_shouldRecordCustomerCancelledReason() {
        Mockito.when(orderRepository.lockByIdAndCustomerId(ORDER_ID, 1L)).thenReturn(order("PAID", RESTAURANT_ID));
        stubCompareAndSet();

        OrderDto cancelled = orderService.cancelOrder(1L, ORDER_ID);

        Assertions.assertEquals(OrderLifecycleCommands.CUSTOMER_CANCELLED, cancelled.cancelReason());
    }


    @Test
    void transitionRestaurantOrder_shouldRecordTheMerchantAndBumpUpdatedAt() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("PAID", RESTAURANT_ID));
        stubCompareAndSet();

        orderService.transitionRestaurantOrder(
                ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.ACCEPTED, null, OrderActor.merchant(40L));

        ArgumentCaptor<LocalDateTime> updatedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        Mockito.verify(orderRepository).compareAndSetStatus(
                Mockito.eq(ORDER_ID), Mockito.eq("PAID"), Mockito.eq("ACCEPTED"), Mockito.isNull(), updatedAt.capture());
        ArgumentCaptor<LocalDateTime> occurredAt = ArgumentCaptor.forClass(LocalDateTime.class);
        Mockito.verify(orderStatusHistoryService).record(
                Mockito.eq(ORDER_ID),
                Mockito.eq(OrderStatus.PAID),
                Mockito.eq(OrderStatus.ACCEPTED),
                Mockito.eq(OrderActor.merchant(40L)),
                Mockito.isNull(),
                occurredAt.capture()
        );
        // updated_at moves with the status, to the time the history row and the event carry.
        Assertions.assertEquals(occurredAt.getValue(), updatedAt.getValue());
        Assertions.assertNotEquals(order("PAID", RESTAURANT_ID).createdAt(), updatedAt.getValue());
        Mockito.verify(orderEventOutboxService).enqueueOrderEvent(
                Mockito.any(), Mockito.anyList(), Mockito.isNull(), Mockito.eq(occurredAt.getValue()));
    }


    @Test
    void transitionRestaurantOrder_whenRejecting_shouldRecordTheRejectReason() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("PAID", RESTAURANT_ID));
        stubCompareAndSet();

        orderService.transitionRestaurantOrder(ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.CANCELLED,
                "MERCHANT_REJECTED: sold out", OrderActor.merchant(40L));

        Mockito.verify(orderStatusHistoryService).record(
                Mockito.eq(ORDER_ID),
                Mockito.eq(OrderStatus.PAID),
                Mockito.eq(OrderStatus.CANCELLED),
                Mockito.eq(OrderActor.merchant(40L)),
                Mockito.eq("MERCHANT_REJECTED: sold out"),
                Mockito.any(LocalDateTime.class)
        );
    }


    /**
     * The merchant module calls with a MERCHANT actor (not SYSTEM): each command fires its own merchant trigger, which
     * the merchant may fire, and writes the move with a compare-and-set on the required status (ADR 0002 section 4.1).
     */
    @ParameterizedTest
    @CsvSource(nullValues = "null", textBlock = """
            PAID,     ACCEPTED,  null
            PAID,     CANCELLED, 'MERCHANT_REJECTED: sold out'
            ACCEPTED, PREPARING, null
            """)
    void restaurantCommands_byAMerchant_shouldMoveTheOrderFromTheRequiredStatus(
            OrderStatus requiredStatus,
            OrderStatus targetStatus,
            String cancelReason
    ) {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order(requiredStatus.name(), RESTAURANT_ID));
        stubCompareAndSet();

        RestaurantOrderTransition transition = orderService.transitionRestaurantOrder(
                ORDER_ID, RESTAURANT_ID, requiredStatus, targetStatus, cancelReason, OrderActor.merchant(40L));

        Assertions.assertTrue(transition.changed());
        Assertions.assertEquals(targetStatus.name(), transition.order().status());
        Assertions.assertEquals(cancelReason, transition.order().cancelReason());
        ArgumentCaptor<LocalDateTime> updatedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        Mockito.verify(orderRepository).compareAndSetStatus(
                Mockito.eq(ORDER_ID), Mockito.eq(requiredStatus.name()), Mockito.eq(targetStatus.name()),
                Mockito.eq(cancelReason), updatedAt.capture());
        Mockito.verify(orderStatusHistoryService).record(
                ORDER_ID, requiredStatus, targetStatus, OrderActor.merchant(40L), cancelReason, updatedAt.getValue());
        Mockito.verify(orderEventOutboxService).enqueueOrderEvent(
                Mockito.any(), Mockito.anyList(), Mockito.isNull(), Mockito.eq(updatedAt.getValue()));
        Mockito.verify(metricsService).recordOrderTransition(requiredStatus.name(), targetStatus.name());
    }


    @Test
    void restaurantCommand_thatNoMerchantCommandMakes_shouldThrowBeforeLocking() {
        IllegalArgumentException refused = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> orderService.transitionRestaurantOrder(
                        ORDER_ID, RESTAURANT_ID, OrderStatus.PLACED, OrderStatus.PAID, null, OrderActor.merchant(40L))
        );

        Assertions.assertEquals("No restaurant command moves an order from PLACED to PAID", refused.getMessage());
        Mockito.verifyNoInteractions(orderRepository, orderStatusHistoryService, orderEventOutboxService, metricsService);
    }


    @Test
    void restaurantCommand_byAnActorTheMachineDoesNotAllow_shouldThrowWithoutWriting() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("PAID", RESTAURANT_ID));

        // A programming error (the security configuration keeps customers away from the merchant API): a 500.
        IllegalStateException refused = Assertions.assertThrows(
                IllegalStateException.class,
                () -> orderService.transitionRestaurantOrder(
                        ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.ACCEPTED, null, OrderActor.customer(1L))
        );

        Assertions.assertEquals("order: CUSTOMER may not fire MERCHANT_ACCEPT", refused.getMessage());
        Mockito.verify(orderRepository).lockById(ORDER_ID);
        Mockito.verifyNoMoreInteractions(orderRepository);
        Mockito.verifyNoInteractions(orderStatusHistoryService, orderEventOutboxService, metricsService);
    }


    @Test
    void cancelIfStillIn_forAStatusNoDeadlineWaitsIn_shouldThrowBeforeLocking() {
        IllegalArgumentException refused = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> orderService.cancelIfStillIn(
                        ORDER_ID, OrderStatus.ACCEPTED, OrderLifecycleCommands.MERCHANT_TIMEOUT, OrderActor.system())
        );

        Assertions.assertEquals("No deadline cancels an order waiting in ACCEPTED", refused.getMessage());
        Mockito.verifyNoInteractions(orderRepository, orderStatusHistoryService, orderEventOutboxService, metricsService);
    }


    /**
     * The real timeout path: the deadline poller passes each deadline's waiting status to cancelIfStillIn, which fires
     * PAYMENT_TIMEOUT for a PLACED order and ACCEPT_TIMEOUT for a PAID one. A trigger the machine refused would throw
     * there, and the poller would reschedule the deadline instead of cancelling the order.
     */
    @Test
    void deadlinePoller_shouldCancelThroughTheTimeoutOfEachDeadline() {
        OrderDeadlineRepository orderDeadlineRepository = Mockito.mock(OrderDeadlineRepository.class);
        PlatformTransactionManager transactionManager = Mockito.mock(PlatformTransactionManager.class);
        Mockito.when(transactionManager.getTransaction(Mockito.any()))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        LocalDateTime dueAt = LocalDateTime.now().minusSeconds(1);
        Mockito.when(orderDeadlineRepository.claimNextDue(Mockito.any())).thenReturn(
                new OrderDeadlineEntity(1L, ORDER_ID, "PAYMENT", dueAt, 0, null, dueAt, dueAt),
                new OrderDeadlineEntity(2L, ORDER_ID, "ACCEPT", dueAt, 0, null, dueAt, dueAt),
                null
        );
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(
                order("PLACED", RESTAURANT_ID),
                order("PAID", RESTAURANT_ID)
        );
        stubCompareAndSet();
        OrderDeadlineProcessor poller = new OrderDeadlineProcessor(
                orderDeadlineRepository, orderService, metricsService, transactionManager, 100, Duration.ofSeconds(30), true);

        Assertions.assertEquals(2, poller.fireDueDeadlines());

        InOrder cancels = Mockito.inOrder(orderRepository, orderDeadlineRepository);
        cancels.verify(orderRepository).compareAndSetStatus(
                Mockito.eq(ORDER_ID), Mockito.eq("PLACED"), Mockito.eq("CANCELLED"),
                Mockito.eq(OrderLifecycleCommands.PAYMENT_TIMEOUT), Mockito.any(LocalDateTime.class));
        cancels.verify(orderDeadlineRepository).deleteById(1L);
        cancels.verify(orderRepository).compareAndSetStatus(
                Mockito.eq(ORDER_ID), Mockito.eq("PAID"), Mockito.eq("CANCELLED"),
                Mockito.eq(OrderLifecycleCommands.MERCHANT_TIMEOUT), Mockito.any(LocalDateTime.class));
        cancels.verify(orderDeadlineRepository).deleteById(2L);
        Mockito.verify(orderStatusHistoryService).record(
                Mockito.eq(ORDER_ID), Mockito.eq(OrderStatus.PLACED), Mockito.eq(OrderStatus.CANCELLED),
                Mockito.eq(OrderActor.system()), Mockito.eq(OrderLifecycleCommands.PAYMENT_TIMEOUT), Mockito.any());
        Mockito.verify(orderStatusHistoryService).record(
                Mockito.eq(ORDER_ID), Mockito.eq(OrderStatus.PAID), Mockito.eq(OrderStatus.CANCELLED),
                Mockito.eq(OrderActor.system()), Mockito.eq(OrderLifecycleCommands.MERCHANT_TIMEOUT), Mockito.any());
        Mockito.verify(orderDeadlineRepository, Mockito.never())
                .reschedule(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(metricsService).recordFulfilmentTimeout("PAYMENT", "cancelled");
        Mockito.verify(metricsService).recordFulfilmentTimeout("ACCEPT", "cancelled");
    }


    @Test
    void completeDeliveredOrder_fromAccepted_shouldWriteEachStepWithACompareAndSet() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("ACCEPTED", RESTAURANT_ID));
        stubCompareAndSet();

        orderService.completeDeliveredOrder(ORDER_ID, OrderActor.rider(4L));

        // The second step expects the status the first one wrote; the order keeps its (empty) cancel reason.
        InOrder steps = Mockito.inOrder(orderRepository);
        steps.verify(orderRepository).lockById(ORDER_ID);
        steps.verify(orderRepository).compareAndSetStatus(
                Mockito.eq(ORDER_ID), Mockito.eq("ACCEPTED"), Mockito.eq("PREPARING"), Mockito.isNull(),
                Mockito.any(LocalDateTime.class));
        steps.verify(orderRepository).compareAndSetStatus(
                Mockito.eq(ORDER_ID), Mockito.eq("PREPARING"), Mockito.eq("COMPLETED"), Mockito.isNull(),
                Mockito.any(LocalDateTime.class));
        Mockito.verifyNoMoreInteractions(orderRepository);
    }


    @Test
    void everyCommand_shouldRequireTheActor() {
        // No overload may default the actor: a merchant or rider command that forgot it would record SYSTEM.
        for (Method command : OrderLifecycleCommands.class.getMethods()) {
            if (Modifier.isStatic(command.getModifiers())) {
                continue;
            }
            Assertions.assertTrue(
                    Arrays.asList(command.getParameterTypes()).contains(OrderActor.class),
                    command + " does not take the OrderActor"
            );
        }
    }


    @Test
    void repeatedAndRefusedCommands_shouldRecordNoHistory() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(
                order("ACCEPTED", RESTAURANT_ID),
                order("ACCEPTED", RESTAURANT_ID),
                order("COMPLETED", RESTAURANT_ID),
                order("CANCELLED", RESTAURANT_ID)
        );

        // A repeated accept, a timeout that lost the race, a duplicate delivery, a delivery of a cancelled order.
        orderService.transitionRestaurantOrder(
                ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.ACCEPTED, null, OrderActor.merchant(40L));
        orderService.cancelIfStillIn(ORDER_ID, OrderStatus.PAID, OrderLifecycleCommands.MERCHANT_TIMEOUT, OrderActor.system());
        orderService.completeDeliveredOrder(ORDER_ID, OrderActor.rider(4L));
        orderService.completeDeliveredOrder(ORDER_ID, OrderActor.rider(4L));

        Mockito.verifyNoInteractions(orderStatusHistoryService);
        Mockito.verify(orderRepository, Mockito.never())
                .compareAndSetStatus(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }


    @Test
    void cancelIfStillIn_shouldRecordTheTimeoutAsSystemWithItsReason() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("PLACED", RESTAURANT_ID));
        stubCompareAndSet();

        orderService.cancelIfStillIn(ORDER_ID, OrderStatus.PLACED, OrderLifecycleCommands.PAYMENT_TIMEOUT, OrderActor.system());

        Mockito.verify(orderStatusHistoryService).record(
                Mockito.eq(ORDER_ID),
                Mockito.eq(OrderStatus.PLACED),
                Mockito.eq(OrderStatus.CANCELLED),
                Mockito.eq(OrderActor.system()),
                Mockito.eq(OrderLifecycleCommands.PAYMENT_TIMEOUT),
                Mockito.any(LocalDateTime.class)
        );
    }


    @Test
    void completeDeliveredOrder_fromAccepted_shouldRecordBothStepsForTheRider() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("ACCEPTED", RESTAURANT_ID));
        stubCompareAndSet();

        orderService.completeDeliveredOrder(ORDER_ID, OrderActor.rider(4L));

        InOrder history = Mockito.inOrder(orderStatusHistoryService);
        history.verify(orderStatusHistoryService).record(
                Mockito.eq(ORDER_ID),
                Mockito.eq(OrderStatus.ACCEPTED),
                Mockito.eq(OrderStatus.PREPARING),
                Mockito.eq(OrderActor.rider(4L)),
                Mockito.eq("DELIVERED"),
                Mockito.any(LocalDateTime.class)
        );
        history.verify(orderStatusHistoryService).record(
                Mockito.eq(ORDER_ID),
                Mockito.eq(OrderStatus.PREPARING),
                Mockito.eq(OrderStatus.COMPLETED),
                Mockito.eq(OrderActor.rider(4L)),
                Mockito.eq("DELIVERED"),
                Mockito.any(LocalDateTime.class)
        );
        history.verifyNoMoreInteractions();
    }


    @Test
    void cancelOrder_byCustomer_shouldRecordTheCustomer() {
        Mockito.when(orderRepository.lockByIdAndCustomerId(ORDER_ID, 1L)).thenReturn(order("PAID", RESTAURANT_ID));
        stubCompareAndSet();

        orderService.cancelOrder(1L, ORDER_ID);

        Mockito.verify(orderStatusHistoryService).record(
                Mockito.eq(ORDER_ID),
                Mockito.eq(OrderStatus.PAID),
                Mockito.eq(OrderStatus.CANCELLED),
                Mockito.eq(OrderActor.customer(1L)),
                Mockito.eq(OrderLifecycleCommands.CUSTOMER_CANCELLED),
                Mockito.any(LocalDateTime.class)
        );
    }


    /**
     * ADR 0002 D12: the merchant's reject and the accept timeout cancel a paid order, and each opens its refund in the
     * command's transaction with the order as written (its own cancel reason) at the time of the cancel.
     */
    @Test
    void merchantRejectAndAcceptTimeout_shouldOpenTheRefundOfThePaidOrder() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(
                order("PAID", RESTAURANT_ID),
                order("PAID", RESTAURANT_ID)
        );
        stubCompareAndSet();

        orderService.transitionRestaurantOrder(ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.CANCELLED,
                "MERCHANT_REJECTED: sold out", OrderActor.merchant(40L));
        orderService.cancelIfStillIn(
                ORDER_ID, OrderStatus.PAID, OrderLifecycleCommands.MERCHANT_TIMEOUT, OrderActor.system());

        ArgumentCaptor<LocalDateTime> cancelledAt = ArgumentCaptor.forClass(LocalDateTime.class);
        Mockito.verify(orderRepository, Mockito.times(2)).compareAndSetStatus(
                Mockito.eq(ORDER_ID), Mockito.eq("PAID"), Mockito.eq("CANCELLED"), Mockito.any(),
                cancelledAt.capture());
        InOrder refunds = Mockito.inOrder(cancellationRefunds);
        refunds.verify(cancellationRefunds).refundCancelledOrder(
                order("PAID", RESTAURANT_ID).withStatus(
                        "CANCELLED", "MERCHANT_REJECTED: sold out", cancelledAt.getAllValues().get(0)),
                cancelledAt.getAllValues().get(0)
        );
        refunds.verify(cancellationRefunds).refundCancelledOrder(
                order("PAID", RESTAURANT_ID).withStatus(
                        "CANCELLED", OrderLifecycleCommands.MERCHANT_TIMEOUT, cancelledAt.getAllValues().get(1)),
                cancelledAt.getAllValues().get(1)
        );
        Mockito.verifyNoMoreInteractions(cancellationRefunds);
    }


    /**
     * The commands that do not cancel a paid order open no refund: accept, preparing, the payment timeout of an unpaid
     * order and the completion of a delivered one.
     */
    @Test
    void commandsThatDoNotCancelAPaidOrder_shouldOpenNoRefund() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(
                order("PAID", RESTAURANT_ID),
                order("ACCEPTED", RESTAURANT_ID),
                order("PLACED", RESTAURANT_ID),
                order("ACCEPTED", RESTAURANT_ID)
        );
        stubCompareAndSet();

        orderService.transitionRestaurantOrder(
                ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.ACCEPTED, null, OrderActor.merchant(40L));
        orderService.transitionRestaurantOrder(
                ORDER_ID, RESTAURANT_ID, OrderStatus.ACCEPTED, OrderStatus.PREPARING, null, OrderActor.merchant(40L));
        Assertions.assertTrue(orderService.cancelIfStillIn(
                ORDER_ID, OrderStatus.PLACED, OrderLifecycleCommands.PAYMENT_TIMEOUT, OrderActor.system()).isPresent());
        Assertions.assertEquals(DeliveryCompletion.COMPLETED,
                orderService.completeDeliveredOrder(ORDER_ID, OrderActor.rider(4L)));

        Mockito.verifyNoInteractions(cancellationRefunds);
    }


    /**
     * Every compare-and-set of the order's status changes its one row, as it does under the row lock.
     */
    private void stubCompareAndSet() {
        Mockito.when(orderRepository.compareAndSetStatus(
                Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.any(),
                Mockito.any(LocalDateTime.class)
        )).thenReturn(1);
    }


    private OrderEntity order(String status, Long restaurantId) {
        return order(status, restaurantId, null);
    }


    private OrderEntity order(String status, Long restaurantId, String cancelReason) {
        return new OrderEntity(
                ORDER_ID,
                1L,
                Money.of("19.00", "USD"),
                status,
                LocalDateTime.of(2026, 4, 4, 12, 0),
                restaurantId,
                cancelReason
        );
    }
}
