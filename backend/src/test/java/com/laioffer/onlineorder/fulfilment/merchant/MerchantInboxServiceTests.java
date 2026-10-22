package com.laioffer.onlineorder.fulfilment.merchant;


import com.laioffer.onlineorder.messaging.EventEnvelope;
import com.laioffer.onlineorder.messaging.MerchantEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.service.EventSequenceTracker;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import com.laioffer.onlineorder.service.ProcessedEventService;
import com.laioffer.onlineorder.push.PushRecipient;
import com.laioffer.onlineorder.push.PushSignals;
import com.laioffer.onlineorder.push.PushTopic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


import static com.laioffer.onlineorder.fulfilment.FulfilmentTestEvents.inCurrency;
import static com.laioffer.onlineorder.fulfilment.FulfilmentTestEvents.orderEvent;
import static com.laioffer.onlineorder.fulfilment.FulfilmentTestEvents.withOrderNo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@ExtendWith(MockitoExtension.class)
class MerchantInboxServiceTests {

    /**
     * A Snowflake order number past 2^53.
     */
    private static final long ORDER_NO = 7_254_189_912_938_504_192L;

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

    @Mock
    private EventSequenceTracker sequenceTracker;

    @Mock
    private PushSignals pushSignals;

    private MerchantInboxService service;


    @BeforeEach
    void setup() {
        service = new MerchantInboxService(
                merchantOrderRepository,
                merchantAccountRepository,
                outboxService,
                metricsService,
                JsonMapper.builder().build(),
                processedEventService,
                sequenceTracker,
                pushSignals,
                "merchant-events"
        );
        // Events built by FulfilmentTestEvents carry no sequence; the tracker applies them (EventSequenceTrackerTests).
        Mockito.lenient().when(sequenceTracker.advance(Mockito.anyString(), Mockito.any())).thenReturn(true);
    }


    @Test
    void orderPaid_shouldAddTheOrderToItsRestaurantsInbox() {
        OrderEventEnvelope paid = orderEvent("order.paid", 10L, 2L, "PAID");
        Mockito.when(processedEventService.markIfUnseen("merchant-inbox-consumer", paid)).thenReturn(true);
        Mockito.when(merchantOrderRepository.insertPaidOrderIfAbsent(
                Mockito.anyLong(), Mockito.any(), Mockito.anyLong(), Mockito.anyLong(), Mockito.any(),
                Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any())).thenReturn(1);

        service.handle(paid);

        ArgumentCaptor<String> items = ArgumentCaptor.forClass(String.class);
        Mockito.verify(merchantOrderRepository).insertPaidOrderIfAbsent(
                Mockito.eq(10L),
                Mockito.isNull(),
                Mockito.eq(2L),
                Mockito.eq(3L),
                Mockito.eq(new BigDecimal("10.59")),
                Mockito.eq("USD"),
                items.capture(),
                Mockito.eq(LocalDateTime.of(2026, 4, 4, 12, 0)),
                Mockito.any(LocalDateTime.class)
        );
        assertTrue(items.getValue().contains("Whopper Meal"));
        Mockito.verify(merchantOrderRepository, Mockito.never())
                .recordPayment(Mockito.anyLong(), Mockito.any(), Mockito.any());
        Mockito.verify(pushSignals).publish(PushRecipient.restaurant(2L), PushTopic.MERCHANT_ORDERS);
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
        // The row had its payment time already (the mock changes no row): nothing for the merchant to re-read.
        Mockito.verifyNoInteractions(pushSignals);
    }


