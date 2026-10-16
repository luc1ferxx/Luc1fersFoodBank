package com.laioffer.onlineorder.fulfilment.timeout;


import com.laioffer.onlineorder.messaging.MerchantEventEnvelope;
import com.laioffer.onlineorder.messaging.MerchantEventPayload;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.service.ProcessedEventService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
                Duration.ofMinutes(10),
                true
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
    void orderPaid_shouldOnlyDropThePaymentDeadline() {
        OrderEventEnvelope paid = event("order.paid", "PAID");
        Mockito.when(processedEventService.markIfUnseen("order-timeout-consumer", paid)).thenReturn(true);

        service.handle(paid);

        // The accept deadline waits for merchant.order_received: only a staffed restaurant gets one.
        Mockito.verify(orderDeadlineRepository).deleteByOrderIdAndDeadlineTypes(10L, List.of("PAYMENT"));
        Mockito.verify(orderDeadlineRepository, Mockito.never())
                .insertIfAbsent(Mockito.anyLong(), Mockito.anyString(), Mockito.any(), Mockito.any());
    }


    @Test
    void merchantOrderReceived_shouldSetAnAcceptDeadlineFromWhenTheInboxReceivedTheOrder() {
        MerchantEventEnvelope received = merchantEvent(OCCURRED_AT);
        Mockito.when(processedEventService.markIfUnseen(
                "order-timeout-merchant-consumer", received.eventId(), received.eventId(), "merchant.order_received",
                "MERCHANT_ORDER", 10L)).thenReturn(true);

        service.handle(received);

        // Paid at 12:00:00, in the merchant's inbox at 12:00:02.
        Mockito.verify(orderDeadlineRepository).insertIfAbsent(
                Mockito.eq(10L), Mockito.eq("ACCEPT"), Mockito.eq(OCCURRED_AT.plusSeconds(2).plusMinutes(10)), Mockito.any());
    }


    @Test
    void merchantOrderReceived_longAfterThePayment_shouldStillGiveTheMerchantTheWholeWindow() {
        // The merchant group handled order.paid 25 minutes late (a backlog or a replayed dead letter): measured from
        // the payment, the deadline would already be due when the order first shows up in the inbox.
        LocalDateTime receivedAt = OCCURRED_AT.plusMinutes(25);
        MerchantEventEnvelope received = new MerchantEventEnvelope(
                "evt-late", 1, "merchant.order_received", "MERCHANT_ORDER", 10L, receivedAt, "evt-late", null,
                new MerchantEventPayload(10L, 2L, OCCURRED_AT, 1));
        Mockito.when(processedEventService.markIfUnseen(
                Mockito.eq("order-timeout-merchant-consumer"), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyString(), Mockito.anyLong())).thenReturn(true);

        service.handle(received);

        Mockito.verify(orderDeadlineRepository).insertIfAbsent(
                Mockito.eq(10L), Mockito.eq("ACCEPT"), Mockito.eq(receivedAt.plusMinutes(10)), Mockito.any());
    }


    @Test
    void receivedAt_shouldNeverBeBeforeThePaymentAndFallBackToNow() {
        LocalDateTime now = OCCURRED_AT.plusHours(1);
        // Another instance's clock stamped the announcement before the payment.
        Assertions.assertEquals(OCCURRED_AT, OrderDeadlineService.receivedAt(new MerchantEventEnvelope(
                "e", 1, "merchant.order_received", "MERCHANT_ORDER", 10L, OCCURRED_AT.minusSeconds(1), "e", null,
                new MerchantEventPayload(10L, 2L, OCCURRED_AT, 1)), now));
        // No event time: this consumer's own time, a later bound for the receipt.
        Assertions.assertEquals(now, OrderDeadlineService.receivedAt(new MerchantEventEnvelope(
                "e", 1, "merchant.order_received", "MERCHANT_ORDER", 10L, null, "e", null,
                new MerchantEventPayload(10L, 2L, OCCURRED_AT, 1)), now));
    }


    @Test
    void merchantOrderReceived_withoutAPaymentTime_shouldMeasureFromTheEventTime() {
        LocalDateTime receivedAt = OCCURRED_AT.plusSeconds(3);
        MerchantEventEnvelope received = new MerchantEventEnvelope(
                "evt-1", 1, "merchant.order_received", "MERCHANT_ORDER", 10L, receivedAt, "evt-1", null,
                new MerchantEventPayload(10L, 2L, null, 1));
        Mockito.when(processedEventService.markIfUnseen(
                Mockito.eq("order-timeout-merchant-consumer"), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyString(), Mockito.anyLong())).thenReturn(true);

        service.handle(received);

        Mockito.verify(orderDeadlineRepository).insertIfAbsent(
                Mockito.eq(10L), Mockito.eq("ACCEPT"), Mockito.eq(receivedAt.plusMinutes(10)), Mockito.any());
    }


    @Test
    void merchantOrderReceived_whenTheAcceptTimeoutIsOff_shouldSetNoDeadline() {
        OrderDeadlineService acceptTimeoutOff = new OrderDeadlineService(
                orderDeadlineRepository, processedEventService, Duration.ofMinutes(15), Duration.ofMinutes(10), false);
        Mockito.when(processedEventService.markIfUnseen(
                Mockito.eq("order-timeout-merchant-consumer"), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyString(), Mockito.anyLong())).thenReturn(true);

        acceptTimeoutOff.handle(merchantEvent(OCCURRED_AT));

        Mockito.verifyNoInteractions(orderDeadlineRepository);
    }


    @Test
    void duplicateMerchantOrderReceived_shouldNotTouchTheDeadlines() {
        Mockito.when(processedEventService.markIfUnseen(
                Mockito.eq("order-timeout-merchant-consumer"), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyString(), Mockito.anyLong())).thenReturn(false);

        service.handle(merchantEvent(OCCURRED_AT));

        Mockito.verifyNoInteractions(orderDeadlineRepository);
    }


    @Test
    void malformedOrOtherMerchantEvents_shouldBeRejectedOrIgnored() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> service.handle(new MerchantEventEnvelope(
                "evt-1", 1, "merchant.order_received", "MERCHANT_ORDER", 10L, OCCURRED_AT, "evt-1", null, null)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> service.handle(new MerchantEventEnvelope(
                " ", 1, "merchant.order_received", "MERCHANT_ORDER", 10L, OCCURRED_AT, null, null,
                new MerchantEventPayload(10L, 2L, OCCURRED_AT, 1))));
        service.handle(new MerchantEventEnvelope(
                "evt-2", 1, "merchant.something_else", "MERCHANT_ORDER", 10L, OCCURRED_AT, "evt-2", null,
                new MerchantEventPayload(10L, 2L, OCCURRED_AT, 1)));
        service.handle((MerchantEventEnvelope) null);

        Mockito.verifyNoInteractions(processedEventService, orderDeadlineRepository);
    }


    @Test
    void orderCreated_whenTheAcceptTimeoutIsOff_shouldStillSetAPaymentDeadline() {
        OrderDeadlineService acceptTimeoutOff = new OrderDeadlineService(
                orderDeadlineRepository, processedEventService, Duration.ofMinutes(15), Duration.ofMinutes(10), false);
        OrderEventEnvelope created = event("order.created", "PLACED");
        Mockito.when(processedEventService.markIfUnseen("order-timeout-consumer", created)).thenReturn(true);

        acceptTimeoutOff.handle(created);

        Mockito.verify(orderDeadlineRepository).insertIfAbsent(
                Mockito.eq(10L), Mockito.eq("PAYMENT"), Mockito.eq(OCCURRED_AT.plusMinutes(15)), Mockito.any());
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


    private static MerchantEventEnvelope merchantEvent(LocalDateTime paidAt) {
        return new MerchantEventEnvelope(
                "evt-received", 1, "merchant.order_received", "MERCHANT_ORDER", 10L, paidAt.plusSeconds(2),
                "corr-1", null, new MerchantEventPayload(10L, 2L, paidAt, 1));
    }
}
