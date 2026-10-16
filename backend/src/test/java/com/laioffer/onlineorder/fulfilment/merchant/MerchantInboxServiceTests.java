package com.laioffer.onlineorder.fulfilment.merchant;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.service.ProcessedEventService;
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


import static com.laioffer.onlineorder.fulfilment.FulfilmentTestEvents.orderEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


@ExtendWith(MockitoExtension.class)
class MerchantInboxServiceTests {

    @Mock
    private MerchantOrderRepository merchantOrderRepository;

    @Mock
    private ProcessedEventService processedEventService;

    private MerchantInboxService service;


    @BeforeEach
    void setup() {
        service = new MerchantInboxService(
                merchantOrderRepository,
                new ObjectMapper().findAndRegisterModules(),
                processedEventService
        );
    }


    @Test
    void orderPaid_shouldAddTheOrderToItsRestaurantsInbox() {
        OrderEventEnvelope paid = orderEvent("order.paid", 10L, 2L, "PAID");
        Mockito.when(processedEventService.markIfUnseen("merchant-inbox-consumer", paid)).thenReturn(true);

        service.handle(paid);

        ArgumentCaptor<String> items = ArgumentCaptor.forClass(String.class);
        Mockito.verify(merchantOrderRepository).insertPaidOrderIfAbsent(
                Mockito.eq(10L),
                Mockito.eq(2L),
                Mockito.eq(3L),
                Mockito.eq(new BigDecimal("10.59")),
                items.capture(),
                Mockito.eq(LocalDateTime.of(2026, 4, 4, 12, 0)),
                Mockito.any(LocalDateTime.class)
        );
        assertTrue(items.getValue().contains("Whopper Meal"));
    }


    @Test
    void duplicateDelivery_shouldNotTouchTheInboxAgain() {
        OrderEventEnvelope paid = orderEvent("order.paid", 10L, 2L, "PAID");
        Mockito.when(processedEventService.markIfUnseen("merchant-inbox-consumer", paid)).thenReturn(false);

        service.handle(paid);

        Mockito.verifyNoInteractions(merchantOrderRepository);
    }


    @Test
    void orderWithoutRestaurant_shouldBeIgnoredWithoutRecordingTheEvent() {
        service.handle(orderEvent("order.paid", 10L, null, "PAID"));

        Mockito.verifyNoInteractions(processedEventService, merchantOrderRepository);
    }


    @Test
    void orderCreated_shouldNotReachTheMerchant() {
        service.handle(orderEvent("order.created", 10L, 2L, "PLACED"));

        Mockito.verifyNoInteractions(processedEventService, merchantOrderRepository);
    }


    @Test
    void laterOrderEvents_shouldOnlyMoveTheInboxRowForward() {
        Mockito.when(processedEventService.markIfUnseen(Mockito.eq("merchant-inbox-consumer"), Mockito.any(OrderEventEnvelope.class)))
                .thenReturn(true);

        service.handle(orderEvent("order.accepted", 10L, 2L, "ACCEPTED"));
        service.handle(orderEvent("order.preparing", 10L, 2L, "PREPARING"));
        service.handle(orderEvent("order.completed", 10L, 2L, "COMPLETED"));

        Mockito.verify(merchantOrderRepository).advanceStatus(
                Mockito.eq(10L), Mockito.eq(List.of("PAID")), Mockito.eq("ACCEPTED"), Mockito.any());
        Mockito.verify(merchantOrderRepository).advanceStatus(
                Mockito.eq(10L), Mockito.eq(List.of("PAID", "ACCEPTED")), Mockito.eq("PREPARING"), Mockito.any());
        Mockito.verify(merchantOrderRepository).advanceStatus(
                Mockito.eq(10L), Mockito.eq(List.of("PAID", "ACCEPTED", "PREPARING")), Mockito.eq("COMPLETED"), Mockito.any());
    }


    @Test
    void orderCancelled_shouldCancelTheInboxRowWithTheReason() {
        OrderEventEnvelope cancelled = orderEvent(
                "order.cancelled", 10L, 3L, 2L, "CANCELLED", "MERCHANT_TIMEOUT", LocalDateTime.of(2026, 4, 4, 12, 10));
        Mockito.when(processedEventService.markIfUnseen("merchant-inbox-consumer", cancelled)).thenReturn(true);

        service.handle(cancelled);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        Mockito.verify(merchantOrderRepository).cancel(
                Mockito.eq(10L),
                Mockito.eq(List.of("PAID", "ACCEPTED", "PREPARING")),
                reason.capture(),
                Mockito.any()
        );
        assertEquals("MERCHANT_TIMEOUT", reason.getValue());
    }
}
