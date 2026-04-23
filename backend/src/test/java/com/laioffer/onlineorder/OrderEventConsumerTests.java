package com.laioffer.onlineorder;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.messaging.OrderEventConsumer;
import com.laioffer.onlineorder.messaging.OrderEventDeadLetterConsumer;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventPayload;
import com.laioffer.onlineorder.service.DeadLetterProcessingService;
import com.laioffer.onlineorder.service.OrderEventHandlerService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


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
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        OrderEventConsumer consumer = new OrderEventConsumer(objectMapper, orderEventHandlerService);
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
                        15.0,
                        occurredAt,
                        List.of()
                )
        );

        consumer.consume(objectMapper.writeValueAsString(payload));

        Mockito.verify(orderEventHandlerService).handle(Mockito.argThat(event ->
                event.aggregateId().equals(7L) && event.data().customerId().equals(3L)
        ));
    }


    @Test
    void consume_shouldThrowWhenPayloadIsInvalid() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        OrderEventConsumer consumer = new OrderEventConsumer(objectMapper, orderEventHandlerService);

        Assertions.assertThrows(IllegalStateException.class, () -> consumer.consume("{not-json}"));
        Mockito.verifyNoInteractions(orderEventHandlerService);
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
}
