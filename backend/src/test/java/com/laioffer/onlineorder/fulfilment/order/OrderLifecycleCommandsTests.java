package com.laioffer.onlineorder.fulfilment.order;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands.DeliveryCompletion;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands.RestaurantOrderTransition;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.IdempotencyRequestRepository;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.service.IdempotencyRequestInitializer;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import com.laioffer.onlineorder.service.OrderService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    private OrderService orderService;


    @BeforeEach
    void setup() {
        orderService = new OrderService(
                orderRepository,
                orderHistoryItemRepository,
                orderEventOutboxService,
                metricsService,
                idempotencyRequestRepository,
                idempotencyRequestInitializer
        );
    }


    @Test
    void transitionRestaurantOrder_shouldLockTransitionAndWriteTheOrderEvent() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("PAID", RESTAURANT_ID));
        Mockito.when(orderRepository.save(Mockito.any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RestaurantOrderTransition transition = orderService.transitionRestaurantOrder(
                ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.ACCEPTED, null);
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
                () -> orderService.transitionRestaurantOrder(ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.ACCEPTED, null)
        );

        Mockito.verify(orderRepository, Mockito.never()).save(Mockito.any());
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void transitionRestaurantOrder_whenOrderHasNoRestaurant_shouldReturnNotFound() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("PAID", null));

        Assertions.assertThrows(
                ResourceNotFoundException.class,
                () -> orderService.transitionRestaurantOrder(ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.ACCEPTED, null)
        );
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void transitionRestaurantOrder_whenOrderWasTimedOut_shouldConflictWithoutChanges() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("CANCELLED", RESTAURANT_ID));

        ConflictException conflict = Assertions.assertThrows(
                ConflictException.class,
                () -> orderService.transitionRestaurantOrder(ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.ACCEPTED, null)
        );

        Assertions.assertEquals(OrderLifecycleCommands.ORDER_STATUS_CONFLICT, conflict.getCode());
        Mockito.verify(orderRepository, Mockito.never()).save(Mockito.any());
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void transitionRestaurantOrder_whenAlreadyInTargetStatus_shouldReturnTheOrderUnchanged() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("ACCEPTED", RESTAURANT_ID));

        RestaurantOrderTransition transition = orderService.transitionRestaurantOrder(
                ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.ACCEPTED, null);

        Assertions.assertFalse(transition.changed());
        Assertions.assertEquals("ACCEPTED", transition.order().status());
        Mockito.verify(orderRepository, Mockito.never()).save(Mockito.any());
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void transitionRestaurantOrder_whenRejectingAnOrderTheCustomerCancelled_shouldConflict() {
        Mockito.when(orderRepository.lockById(ORDER_ID))
                .thenReturn(order("CANCELLED", RESTAURANT_ID, OrderLifecycleCommands.CUSTOMER_CANCELLED));

        ConflictException conflict = Assertions.assertThrows(
                ConflictException.class,
                () -> orderService.transitionRestaurantOrder(
                        ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.CANCELLED, "MERCHANT_REJECTED: sold out")
        );

        Assertions.assertEquals(OrderLifecycleCommands.ORDER_STATUS_CONFLICT, conflict.getCode());
        Assertions.assertTrue(conflict.getMessage().contains("CUSTOMER_CANCELLED"), conflict.getMessage());
        Mockito.verify(orderRepository, Mockito.never()).save(Mockito.any());
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void transitionRestaurantOrder_whenRejectingAnOrderTheAcceptTimeoutCancelled_shouldConflict() {
        Mockito.when(orderRepository.lockById(ORDER_ID))
                .thenReturn(order("CANCELLED", RESTAURANT_ID, OrderLifecycleCommands.MERCHANT_TIMEOUT));

        Assertions.assertThrows(
                ConflictException.class,
                () -> orderService.transitionRestaurantOrder(
                        ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.CANCELLED, "MERCHANT_REJECTED: sold out")
        );
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void transitionRestaurantOrder_whenTheRejectIsRepeated_shouldReturnTheFirstRejectionUnchanged() {
        Mockito.when(orderRepository.lockById(ORDER_ID))
                .thenReturn(order("CANCELLED", RESTAURANT_ID, "MERCHANT_REJECTED: out of buns"));

        RestaurantOrderTransition transition = orderService.transitionRestaurantOrder(
                ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.CANCELLED, "MERCHANT_REJECTED: sold out");

        Assertions.assertFalse(transition.changed());
        Assertions.assertEquals("MERCHANT_REJECTED: out of buns", transition.order().cancelReason());
        Mockito.verify(orderRepository, Mockito.never()).save(Mockito.any());
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void transitionRestaurantOrder_whenRejecting_shouldStoreTheCancelReason() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("PAID", RESTAURANT_ID));
        Mockito.when(orderRepository.save(Mockito.any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderDto rejected = orderService.transitionRestaurantOrder(
                ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.CANCELLED, "MERCHANT_REJECTED: sold out").order();

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
        Mockito.when(orderRepository.save(Mockito.any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<OrderDto> cancelled = orderService.cancelIfStillIn(
                ORDER_ID, OrderStatus.PAID, OrderLifecycleCommands.MERCHANT_TIMEOUT);

        Assertions.assertTrue(cancelled.isPresent());
        Assertions.assertEquals("CANCELLED", cancelled.get().status());
        Assertions.assertEquals(OrderLifecycleCommands.MERCHANT_TIMEOUT, cancelled.get().cancelReason());
        Mockito.verify(metricsService).recordOrderTransition("PAID", "CANCELLED");
    }


    @Test
    void cancelIfStillIn_whenOrderWasAcceptedMeanwhile_shouldLeaveItAlone() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("ACCEPTED", RESTAURANT_ID));

        Optional<OrderDto> cancelled = orderService.cancelIfStillIn(
                ORDER_ID, OrderStatus.PAID, OrderLifecycleCommands.MERCHANT_TIMEOUT);

        Assertions.assertTrue(cancelled.isEmpty());
        Mockito.verify(orderRepository, Mockito.never()).save(Mockito.any());
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void cancelIfStillIn_whenOrderDoesNotExist_shouldDoNothing() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(null);

        Assertions.assertTrue(orderService.cancelIfStillIn(
                ORDER_ID, OrderStatus.PLACED, OrderLifecycleCommands.PAYMENT_TIMEOUT).isEmpty());
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void completeDeliveredOrder_fromPreparing_shouldComplete() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("PREPARING", RESTAURANT_ID));
        Mockito.when(orderRepository.save(Mockito.any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Assertions.assertEquals(DeliveryCompletion.COMPLETED, orderService.completeDeliveredOrder(ORDER_ID));

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
        Mockito.when(orderRepository.save(Mockito.any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Assertions.assertEquals(DeliveryCompletion.COMPLETED, orderService.completeDeliveredOrder(ORDER_ID));

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

        Assertions.assertEquals(DeliveryCompletion.ALREADY_COMPLETED, orderService.completeDeliveredOrder(ORDER_ID));
        Mockito.verify(orderRepository, Mockito.never()).save(Mockito.any());
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void completeDeliveredOrder_whenOrderWasCancelled_shouldIgnoreTheDelivery() {
        Mockito.when(orderRepository.lockById(ORDER_ID)).thenReturn(order("CANCELLED", RESTAURANT_ID));

        Assertions.assertEquals(DeliveryCompletion.IGNORED, orderService.completeDeliveredOrder(ORDER_ID));
        Mockito.verify(orderRepository, Mockito.never()).save(Mockito.any());
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void cancelOrder_byCustomer_shouldRecordCustomerCancelledReason() {
        Mockito.when(orderRepository.lockByIdAndCustomerId(ORDER_ID, 1L)).thenReturn(order("PAID", RESTAURANT_ID));
        Mockito.when(orderRepository.save(Mockito.any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderDto cancelled = orderService.cancelOrder(1L, ORDER_ID);

        Assertions.assertEquals(OrderLifecycleCommands.CUSTOMER_CANCELLED, cancelled.cancelReason());
    }


    private OrderEntity order(String status, Long restaurantId) {
        return order(status, restaurantId, null);
    }


    private OrderEntity order(String status, Long restaurantId, String cancelReason) {
        return new OrderEntity(
                ORDER_ID,
                1L,
                new BigDecimal("19.00"),
                status,
                LocalDateTime.of(2026, 4, 4, 12, 0),
                restaurantId,
                cancelReason
        );
    }
}
