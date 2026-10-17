package com.laioffer.onlineorder;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderNotificationEntity;
import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import com.laioffer.onlineorder.messaging.DeliveryEventPayload;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventPayload;
import com.laioffer.onlineorder.messaging.OrderEventItem;
import com.laioffer.onlineorder.model.OrderHistoryItemDto;
import com.laioffer.onlineorder.model.OrderNotificationDto;
import com.laioffer.onlineorder.repository.OrderNotificationRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.service.OrderNotificationService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


@ExtendWith(MockitoExtension.class)
class OrderNotificationServiceTests {

    @org.mockito.Mock
    private OrderNotificationRepository orderNotificationRepository;

    // Unstubbed it knows no order numbers, and the messages fall back to the internal id.
    @org.mockito.Mock
    private OrderRepository orderRepository;


    @Test
    void recordOrderEvent_shouldInsertNotificationWithConflictSafeRepositoryMethod() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository);
        OrderEventEnvelope payload = buildEvent("evt-1", "order.created", 9L, 4L, "PLACED");

        Mockito.when(orderNotificationRepository.insertIfAbsent(
                Mockito.eq(9L),
                Mockito.eq(4L),
                Mockito.eq("order.created"),
                Mockito.eq("Order placed"),
                Mockito.contains("Order #9 was placed"),
                Mockito.any(LocalDateTime.class)
        )).thenReturn(1);

        service.recordOrderEvent(payload);

        Mockito.verify(orderNotificationRepository).insertIfAbsent(
                Mockito.eq(9L),
                Mockito.eq(4L),
                Mockito.eq("order.created"),
                Mockito.eq("Order placed"),
                Mockito.contains("Order #9 was placed"),
                Mockito.any(LocalDateTime.class)
        );
        Mockito.verify(orderNotificationRepository, Mockito.never()).save(Mockito.any());
    }


    @Test
    void recordOrderEvent_whenDuplicateNotificationInsertIsSkipped_shouldNotThrow() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository);
        OrderEventEnvelope payload = buildEvent("evt-2", "order.paid", 9L, 4L, "PAID");

        Mockito.when(orderNotificationRepository.insertIfAbsent(
                Mockito.anyLong(),
                Mockito.anyLong(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(LocalDateTime.class)
        )).thenReturn(0);

        Assertions.assertDoesNotThrow(() -> service.recordOrderEvent(payload));
    }


    @Test
    void recordOrderEvent_whenRepositoryReportsNonDuplicateIntegrityFailure_shouldPropagate() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository);
        OrderEventEnvelope payload = buildEvent("evt-3", "order.paid", 9L, 4L, "PAID");

        Mockito.when(orderNotificationRepository.insertIfAbsent(
                Mockito.anyLong(),
                Mockito.anyLong(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(LocalDateTime.class)
        )).thenThrow(new DataIntegrityViolationException("foreign key violation"));

        Assertions.assertThrows(DataIntegrityViolationException.class, () -> service.recordOrderEvent(payload));
    }


    @Test
    void recordOrderEvent_whenTheMerchantTimedOut_shouldSaySoToTheCustomer() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository);

        service.recordOrderEvent(cancelledEvent("MERCHANT_TIMEOUT"));

        Mockito.verify(orderNotificationRepository).insertIfAbsent(
                Mockito.eq(9L),
                Mockito.eq(4L),
                Mockito.eq("order.cancelled"),
                Mockito.eq("Order cancelled: the restaurant did not respond"),
                Mockito.contains("did not accept it in time"),
                Mockito.any(LocalDateTime.class)
        );
    }


    @Test
    void recordOrderEvent_whenTheMerchantRejected_shouldPassOnTheirReason() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository);

        service.recordOrderEvent(cancelledEvent("MERCHANT_REJECTED: Out of buns"));

        Mockito.verify(orderNotificationRepository).insertIfAbsent(
                Mockito.eq(9L),
                Mockito.eq(4L),
                Mockito.eq("order.cancelled"),
                Mockito.eq("Order rejected by the restaurant"),
                Mockito.eq("The restaurant could not take order #9: Out of buns. Please contact support about your payment."),
                Mockito.any(LocalDateTime.class)
        );
    }


    @Test
    void recordOrderEvent_whenCancelledWithoutReason_shouldKeepTheGenericMessage() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository);

        service.recordOrderEvent(buildEvent("evt-c", "order.cancelled", 9L, 4L, "CANCELLED"));

        Mockito.verify(orderNotificationRepository).insertIfAbsent(
                Mockito.eq(9L), Mockito.eq(4L), Mockito.eq("order.cancelled"), Mockito.eq("Order cancelled"),
                Mockito.eq("Order #9 was cancelled."), Mockito.any(LocalDateTime.class));
    }


    @Test
    void recordDeliveryEvent_shouldTellTheCustomerAboutTheRiderAndTheDelivery() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository);

        service.recordDeliveryEvent(deliveryEvent("delivery.assigned"));
        service.recordDeliveryEvent(deliveryEvent("delivery.delivered"));
        service.recordDeliveryEvent(deliveryEvent("delivery.cancelled"));

        Mockito.verify(orderNotificationRepository).insertIfAbsent(
                Mockito.eq(9L), Mockito.eq(4L), Mockito.eq("delivery.assigned"), Mockito.eq("Rider assigned"),
                Mockito.eq("Demo Rider Alice will deliver order #9."), Mockito.any(LocalDateTime.class));
        Mockito.verify(orderNotificationRepository).insertIfAbsent(
                Mockito.eq(9L), Mockito.eq(4L), Mockito.eq("delivery.delivered"), Mockito.eq("Order delivered"),
                Mockito.contains("was delivered"), Mockito.any(LocalDateTime.class));
        Mockito.verifyNoMoreInteractions(orderNotificationRepository);
    }


    @Test
    void recordOrderAndDeliveryEvents_shouldNameTheOrderByItsPublicNumber() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository);
        Mockito.when(orderRepository.findOrderNoById(9L)).thenReturn(Optional.of(7_391_022_331_904_512_345L));

        service.recordOrderEvent(buildEvent("evt-n", "order.created", 9L, 4L, "PLACED"));
        service.recordDeliveryEvent(deliveryEvent("delivery.picked_up"));

        Mockito.verify(orderNotificationRepository).insertIfAbsent(
                Mockito.eq(9L), Mockito.eq(4L), Mockito.eq("order.created"), Mockito.eq("Order placed"),
                Mockito.eq("Order #7391022331904512345 was placed and sent for processing."),
                Mockito.any(LocalDateTime.class));
        Mockito.verify(orderNotificationRepository).insertIfAbsent(
                Mockito.eq(9L), Mockito.eq(4L), Mockito.eq("delivery.picked_up"), Mockito.eq("Order on the way"),
                Mockito.eq("Demo Rider Alice picked up order #7391022331904512345 and is on the way."),
                Mockito.any(LocalDateTime.class));
    }


    @Test
    void getNotificationsByCustomerId_shouldCarryEachOrdersPublicNumber() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository);
        LocalDateTime createdAt = LocalDateTime.of(2026, 4, 4, 12, 0);
        Mockito.when(orderNotificationRepository.findPageByCustomerIdOrderByCreatedAtDesc(4L, 50)).thenReturn(List.of(
                new OrderNotificationEntity(2L, 9L, 4L, "order.paid", "Payment confirmed", "m", createdAt),
                new OrderNotificationEntity(1L, 9L, 4L, "order.created", "Order placed", "m", createdAt)
        ));
        Mockito.when(orderRepository.findAllById(List.of(9L))).thenReturn(List.of(new OrderEntity(
                9L, 7_391_022_331_904_512_345L, 4L, amount("22.0"), "PAID", createdAt, 2L, null, createdAt, "USD")));

        List<OrderNotificationDto> notifications = service.getNotificationsByCustomerId(4L);

        Assertions.assertEquals(List.of(7_391_022_331_904_512_345L, 7_391_022_331_904_512_345L),
                notifications.stream().map(OrderNotificationDto::orderNo).toList());
        Assertions.assertEquals(List.of(9L, 9L), notifications.stream().map(OrderNotificationDto::orderId).toList());
    }


    @Test
    void getNotificationsByCustomerId_withoutNotifications_shouldNotLookUpOrders() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository);
        Mockito.when(orderNotificationRepository.findPageByCustomerIdOrderByCreatedAtDesc(4L, 50)).thenReturn(List.of());

        Assertions.assertEquals(List.of(), service.getNotificationsByCustomerId(4L));
        Mockito.verifyNoInteractions(orderRepository);
    }


    private DeliveryEventEnvelope deliveryEvent(String eventType) {
        return new DeliveryEventEnvelope(
                "evt-" + eventType, 1, eventType, "DELIVERY", 7L, LocalDateTime.of(2026, 4, 4, 12, 30),
                "corr", null, new DeliveryEventPayload(9L, 4L, 2L, 3L, "Demo Rider Alice", "ASSIGNED"));
    }


    private OrderEventEnvelope cancelledEvent(String reason) {
        OrderEventEnvelope event = buildEvent("evt-x", "order.cancelled", 9L, 4L, "CANCELLED");
        OrderEventPayload data = event.data();
        return new OrderEventEnvelope(
                event.eventId(), event.eventVersion(), event.eventType(), event.aggregateType(), event.aggregateId(),
                event.occurredAt(), event.correlationId(), event.idempotencyKey(),
                new OrderEventPayload(data.customerId(), data.status(), data.totalPrice(), data.createdAt(), data.items(),
                        2L, reason));
    }


    private OrderEventEnvelope buildEvent(String eventId, String eventType, Long orderId, Long customerId, String status) {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 4, 4, 12, 0);
        return new OrderEventEnvelope(
                eventId,
                1,
                eventType,
                "ORDER",
                orderId,
                occurredAt,
                "corr-" + eventId,
                "idem-" + eventId,
                new OrderEventPayload(
                        customerId,
                        status,
                        amount("22.0"),
                        occurredAt,
                        List.of(new OrderEventItem(1L, orderId, 2L, amount("10.0"), null, 1, "Burger", "Classic", "image"))
                )
        );
    }


    private BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