    @Test
    void orderCancelled_beforeOrderPaid_shouldLeaveAHiddenCancelledTombstone() {
        OrderEventEnvelope cancelled = orderEvent(
                "order.cancelled", 10L, 3L, 2L, "CANCELLED", "CUSTOMER_CANCELLED", LocalDateTime.of(2026, 4, 4, 12, 5));
        Mockito.when(processedEventService.markIfUnseen("merchant-inbox-consumer", cancelled)).thenReturn(true);
        Mockito.when(merchantOrderRepository.insertUnpaidOrderIfAbsent(
                Mockito.anyLong(), Mockito.any(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString(),
                Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any())).thenReturn(1);

        service.handle(cancelled);

        Mockito.verify(merchantOrderRepository).insertUnpaidOrderIfAbsent(
                Mockito.eq(10L),
                Mockito.isNull(),
                Mockito.eq(2L),
                Mockito.eq(3L),
                Mockito.eq("CANCELLED"),
                Mockito.eq(new BigDecimal("10.59")),
                Mockito.eq("USD"),
                Mockito.contains("Whopper Meal"),
                Mockito.eq("CUSTOMER_CANCELLED"),
                Mockito.any(LocalDateTime.class)
        );
        Mockito.verify(merchantOrderRepository, Mockito.never())
                .cancel(Mockito.anyLong(), Mockito.anyCollection(), Mockito.any(), Mockito.any());
    }


    @Test
    void orderPaid_shouldKeepTheOrdersCurrencyWithItsAmounts() {
        OrderEventEnvelope paid = inCurrency(orderEvent("order.paid", 10L, 2L, "PAID"), "EUR");
        Mockito.when(processedEventService.markIfUnseen("merchant-inbox-consumer", paid)).thenReturn(true);

        service.handle(paid);

        ArgumentCaptor<String> items = ArgumentCaptor.forClass(String.class);
        Mockito.verify(merchantOrderRepository).insertPaidOrderIfAbsent(
                Mockito.eq(10L),
                Mockito.isNull(),
                Mockito.eq(2L),
                Mockito.eq(3L),
                Mockito.eq(new BigDecimal("10.59")),
                Mockito.eq("EUR"),
                items.capture(),
                Mockito.any(),
                Mockito.any()
        );
        // The stored lines are priced in the row's currency and carry none of their own.
        assertTrue(!items.getValue().contains("currency"), items.getValue());
    }


    @Test
    void orderPaid_publishedBeforeOrdersCarriedACurrency_shouldBeStoredAsUsd() {
        OrderEventEnvelope legacyPaid = inCurrency(orderEvent("order.paid", 10L, 2L, "PAID"), null);
        Mockito.when(processedEventService.markIfUnseen("merchant-inbox-consumer", legacyPaid)).thenReturn(true);

        service.handle(legacyPaid);

        Mockito.verify(merchantOrderRepository).insertPaidOrderIfAbsent(
                Mockito.eq(10L), Mockito.isNull(), Mockito.eq(2L), Mockito.eq(3L), Mockito.eq(new BigDecimal("10.59")),
                Mockito.eq("USD"), Mockito.anyString(), Mockito.any(), Mockito.any());
    }


    @Test
    void orderPaid_withAnUnknownCurrency_shouldFailForTheDeadLetterTopic() {
        OrderEventEnvelope paid = inCurrency(orderEvent("order.paid", 10L, 2L, "PAID"), "usd");
        Mockito.when(processedEventService.markIfUnseen("merchant-inbox-consumer", paid)).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.handle(paid));

        Mockito.verify(merchantOrderRepository, Mockito.never()).insertPaidOrderIfAbsent(
                Mockito.anyLong(), Mockito.any(), Mockito.anyLong(), Mockito.anyLong(), Mockito.any(),
                Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any());
    }


