package com.laioffer.onlineorder;


import com.laioffer.onlineorder.entity.IdempotencyRequestEntity;
import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.fulfilment.order.OrderActor;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.model.OrderStatusHistoryDto;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.IdempotencyRequestRepository;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.service.IdempotencyRequestInitializer;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import com.laioffer.onlineorder.service.OrderService;
import com.laioffer.onlineorder.service.OrderStatusHistoryService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;


@ExtendWith(MockitoExtension.class)
class OrderServiceTests {

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
                orderStatusHistoryService
        );
    }


    @Test
    void transitionOrderStatus_shouldAdvanceThroughAllowedState() {
        OrderEntity paidOrder = new OrderEntity(5L, 1L, amount("19.0"), "PAID", LocalDateTime.of(2026, 4, 4, 12, 0));
        OrderEntity acceptedOrder = new OrderEntity(5L, 1L, amount("19.0"), "ACCEPTED", paidOrder.createdAt());
        OrderHistoryItemEntity historyItem = new OrderHistoryItemEntity(
                6L,
                5L,
                3L,
                2L,
                amount("19.0"),
                1,
                "Burger",
                "Classic burger",
                "image"
        );

        Mockito.when(orderRepository.lockById(5L)).thenReturn(paidOrder);
        Mockito.when(orderRepository.save(Mockito.any(OrderEntity.class))).thenReturn(acceptedOrder);
        Mockito.when(orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(5L)).thenReturn(List.of(historyItem));

        LocalDateTime beforeTransition = LocalDateTime.now();
        OrderDto result = orderService.transitionOrderStatus(5L, "ACCEPTED");
        LocalDateTime afterTransition = LocalDateTime.now();

        Assertions.assertEquals("ACCEPTED", result.status());
        ArgumentCaptor<LocalDateTime> occurredAt = ArgumentCaptor.forClass(LocalDateTime.class);
        Mockito.verify(orderEventOutboxService).enqueueOrderEvent(
                Mockito.eq(acceptedOrder),
                Mockito.anyList(),
                Mockito.isNull(),
                occurredAt.capture()
        );
        Assertions.assertNotEquals(paidOrder.createdAt(), occurredAt.getValue());
        Assertions.assertFalse(occurredAt.getValue().isBefore(beforeTransition));
        Assertions.assertFalse(occurredAt.getValue().isAfter(afterTransition));
        Mockito.verify(metricsService).recordOrderTransition("PAID", "ACCEPTED");
    }


    @Test
    void cancelOrder_shouldRejectInvalidTransition() {
        OrderEntity completedOrder = new OrderEntity(5L, 1L, amount("19.0"), "COMPLETED", LocalDateTime.of(2026, 4, 4, 12, 0));

        Mockito.when(orderRepository.lockByIdAndCustomerId(5L, 1L)).thenReturn(completedOrder);

        Assertions.assertThrows(ConflictException.class, () -> orderService.cancelOrder(1L, 5L));
        Mockito.verify(orderRepository, Mockito.never()).save(Mockito.any());
    }


    @Test
    void transitionOrderStatus_withoutAnActor_shouldRecordSystemInTheHistory() {
        OrderEntity paidOrder = new OrderEntity(5L, 1L, amount("19.0"), "PAID", LocalDateTime.of(2026, 4, 4, 12, 0));
        Mockito.when(orderRepository.lockById(5L)).thenReturn(paidOrder);
        Mockito.when(orderRepository.save(Mockito.any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        orderService.transitionOrderStatus(5L, "ACCEPTED");

        Mockito.verify(orderStatusHistoryService).record(
                Mockito.eq(5L),
                Mockito.eq(OrderStatus.PAID),
                Mockito.eq(OrderStatus.ACCEPTED),
                Mockito.eq(OrderActor.system()),
                Mockito.isNull(),
                Mockito.any(LocalDateTime.class)
        );
    }


    @Test
    void idempotentTransitionOrderStatus_shouldRecordTheAdminInTheHistory() {
        OrderEntity paidOrder = new OrderEntity(5L, 1L, amount("19.0"), "PAID", LocalDateTime.of(2026, 4, 4, 12, 0));
        stubFirstIdempotentAttempt(9L, "admin-key");
        Mockito.when(orderRepository.lockById(5L)).thenReturn(paidOrder);
        Mockito.when(orderRepository.save(Mockito.any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderDto result = orderService.idempotentTransitionOrderStatus(9L, 5L, "ACCEPTED", "admin-key");

        Assertions.assertEquals("ACCEPTED", result.status());
        Mockito.verify(orderStatusHistoryService).record(
                Mockito.eq(5L),
                Mockito.eq(OrderStatus.PAID),
                Mockito.eq(OrderStatus.ACCEPTED),
                Mockito.eq(OrderActor.admin(9L)),
                Mockito.isNull(),
                Mockito.any(LocalDateTime.class)
        );
    }


    @Test
    void idempotentTransitionOrderStatus_toTheCurrentStatus_shouldRecordNoHistory() {
        OrderEntity acceptedOrder = new OrderEntity(5L, 1L, amount("19.0"), "ACCEPTED", LocalDateTime.of(2026, 4, 4, 12, 0));
        stubFirstIdempotentAttempt(9L, "same-status-key");
        Mockito.when(orderRepository.lockById(5L)).thenReturn(acceptedOrder);

        orderService.idempotentTransitionOrderStatus(9L, 5L, "ACCEPTED", "same-status-key");

        Mockito.verifyNoInteractions(orderStatusHistoryService);
        Mockito.verify(orderRepository, Mockito.never()).save(Mockito.any());
    }


    @Test
    void getStatusHistory_shouldOnlyServeTheOwnerAndAdmins() {
        List<OrderStatusHistoryDto> history = List.of(new OrderStatusHistoryDto(
                null, "PLACED", "CUSTOMER", 1L, null, LocalDateTime.of(2026, 4, 4, 12, 0)));
        OrderEntity order = new OrderEntity(5L, 1L, amount("19.0"), "PLACED", LocalDateTime.of(2026, 4, 4, 12, 0));
        Mockito.when(orderRepository.findByIdAndCustomerId(5L, 1L)).thenReturn(Optional.of(order));
        Mockito.when(orderRepository.findByIdAndCustomerId(5L, 2L)).thenReturn(Optional.empty());
        Mockito.when(orderRepository.existsById(5L)).thenReturn(true);
        Mockito.when(orderRepository.existsById(6L)).thenReturn(false);
        Mockito.when(orderStatusHistoryService.getHistory(5L)).thenReturn(history);

        Assertions.assertEquals(history, orderService.getStatusHistory(5L, 1L, false));
        Assertions.assertEquals(history, orderService.getStatusHistory(5L, 3L, true));
        Assertions.assertThrows(ResourceNotFoundException.class, () -> orderService.getStatusHistory(5L, 2L, false));
        Assertions.assertThrows(ResourceNotFoundException.class, () -> orderService.getStatusHistory(6L, 3L, true));
        Mockito.verify(orderStatusHistoryService, Mockito.times(2)).getHistory(5L);
    }


    /**
     * The first attempt with this key: the PROCESSING row is inserted and then read back with the hash it was
     * inserted with.
     */
    private void stubFirstIdempotentAttempt(Long actorCustomerId, String key) {
        AtomicReference<String> insertedHash = new AtomicReference<>();
        Mockito.when(idempotencyRequestInitializer.insertIfAbsent(
                Mockito.eq(actorCustomerId), Mockito.anyString(), Mockito.eq(key), Mockito.anyString(),
                Mockito.eq("PROCESSING"), Mockito.any(), Mockito.any()
        )).thenAnswer(invocation -> {
            insertedHash.set(invocation.getArgument(3));
            return 1;
        });
        Mockito.when(idempotencyRequestRepository.lockByCustomerIdAndScopeAndIdempotencyKey(
                Mockito.eq(actorCustomerId), Mockito.anyString(), Mockito.eq(key)
        )).thenAnswer(invocation -> new IdempotencyRequestEntity(
                77L, actorCustomerId, invocation.getArgument(1), key, insertedHash.get(), "PROCESSING", null,
                LocalDateTime.now(), LocalDateTime.now()));
    }


    private BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
