package com.laioffer.onlineorder;


import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import com.laioffer.onlineorder.messaging.DeliveryEventPayload;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventPayload;
import com.laioffer.onlineorder.messaging.OrderEventItem;
import com.laioffer.onlineorder.repository.OrderNotificationRepository;
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


@ExtendWith(MockitoExtension.class)
class OrderNotificationServiceTests {

    @org.mockito.Mock
    private OrderNotificationRepository orderNotificationRepository;


    @Test
    void recordOrderEvent_shouldInsertNotificationWithConflictSafeRepositoryMethod() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository);
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
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository);
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
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository);
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
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository);

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
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository);

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
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository);

        service.recordOrderEvent(buildEvent("evt-c", "order.cancelled", 9L, 4L, "CANCELLED"));

        Mockito.verify(orderNotificationRepository).insertIfAbsent(
                Mockito.eq(9L), Mockito.eq(4L), Mockito.eq("order.cancelled"), Mockito.eq("Order cancelled"),
                Mockito.eq("Order #9 was cancelled."), Mockito.any(LocalDateTime.class));
    }


    @Test
    void recordDeliveryEvent_shouldTellTheCustomerAboutTheRiderAndTheDelivery() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository);

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