    @Test
    void duplicateDelivery_shouldNotTouchTheInboxAgain() {
        OrderEventEnvelope paid = orderEvent("order.paid", 10L, 2L, "PAID");
        Mockito.when(processedEventService.markIfUnseen("merchant-inbox-consumer", paid)).thenReturn(false);

        service.handle(paid);

        Mockito.verifyNoInteractions(merchantOrderRepository, pushSignals);
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
                    Mockito.eq(10L), Mockito.isNull(), Mockito.eq(2L), Mockito.eq(3L), Mockito.eq(status),
                    Mockito.any(), Mockito.eq("USD"), Mockito.anyString(), Mockito.isNull(), Mockito.any());
        }

        Mockito.verify(merchantOrderRepository).advanceStatus(
                Mockito.eq(10L), Mockito.eq(List.of("PAID")), Mockito.eq("ACCEPTED"), Mockito.any());
        Mockito.verify(merchantOrderRepository).advanceStatus(
                Mockito.eq(10L), Mockito.eq(List.of("PAID", "ACCEPTED")), Mockito.eq("PREPARING"), Mockito.any());
        Mockito.verify(merchantOrderRepository).advanceStatus(
                Mockito.eq(10L), Mockito.eq(List.of("PAID", "ACCEPTED", "PREPARING")), Mockito.eq("COMPLETED"), Mockito.any());
        // Version 1 events carry no order number, so there is none to record.
        Mockito.verify(merchantOrderRepository, Mockito.never()).recordOrderNo(Mockito.anyLong(), Mockito.any());
    }


    @Test
    void orderPaid_version2_shouldStoreTheOrderNumberWithTheNewRow() {
        OrderEventEnvelope paid = withOrderNo(orderEvent("order.paid", 10L, 2L, "PAID"), ORDER_NO);
        Mockito.when(processedEventService.markIfUnseen("merchant-inbox-consumer", paid)).thenReturn(true);
        Mockito.when(merchantOrderRepository.insertPaidOrderIfAbsent(
                Mockito.anyLong(), Mockito.any(), Mockito.anyLong(), Mockito.anyLong(), Mockito.any(),
                Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any())).thenReturn(1);

        service.handle(paid);

        Mockito.verify(merchantOrderRepository).insertPaidOrderIfAbsent(
                Mockito.eq(10L), Mockito.eq(ORDER_NO), Mockito.eq(2L), Mockito.eq(3L), Mockito.any(),
                Mockito.eq("USD"), Mockito.anyString(), Mockito.any(), Mockito.any());
        // The insert wrote it; there is no other row to fill.
        Mockito.verify(merchantOrderRepository, Mockito.never()).recordOrderNo(Mockito.anyLong(), Mockito.any());
    }


    @Test
    void laterOrderEvents_version2_shouldStoreTheNumberWithARowTheyCreateOrFillARowWithoutOne() {
        Mockito.when(processedEventService.markIfUnseen(
                Mockito.eq("merchant-inbox-consumer"), Mockito.any(OrderEventEnvelope.class))).thenReturn(true);
        // Lenient: order.accepted calls the same insert with another status, which returns 0 (the row exists).
        Mockito.lenient().when(merchantOrderRepository.insertUnpaidOrderIfAbsent(
                        Mockito.anyLong(), Mockito.any(), Mockito.anyLong(), Mockito.anyLong(), Mockito.eq("CANCELLED"),
                        Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any()))
                .thenReturn(1);

        // The row exists for orders 10 and 11 (their inserts are no-ops); order.cancelled creates order 12's.
        service.handle(withOrderNo(orderEvent("order.accepted", 10L, 2L, "ACCEPTED"), ORDER_NO));
        service.handle(withOrderNo(orderEvent("order.paid", 11L, 2L, "PAID"), ORDER_NO + 1));
        service.handle(withOrderNo(orderEvent("order.cancelled", 12L, 2L, "CANCELLED"), ORDER_NO + 2));

        Mockito.verify(merchantOrderRepository).advanceStatus(
                Mockito.eq(10L), Mockito.eq(List.of("PAID")), Mockito.eq("ACCEPTED"), Mockito.any());
        Mockito.verify(merchantOrderRepository).recordOrderNo(10L, ORDER_NO);
        Mockito.verify(merchantOrderRepository).recordPayment(Mockito.eq(11L), Mockito.any(), Mockito.any());
        Mockito.verify(merchantOrderRepository).recordOrderNo(11L, ORDER_NO + 1);
        Mockito.verify(merchantOrderRepository).insertUnpaidOrderIfAbsent(
                Mockito.eq(12L), Mockito.eq(ORDER_NO + 2), Mockito.eq(2L), Mockito.eq(3L), Mockito.eq("CANCELLED"),
                Mockito.any(), Mockito.eq("USD"), Mockito.anyString(), Mockito.any(), Mockito.any());
        Mockito.verify(merchantOrderRepository, Mockito.never()).recordOrderNo(Mockito.eq(12L), Mockito.any());
        Mockito.verifyNoInteractions(outboxService);
    }


    /**
     * A stale event moves nothing but still fills a row that has no number yet: the number never changes.
     */
    @Test
    void staleEvent_version2_shouldOnlyRecordTheOrderNumber() {
        OrderEventEnvelope accepted = withOrderNo(orderEvent("order.accepted", 10L, 2L, "ACCEPTED", 2), ORDER_NO);
        Mockito.when(processedEventService.markIfUnseen("merchant-inbox-consumer", accepted)).thenReturn(true);
        Mockito.when(sequenceTracker.advance("merchant-inbox-consumer", accepted)).thenReturn(false);

        service.handle(accepted);

        Mockito.verify(merchantOrderRepository).recordOrderNo(10L, ORDER_NO);
        Mockito.verifyNoMoreInteractions(merchantOrderRepository);
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
                Mockito.anyLong(), Mockito.any(), Mockito.anyLong(), Mockito.anyLong(), Mockito.any(),
                Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any())).thenReturn(1);
        Mockito.when(merchantAccountRepository.countActiveByRestaurantId(2L)).thenReturn(2);

        service.handle(paid);

        ArgumentCaptor<EventEnvelope> envelope = ArgumentCaptor.forClass(EventEnvelope.class);
        Mockito.verify(outboxService).enqueueEvent(
                Mockito.eq("merchant-events"),
                Mockito.eq("10"),
                envelope.capture()
        );
        MerchantEventEnvelope event = (MerchantEventEnvelope) envelope.getValue();
        assertEquals("MERCHANT_ORDER", event.aggregateType());
        assertEquals(1, event.eventVersion());
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
                Mockito.anyLong(), Mockito.any(), Mockito.anyLong(), Mockito.anyLong(), Mockito.any(),
                Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any())).thenReturn(1);
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
