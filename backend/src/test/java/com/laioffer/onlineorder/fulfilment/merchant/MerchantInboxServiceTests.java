package com.laioffer.onlineorder.fulfilment.merchant;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.messaging.MerchantEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
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

    @Mock
    private MerchantAccountRepository merchantAccountRepository;

    @Mock
    private OrderEventOutboxService outboxService;

    @Mock
    private ApplicationMetricsService metricsService;

    private MerchantInboxService service;


    @BeforeEach
    void setup() {
        service = new MerchantInboxService(
                merchantOrderRepository,
                merchantAccountRepository,
                outboxService,
                metricsService,
                new ObjectMapper().findAndRegisterModules(),
                processedEventService,
                "merchant-events"
        );
    }


    @Test
    void orderPaid_shouldAddTheOrderToItsRestaurantsInbox() {
        OrderEventEnvelope paid = orderEvent("order.paid", 10L, 2L, "PAID");
        Mockito.when(processedEventService.markIfUnseen("merchant-inbox-consumer", paid)).thenReturn(true);
        Mockito.when(merchantOrderRepository.insertPaidOrderIfAbsent(
                Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.any(), Mockito.anyString(),
                Mockito.any(), Mockito.any())).thenReturn(1);

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
        Mockito.verify(merchantOrderRepository, Mockito.never())
                .recordPayment(Mockito.anyLong(), Mockito.any(), Mockito.any());
    }


    @Test
    void orderPaid_afterALaterEventCreatedTheRow_shouldOnlyRecordThePayment() {
        OrderEventEnvelope paid = orderEvent("order.paid", 10L, 2L, "PAID");
        Mockito.when(processedEventService.markIfUnseen("merchant-inbox-consumer", paid)).thenReturn(true);
        // The row exists already (insert is a no-op): for example a tombstone order.cancelled wrote.

        service.handle(paid);

        Mockito.verify(merchantOrderRepository).recordPayment(
                Mockito.eq(10L), Mockito.eq(LocalDateTime.of(2026, 4, 4, 12, 0)), Mockito.any(LocalDateTime.class));
        Mockito.verify(merchantOrderRepository, Mockito.never())
                .advanceStatus(Mockito.anyLong(), Mockito.anyCollection(), Mockito.anyString(), Mockito.any());
        Mockito.verify(merchantOrderRepository, Mockito.never())
                .cancel(Mockito.anyLong(), Mockito.anyCollection(), Mockito.any(), Mockito.any());
    }


    @Test
    void orderCancelled_beforeOrderPaid_shouldLeaveAHiddenCancelledTombstone() {
        OrderEventEnvelope cancelled = orderEvent(
                "order.cancelled", 10L, 3L, 2L, "CANCELLED", "CUSTOMER_CANCELLED", LocalDateTime.of(2026, 4, 4, 12, 5));
        Mockito.when(processedEventService.markIfUnseen("merchant-inbox-consumer", cancelled)).thenReturn(true);
        Mockito.when(merchantOrderRepository.insertUnpaidOrderIfAbsent(
                Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString(), Mockito.any(),
                Mockito.anyString(), Mockito.any(), Mockito.any())).thenReturn(1);

        service.handle(cancelled);

        Mockito.verify(merchantOrderRepository).insertUnpaidOrderIfAbsent(
                Mockito.eq(10L),
                Mockito.eq(2L),
                Mockito.eq(3L),
                Mockito.eq("CANCELLED"),
                Mockito.eq(new BigDecimal("10.59")),
                Mockito.contains("Whopper Meal"),
                Mockito.eq("CUSTOMER_CANCELLED"),
                Mockito.any(LocalDateTime.class)
        );
        Mockito.verify(merchantOrderRepository, Mockito.never())
                .cancel(Mockito.anyLong(), Mockito.anyCollection(), Mockito.any(), Mockito.any());
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

        // The row exists (every insert below is a no-op), so the events only move it forward.
        for (String status : List.of("ACCEPTED", "PREPARING", "COMPLETED")) {
            Mockito.verify(merchantOrderRepository).insertUnpaidOrderIfAbsent(
                    Mockito.eq(10L), Mockito.eq(2L), Mockito.eq(3L), Mockito.eq(status), Mockito.any(),
                    Mockito.anyString(), Mockito.isNull(), Mockito.any());
        }

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
    @Test
    void orderPaid_atAStaffedRestaurant_shouldAnnounceItWithThePaymentTime() {
        OrderEventEnvelope paid = orderEvent("order.paid", 10L, 2L, "PAID");
        Mockito.when(processedEventService.markIfUnseen("merchant-inbox-consumer", paid)).thenReturn(true);
        Mockito.when(merchantOrderRepository.insertPaidOrderIfAbsent(
                Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.any(), Mockito.anyString(),
                Mockito.any(), Mockito.any())).thenReturn(1);
        Mockito.when(merchantAccountRepository.countActiveByRestaurantId(2L)).thenReturn(2);

        service.handle(paid);

        ArgumentCaptor<Object> envelope = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<String> eventId = ArgumentCaptor.forClass(String.class);
        Mockito.verify(outboxService).enqueueEvent(
                Mockito.eq("MERCHANT_ORDER"),
                Mockito.eq(10L),
                Mockito.eq("merchant-events"),
                Mockito.eq("10"),
                Mockito.eq("merchant.order_received"),
                eventId.capture(),
                envelope.capture()
        );
        MerchantEventEnvelope event = (MerchantEventEnvelope) envelope.getValue();
        assertEquals(eventId.getValue(), event.eventId());
        assertEquals("merchant.order_received", event.eventType());
        assertEquals(10L, event.aggregateId());
        assertEquals(paid.correlationId(), event.correlationId());
        assertEquals(10L, event.data().orderId());
        assertEquals(2L, event.data().restaurantId());
        assertEquals(LocalDateTime.of(2026, 4, 4, 12, 0), event.data().paidAt());
        assertEquals(2, event.data().activeMerchants());
        Mockito.verify(metricsService).recordMerchantOrderReceived("staffed");
    }


    @Test
    void orderPaid_atARestaurantWithoutAnActiveMerchant_shouldNotAnnounceIt() {
        OrderEventEnvelope paid = orderEvent("order.paid", 10L, 2L, "PAID");
        Mockito.when(processedEventService.markIfUnseen("merchant-inbox-consumer", paid)).thenReturn(true);
        Mockito.when(merchantOrderRepository.insertPaidOrderIfAbsent(
                Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.any(), Mockito.anyString(),
                Mockito.any(), Mockito.any())).thenReturn(1);
        Mockito.when(merchantAccountRepository.countActiveByRestaurantId(2L)).thenReturn(0);

        service.handle(paid);

        Mockito.verifyNoInteractions(outboxService);
        Mockito.verify(metricsService).recordMerchantOrderReceived("unstaffed");
    }


    @Test
    void orderPaid_thatOnlyCompletesARowALaterEventCreated_shouldNotAnnounceIt() {
        OrderEventEnvelope paid = orderEvent("order.paid", 10L, 2L, "PAID");
        Mockito.when(processedEventService.markIfUnseen("merchant-inbox-consumer", paid)).thenReturn(true);

        service.handle(paid);

        Mockito.verifyNoInteractions(outboxService, merchantAccountRepository, metricsService);
    }
}
