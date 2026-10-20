package com.laioffer.onlineorder.fulfilment.order;


import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import com.laioffer.onlineorder.messaging.DeliveryEventPayload;
import com.laioffer.onlineorder.service.ProcessedEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.time.LocalDateTime;


@ExtendWith(MockitoExtension.class)
class DeliveryCompletionServiceTests {

    @Mock
    private OrderLifecycleCommands orderLifecycleCommands;

    @Mock
    private ProcessedEventService processedEventService;

    private DeliveryCompletionService service;


    @BeforeEach
    void setup() {
        service = new DeliveryCompletionService(orderLifecycleCommands, processedEventService);
    }


    @Test
    void deliveryDelivered_shouldCompleteTheOrder() {
        Mockito.when(processedEventService.markIfUnseen(
                "order-delivery-consumer", "evt-1", "evt-1", "delivery.delivered", "DELIVERY", 7L)).thenReturn(true);
        Mockito.when(orderLifecycleCommands.completeDeliveredOrder(10L, OrderActor.rider(4L)))
                .thenReturn(OrderLifecycleCommands.DeliveryCompletion.COMPLETED);

        service.handle(event("delivery.delivered"));

        // The rider of the delivery event is the actor recorded in the order history.
        Mockito.verify(orderLifecycleCommands).completeDeliveredOrder(10L, OrderActor.rider(4L));
    }


    /**
     * ADR 0002 I14 (section 4.1): the processed_events mark (an advisory lock on the event) comes before the order
     * module locks the order.
     */
    @Test
    void deliveryDelivered_shouldMarkTheEventBeforeCompletingTheOrder() {
        Mockito.when(processedEventService.markIfUnseen(
                "order-delivery-consumer", "evt-1", "evt-1", "delivery.delivered", "DELIVERY", 7L)).thenReturn(true);
        Mockito.when(orderLifecycleCommands.completeDeliveredOrder(10L, OrderActor.rider(4L)))
                .thenReturn(OrderLifecycleCommands.DeliveryCompletion.COMPLETED);

        service.handle(event("delivery.delivered"));

        InOrder steps = Mockito.inOrder(processedEventService, orderLifecycleCommands);
        steps.verify(processedEventService).markIfUnseen(
                "order-delivery-consumer", "evt-1", "evt-1", "delivery.delivered", "DELIVERY", 7L);
        steps.verify(orderLifecycleCommands).completeDeliveredOrder(10L, OrderActor.rider(4L));
        Mockito.verifyNoMoreInteractions(processedEventService, orderLifecycleCommands);
    }


    @Test
    void deliveryDeliveredWithoutARider_shouldCompleteTheOrderAsSystem() {
        Mockito.when(processedEventService.markIfUnseen(
                "order-delivery-consumer", "evt-1", "evt-1", "delivery.delivered", "DELIVERY", 7L)).thenReturn(true);
        Mockito.when(orderLifecycleCommands.completeDeliveredOrder(10L, OrderActor.system()))
                .thenReturn(OrderLifecycleCommands.DeliveryCompletion.COMPLETED);

        service.handle(new DeliveryEventEnvelope(
                "evt-1", 1, "delivery.delivered", "DELIVERY", 7L, LocalDateTime.of(2026, 4, 4, 12, 30), "evt-1", null,
                new DeliveryEventPayload(10L, 3L, 2L, null, null, "DELIVERED")));

        Mockito.verify(orderLifecycleCommands).completeDeliveredOrder(10L, OrderActor.system());
    }


    @Test
    void duplicateDeliveryDelivered_shouldCompleteTheOrderOnce() {
        Mockito.when(processedEventService.markIfUnseen(
                "order-delivery-consumer", "evt-1", "evt-1", "delivery.delivered", "DELIVERY", 7L)).thenReturn(false);

        service.handle(event("delivery.delivered"));

        Mockito.verifyNoInteractions(orderLifecycleCommands);
    }


    @Test
    void otherDeliveryEvents_shouldNotTouchTheOrder() {
        service.handle(event("delivery.assigned"));
        service.handle(event("delivery.picked_up"));

        Mockito.verifyNoInteractions(orderLifecycleCommands, processedEventService);
    }


    private DeliveryEventEnvelope event(String eventType) {
        return new DeliveryEventEnvelope(
                "evt-1",
                1,
                eventType,
                "DELIVERY",
                7L,
                LocalDateTime.of(2026, 4, 4, 12, 30),
                "evt-1",
                null,
                new DeliveryEventPayload(10L, 3L, 2L, 4L, "Alice", "DELIVERED")
        );
    }
}
