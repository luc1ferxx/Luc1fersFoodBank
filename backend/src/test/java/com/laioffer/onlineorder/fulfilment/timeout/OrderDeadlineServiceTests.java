package com.laioffer.onlineorder.fulfilment.timeout;


import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.service.ProcessedEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;


import static com.laioffer.onlineorder.fulfilment.FulfilmentTestEvents.orderEvent;


@ExtendWith(MockitoExtension.class)
class OrderDeadlineServiceTests {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 4, 4, 12, 0);

    @Mock
    private OrderDeadlineRepository orderDeadlineRepository;

    @Mock
    private ProcessedEventService processedEventService;

    private OrderDeadlineService service;


    @BeforeEach
    void setup() {
        service = new OrderDeadlineService(
                orderDeadlineRepository,
                processedEventService,
                Duration.ofMinutes(15),
                Duration.ofMinutes(10)
        );
    }


    @Test
    void orderCreated_shouldSetAPaymentDeadlineFromTheOrderTime() {
        OrderEventEnvelope created = event("order.created", "PLACED");
        Mockito.when(processedEventService.markIfUnseen("order-timeout-consumer", created)).thenReturn(true);

        service.handle(created);

        Mockito.verify(orderDeadlineRepository).insertIfAbsent(
                Mockito.eq(10L), Mockito.eq("PAYMENT"), Mockito.eq(OCCURRED_AT.plusMinutes(15)), Mockito.any());
    }


    @Test
    void orderPaid_shouldReplaceThePaymentDeadlineWithAnAcceptDeadlineFromThePaymentTime() {
        OrderEventEnvelope paid = event("order.paid", "PAID");
        Mockito.when(processedEventService.markIfUnseen("order-timeout-consumer", paid)).thenReturn(true);

        service.handle(paid);

        InOrder inOrder = Mockito.inOrder(orderDeadlineRepository);
        inOrder.verify(orderDeadlineRepository).deleteByOrderIdAndDeadlineTypes(10L, List.of("PAYMENT"));
        inOrder.verify(orderDeadlineRepository).insertIfAbsent(
                Mockito.eq(10L), Mockito.eq("ACCEPT"), Mockito.eq(OCCURRED_AT.plusMinutes(10)), Mockito.any());
    }


    @Test
    void laterOrderEvents_shouldDropEveryDeadlineOfTheOrder() {
        Mockito.when(processedEventService.markIfUnseen(Mockito.eq("order-timeout-consumer"), Mockito.any(OrderEventEnvelope.class)))
                .thenReturn(true);

        service.handle(event("order.accepted", "ACCEPTED"));
        service.handle(event("order.cancelled", "CANCELLED"));

        Mockito.verify(orderDeadlineRepository, Mockito.times(2))
                .deleteByOrderIdAndDeadlineTypes(10L, List.of("PAYMENT", "ACCEPT"));
        Mockito.verify(orderDeadlineRepository, Mockito.never())
                .insertIfAbsent(Mockito.anyLong(), Mockito.anyString(), Mockito.any(), Mockito.any());
    }


    @Test
    void duplicateDelivery_shouldNotTouchTheDeadlines() {
        OrderEventEnvelope paid = event("order.paid", "PAID");
        Mockito.when(processedEventService.markIfUnseen("order-timeout-consumer", paid)).thenReturn(false);

        service.handle(paid);

        Mockito.verifyNoInteractions(orderDeadlineRepository);
    }


    @Test
    void orderWithoutRestaurant_shouldNotGetDeadlines() {
        service.handle(orderEvent("order.paid", 10L, 3L, null, "PAID", null, OCCURRED_AT));

        Mockito.verifyNoInteractions(processedEventService, orderDeadlineRepository);
    }


    private OrderEventEnvelope event(String eventType, String status) {
        return orderEvent(eventType, 10L, 3L, 2L, status, null, OCCURRED_AT);
    }
}
