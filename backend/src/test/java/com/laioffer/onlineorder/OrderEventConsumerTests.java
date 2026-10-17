package com.laioffer.onlineorder;


import com.laioffer.onlineorder.messaging.OrderEventConsumer;
import com.laioffer.onlineorder.messaging.OrderEventDeadLetterConsumer;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventPayload;
import com.laioffer.onlineorder.messaging.contract.EventSchemaViolationException;
import com.laioffer.onlineorder.messaging.contract.EventTestSupport;
import com.laioffer.onlineorder.service.DeadLetterProcessingService;
import com.laioffer.onlineorder.service.OrderEventHandlerService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTests {

    @Mock
    private OrderEventHandlerService orderEventHandlerService;

    @Mock
    private DeadLetterProcessingService deadLetterProcessingService;


    @Test
    void consume_shouldConvertPayloadAndRecordNotification() throws Exception {
        OrderEventConsumer consumer = new OrderEventConsumer(EventTestSupport.contracts(), orderEventHandlerService);
        LocalDateTime occurredAt = LocalDateTime.of(2026, 4, 4, 12, 0);
        OrderEventEnvelope payload = new OrderEventEnvelope(
                "evt-1",
                1,
                "order.created",
                "ORDER",
                7L,
                occurredAt,
                "corr-1",
                "idem-1",
                new OrderEventPayload(
                        3L,
                        "PLACED",
                        amount("15.00"),
                        occurredAt,
                        List.of()
                )
        ).withSequence(1L);

        consumer.consume(EventTestSupport.mapper().writeValueAsString(payload));

        Mockito.verify(orderEventHandlerService).handle(Mockito.argThat(event ->
                event.aggregateId().equals(7L)
                        && event.sequence().equals(1L)
                        && event.data().customerId().equals(3L)
                        // Read from the record's text, so the amount keeps its scale.
                        && event.data().totalPrice().equals(amount("15.00"))
        ));
    }


    @Test
    void consume_shouldThrowANonRetryableViolationWhenPayloadIsNotJson() {
        OrderEventConsumer consumer = new OrderEventConsumer(EventTestSupport.contracts(), orderEventHandlerService);

        EventSchemaViolationException violation =
                Assertions.assertThrows(EventSchemaViolationException.class, () -> consumer.consume("{not-json}"));

        Assertions.assertTrue(violation.getMessage().contains("not valid JSON"), violation.getMessage());
        Mockito.verifyNoInteractions(orderEventHandlerService);
    }


    @Test
    void consume_shouldRejectAnEventThatBreaksItsSchemaAndCountIt() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OrderEventConsumer consumer =
                new OrderEventConsumer(EventTestSupport.contracts(meterRegistry), orderEventHandlerService);

        // order.paid whose data says PLACED and has no customer.
        EventSchemaViolationException violation = Assertions.assertThrows(
                EventSchemaViolationException.class,
                () -> consumer.consume("""
                        {"event_id":"evt-2","event_version":1,"event_type":"order.paid","aggregate_type":"ORDER",
                         "aggregate_id":7,"sequence":2,"occurred_at":"2026-04-04T12:00:00",
                         "data":{"status":"PLACED","total_price":15.00,"created_at":"2026-04-04T12:00:00","items":[]}}
                        """)
        );

        Assertions.assertTrue(violation.getMessage().contains("evt-2"), violation.getMessage());
        Assertions.assertTrue(violation.getMessage().contains("/data/status"), violation.getMessage());
        Assertions.assertTrue(violation.getMessage().contains("customer_id"), violation.getMessage());
        Assertions.assertEquals(1.0, meterRegistry.get("onlineorder.events.schema_violations")
                .tag("side", "consumer")
                .tag("topic", "order-events")
                .tag("event_type", "order.paid")
                .counter()
                .count());
        Mockito.verifyNoInteractions(orderEventHandlerService);
    }


    @Test
    void consume_shouldPassAnEventOfAnUnknownTypeThatHasAValidEnvelope() {
        OrderEventConsumer consumer = new OrderEventConsumer(EventTestSupport.contracts(), orderEventHandlerService);

        consumer.consume("""
                {"event_id":"evt-3","event_version":1,"event_type":"order.refunded","aggregate_type":"ORDER",
                 "aggregate_id":7,"sequence":9,"occurred_at":"2026-04-04T12:00:00","data":{"customer_id":3}}
                """);

        Mockito.verify(orderEventHandlerService).handle(Mockito.argThat(event ->
                "order.refunded".equals(event.eventType()) && event.sequence().equals(9L)));
    }


    @Test
    void consume_whenTheHandlerFails_shouldWrapTheFailureForTheRetryingErrorHandler() throws Exception {
        OrderEventConsumer consumer = new OrderEventConsumer(EventTestSupport.contracts(), orderEventHandlerService);
        Mockito.doThrow(new IllegalArgumentException("boom")).when(orderEventHandlerService).handle(Mockito.any());
        OrderEventEnvelope event = new OrderEventEnvelope("evt-4", 1, "order.created", "ORDER", 7L,
                LocalDateTime.of(2026, 4, 4, 12, 0), "evt-4", null,
                new OrderEventPayload(3L, "PLACED", amount("15.00"), LocalDateTime.of(2026, 4, 4, 12, 0), List.of()));

        IllegalStateException failure = Assertions.assertThrows(
                IllegalStateException.class,
                () -> consumer.consume(EventTestSupport.mapper().writeValueAsString(event))
        );

        Assertions.assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }


    @Test
    void deadLetterConsumer_shouldStoreDeadLetterEvent() {
        OrderEventDeadLetterConsumer consumer = new OrderEventDeadLetterConsumer(deadLetterProcessingService);

        consumer.consume("payload", "order-events.dlt", "order-events", "9", "processing failed");

        Mockito.verify(deadLetterProcessingService).handle(
                "order-events",
                "order-events.dlt",
                "9",
                "payload",
                "processing failed"
        );
    }


    private BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
