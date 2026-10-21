package com.laioffer.onlineorder;


import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventPayload;
import com.laioffer.onlineorder.service.EventSequenceTracker;
import com.laioffer.onlineorder.service.OrderEventHandlerService;
import com.laioffer.onlineorder.service.OrderNotificationService;
import com.laioffer.onlineorder.service.ProcessedEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


@ExtendWith(MockitoExtension.class)
class OrderEventHandlerServiceTests {

    @Mock
    private OrderNotificationService orderNotificationService;

    @Mock
    private ProcessedEventService processedEventService;

    @Mock
    private EventSequenceTracker sequenceTracker;


    @Test
    void handle_shouldSkipAlreadyProcessedEvent() {
        OrderEventHandlerService service = new OrderEventHandlerService(
                orderNotificationService, processedEventService, sequenceTracker);
        OrderEventEnvelope event = buildEvent();

        Mockito.when(processedEventService.markIfUnseen("order-notification-consumer", event)).thenReturn(false);

        service.handle(event);

        Mockito.verify(orderNotificationService, Mockito.never()).recordOrderEvent(Mockito.any());
        Mockito.verifyNoInteractions(sequenceTracker);
    }


    @Test
    void handle_shouldRecordNotificationForNewEvent() {
        OrderEventHandlerService service = new OrderEventHandlerService(
                orderNotificationService, processedEventService, sequenceTracker);
        OrderEventEnvelope event = buildEvent();

        Mockito.when(processedEventService.markIfUnseen("order-notification-consumer", event)).thenReturn(true);
        Mockito.when(sequenceTracker.advance("order-notification-consumer", event)).thenReturn(true);

        service.handle(event);

        Mockito.verify(orderNotificationService).recordOrderEvent(event);
    }


    @Test
    void handle_shouldNotNotifyOfAStaleEvent() {
        OrderEventHandlerService service = new OrderEventHandlerService(
                orderNotificationService, processedEventService, sequenceTracker);
        OrderEventEnvelope event = buildEvent();

        Mockito.when(processedEventService.markIfUnseen("order-notification-consumer", event)).thenReturn(true);
        Mockito.when(sequenceTracker.advance("order-notification-consumer", event)).thenReturn(false);

        service.handle(event);

        Mockito.verify(orderNotificationService, Mockito.never()).recordOrderEvent(Mockito.any());
    }


    private OrderEventEnvelope buildEvent() {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 4, 4, 12, 0);
        return new OrderEventEnvelope(
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
                        amount("15.0"),
                        occurredAt,
                        List.of()
                )
        );
    }


    private BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
