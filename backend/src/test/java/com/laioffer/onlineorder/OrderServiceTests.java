package com.laioffer.onlineorder;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.IdempotencyRequestRepository;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import com.laioffer.onlineorder.service.OrderService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.time.LocalDateTime;
import java.util.List;


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
    private ApplicationMetricsService metricsService;

    private OrderService orderService;


    @BeforeEach
    void setup() {
        orderService = new OrderService(
                orderRepository,
                orderHistoryItemRepository,
                orderEventOutboxService,
                metricsService,
                idempotencyRequestRepository
        );
    }


    @Test
    void transitionOrderStatus_shouldAdvanceThroughAllowedState() {
        OrderEntity paidOrder = new OrderEntity(5L, 1L, 19.0, "PAID", LocalDateTime.of(2026, 4, 4, 12, 0));
        OrderEntity acceptedOrder = new OrderEntity(5L, 1L, 19.0, "ACCEPTED", paidOrder.createdAt());
        OrderHistoryItemEntity historyItem = new OrderHistoryItemEntity(
                6L,
                5L,
                3L,
                2L,
                19.0,
                1,
                "Burger",
                "Classic burger",
                "image"
        );

        Mockito.when(orderRepository.lockById(5L)).thenReturn(paidOrder);
        Mockito.when(orderRepository.save(Mockito.any(OrderEntity.class))).thenReturn(acceptedOrder);
        Mockito.when(orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(5L)).thenReturn(List.of(historyItem));

        OrderDto result = orderService.transitionOrderStatus(5L, "ACCEPTED");

        Assertions.assertEquals("ACCEPTED", result.status());
        Mockito.verify(orderEventOutboxService).enqueueOrderEvent(Mockito.eq(acceptedOrder), Mockito.anyList(), Mockito.isNull());
        Mockito.verify(metricsService).recordOrderTransition("PAID", "ACCEPTED");
    }


    @Test
    void cancelOrder_shouldRejectInvalidTransition() {
        OrderEntity completedOrder = new OrderEntity(5L, 1L, 19.0, "COMPLETED", LocalDateTime.of(2026, 4, 4, 12, 0));

        Mockito.when(orderRepository.lockByIdAndCustomerId(5L, 1L)).thenReturn(completedOrder);

        Assertions.assertThrows(ConflictException.class, () -> orderService.cancelOrder(1L, 5L));
        Mockito.verify(orderRepository, Mockito.never()).save(Mockito.any());
    }
}
