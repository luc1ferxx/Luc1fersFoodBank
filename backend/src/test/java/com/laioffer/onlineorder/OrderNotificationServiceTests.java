package com.laioffer.onlineorder;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderNotificationEntity;
import com.laioffer.onlineorder.entity.OrderPaymentEntity;
import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import com.laioffer.onlineorder.messaging.DeliveryEventPayload;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventPayload;
import com.laioffer.onlineorder.messaging.OrderEventItem;
import com.laioffer.onlineorder.model.OrderHistoryItemDto;
import com.laioffer.onlineorder.model.OrderNotificationDto;
import com.laioffer.onlineorder.repository.OrderNotificationRepository;
import com.laioffer.onlineorder.repository.OrderPaymentRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.service.OrderNotificationService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

    // Unstubbed it knows no captured payments: a cancelled paid order points the customer to support.
    @org.mockito.Mock
    private OrderPaymentRepository orderPaymentRepository;


    @Test
    void recordOrderEvent_shouldInsertNotificationWithConflictSafeRepositoryMethod() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository, orderPaymentRepository, 50);
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
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository, orderPaymentRepository, 50);
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
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository, orderPaymentRepository, 50);
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
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository, orderPaymentRepository, 50);

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
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository, orderPaymentRepository, 50);
        Mockito.when(orderPaymentRepository.findByOrderId(9L)).thenReturn(Optional.of(payment("22.00")));

        service.recordOrderEvent(cancelledEvent("MERCHANT_REJECTED: Out of buns"));

        Mockito.verify(orderNotificationRepository).insertIfAbsent(
                Mockito.eq(9L),
                Mockito.eq(4L),
                Mockito.eq("order.cancelled"),
                Mockito.eq("Order rejected by the restaurant"),
                Mockito.eq("The restaurant could not take order #9: Out of buns. Your payment will be refunded automatically."),
                Mockito.any(LocalDateTime.class)
        );
    }


    /**
     * ADR 0002 D12: the cancel of a paid order refunds its captured payment automatically, which the notice of a
     * reject or an accept timeout promises; an order without a capture above 0.00 gets no automatic refund, so its
     * customer is still pointed to support.
     */
    @Test
    void recordOrderEvent_whenAPaidOrderIsRejectedOrTimedOut_shouldPromiseTheRefundOnlyOfACapturedPayment() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository, orderPaymentRepository, 50);
        Mockito.when(orderPaymentRepository.findByOrderId(9L)).thenReturn(
                Optional.of(payment("22.00")),
                Optional.of(payment("0.00")),
                Optional.empty()
        );

        service.recordOrderEvent(cancelledEvent("MERCHANT_TIMEOUT"));
        service.recordOrderEvent(cancelledEvent("MERCHANT_TIMEOUT"));
        service.recordOrderEvent(cancelledEvent("MERCHANT_REJECTED"));

        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        Mockito.verify(orderNotificationRepository, Mockito.times(3)).insertIfAbsent(
                Mockito.eq(9L), Mockito.eq(4L), Mockito.eq("order.cancelled"), Mockito.anyString(), messages.capture(),
                Mockito.any(LocalDateTime.class));
        Assertions.assertEquals(List.of(
                "Order #9 was cancelled because the restaurant did not accept it in time. "
                        + "Your payment will be refunded automatically.",
                "Order #9 was cancelled because the restaurant did not accept it in time. "
                        + "Please contact support about your payment.",
                "The restaurant could not take order #9. Please contact support about your payment."
        ), messages.getAllValues());
    }


    /**
     * ADR 0002 section 24 item 7: the customer's and an admin's cancel of an order with a capture above 0.00 promise
     * the refund too; without one (unpaid, marked PAID by an admin, or 0.00) they say nothing about a payment, as
     * before.
     */
    @Test
    void recordOrderEvent_whenTheCustomerOrAnAdminCancelled_shouldPromiseTheRefundOnlyOfACapturedPayment() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository, orderPaymentRepository, 50);
        Mockito.when(orderPaymentRepository.findByOrderId(9L)).thenReturn(
                Optional.of(payment("22.00")),
                Optional.of(payment("22.00")),
                Optional.of(payment("0.00")),
                Optional.empty()
        );

        service.recordOrderEvent(cancelledEvent("CUSTOMER_CANCELLED"));
        service.recordOrderEvent(cancelledEvent("ADMIN_CANCELLED"));
        service.recordOrderEvent(cancelledEvent("CUSTOMER_CANCELLED"));
        service.recordOrderEvent(cancelledEvent("ADMIN_CANCELLED"));

        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        Mockito.verify(orderNotificationRepository, Mockito.times(4)).insertIfAbsent(
                Mockito.eq(9L), Mockito.eq(4L), Mockito.eq("order.cancelled"), Mockito.eq("Order cancelled"),
                messages.capture(), Mockito.any(LocalDateTime.class));
        Assertions.assertEquals(List.of(
                "Order #9 was cancelled. Your payment will be refunded automatically.",
                "Order #9 was cancelled. Your payment will be refunded automatically.",
                "Order #9 was cancelled.",
                "Order #9 was cancelled."
        ), messages.getAllValues());
    }


    /**
     * ADR 0002 section 27, follow-up 17: the customer's cancel after the rider picked the order up says how much of the
     * payment comes back (the share of the total, half-even), or that nothing does; an admin's cancel after the pickup
     * still refunds it all.
     */
    @Test
    void recordOrderEvent_whenTheCustomerCancelledAfterThePickup_shouldNameTheShareThatIsRefunded() {
        Mockito.when(orderPaymentRepository.findByOrderId(9L)).thenReturn(Optional.of(payment("22.00")));
        Mockito.when(orderRepository.findPickedUpAt(9L)).thenReturn(Optional.of(LocalDateTime.of(2026, 4, 4, 11, 50)));

        new OrderNotificationService(orderNotificationRepository, orderRepository, orderPaymentRepository, 50)
                .recordOrderEvent(cancelledEvent("CUSTOMER_CANCELLED", "16.09"));
        new OrderNotificationService(orderNotificationRepository, orderRepository, orderPaymentRepository, 0)
                .recordOrderEvent(cancelledEvent("CUSTOMER_CANCELLED", "16.09"));
        new OrderNotificationService(orderNotificationRepository, orderRepository, orderPaymentRepository, 50)
                .recordOrderEvent(cancelledEvent("ADMIN_CANCELLED", "16.09"));

        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        Mockito.verify(orderNotificationRepository, Mockito.times(3)).insertIfAbsent(
                Mockito.eq(9L), Mockito.eq(4L), Mockito.eq("order.cancelled"), Mockito.eq("Order cancelled"),
                messages.capture(), Mockito.any(LocalDateTime.class));
        Assertions.assertEquals(List.of(
                "Order #9 was cancelled after the rider picked it up. 8.04 USD of your 16.09 USD payment will be "
                        + "refunded automatically.",
                "Order #9 was cancelled after the rider picked it up. Your payment will not be refunded.",
                "Order #9 was cancelled. Your payment will be refunded automatically."
        ), messages.getAllValues());
    }


    @Test
    void recordOrderEvent_whenThePaymentTimedOut_shouldNotLookAtThePayment() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository, orderPaymentRepository, 50);

        service.recordOrderEvent(cancelledEvent("PAYMENT_TIMEOUT"));

        Mockito.verify(orderNotificationRepository).insertIfAbsent(
                Mockito.eq(9L), Mockito.eq(4L), Mockito.eq("order.cancelled"),
                Mockito.eq("Order cancelled: payment not received"),
                Mockito.eq("Order #9 was cancelled because it was not paid in time."), Mockito.any(LocalDateTime.class));
        Mockito.verifyNoInteractions(orderPaymentRepository);
    }


    @Test
    void recordOrderEvent_whenCancelledWithoutReason_shouldKeepTheGenericMessage() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository, orderPaymentRepository, 50);

        service.recordOrderEvent(buildEvent("evt-c", "order.cancelled", 9L, 4L, "CANCELLED"));

        Mockito.verify(orderNotificationRepository).insertIfAbsent(
                Mockito.eq(9L), Mockito.eq(4L), Mockito.eq("order.cancelled"), Mockito.eq("Order cancelled"),
                Mockito.eq("Order #9 was cancelled."), Mockito.any(LocalDateTime.class));
    }


    @Test
    void recordDeliveryEvent_shouldTellTheCustomerAboutTheRiderAndTheDelivery() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository, orderPaymentRepository, 50);

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
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository, orderPaymentRepository, 50);
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
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository, orderPaymentRepository, 50);
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
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository, orderRepository, orderPaymentRepository, 50);
        Mockito.when(orderNotificationRepository.findPageByCustomerIdOrderByCreatedAtDesc(4L, 50)).thenReturn(List.of());

        Assertions.assertEquals(List.of(), service.getNotificationsByCustomerId(4L));
        Mockito.verifyNoInteractions(orderRepository);
    }


    private OrderPaymentEntity payment(String amount) {
        return new OrderPaymentEntity(30L, 9L, 4L, amount(amount), "USD", OrderPaymentEntity.CAPTURED, "SIMPAY-9",
                LocalDateTime.of(2026, 4, 4, 12, 0));
    }


    private DeliveryEventEnvelope deliveryEvent(String eventType) {
        return new DeliveryEventEnvelope(
                "evt-" + eventType, 1, eventType, "DELIVERY", 7L, LocalDateTime.of(2026, 4, 4, 12, 30),
                "corr", null, new DeliveryEventPayload(9L, 4L, 2L, 3L, "Demo Rider Alice", "ASSIGNED"));
    }


    private OrderEventEnvelope cancelledEvent(String reason) {
        return cancelledEvent(reason, null);
    }


    /**
     * order.cancelled of order 9 with {@code reason}, and with the total {@code total} USD unless it is null.
     */
    private OrderEventEnvelope cancelledEvent(String reason, String total) {
        OrderEventEnvelope event = buildEvent("evt-x", "order.cancelled", 9L, 4L, "CANCELLED");
        OrderEventPayload data = event.data();
        return new OrderEventEnvelope(
                event.eventId(), event.eventVersion(), event.eventType(), event.aggregateType(), event.aggregateId(),
                event.occurredAt(), event.correlationId(), event.idempotencyKey(),
                new OrderEventPayload(data.customerId(), data.status(),
                        total == null ? data.totalPrice() : new BigDecimal(total), data.createdAt(), data.items(),
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
