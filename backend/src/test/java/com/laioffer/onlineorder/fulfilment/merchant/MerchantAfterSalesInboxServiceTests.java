package com.laioffer.onlineorder.fulfilment.merchant;


import com.laioffer.onlineorder.fulfilment.FulfilmentEvents;
import com.laioffer.onlineorder.messaging.AfterSalesCaseLinePayload;
import com.laioffer.onlineorder.messaging.AfterSalesCasePayload;
import com.laioffer.onlineorder.messaging.AfterSalesEventEnvelope;
import com.laioffer.onlineorder.service.EventSequenceTracker;
import com.laioffer.onlineorder.service.ProcessedEventService;
import com.laioffer.onlineorder.push.PushRecipient;
import com.laioffer.onlineorder.push.PushSignals;
import com.laioffer.onlineorder.push.PushTopic;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


/**
 * The merchant's after-sales inbox built from after-sales events (ADR 0002 section 10.3): only customers' requests,
 * each event applied once (processed_events) and only when it is not older than the last event of its case this
 * consumer applied, then upserted by the case version it carries.
 */
@ExtendWith(MockitoExtension.class)
class MerchantAfterSalesInboxServiceTests {

    private static final String CONSUMER = "merchant-aftersales-consumer";
    private static final long CASE_ID = 300L;
    private static final LocalDateTime OPENED_AT = LocalDateTime.of(2026, 10, 20, 9, 0);

    @Mock
    private MerchantAfterSalesRepository repository;

    @Mock
    private ProcessedEventService processedEventService;

    @Mock
    private EventSequenceTracker sequenceTracker;

    @Mock
    private PushSignals pushSignals;

    private MerchantAfterSalesInboxService service;


    @BeforeEach
    void setup() {
        service = new MerchantAfterSalesInboxService(
                new MerchantAfterSalesProjection(repository, snakeCase(), pushSignals),
                processedEventService,
                sequenceTracker
        );
    }


    @Test
    void theFirstEventOfACase_shouldAddItsRowAfterTheMarkAndTheSequenceCheck() {
        AfterSalesEventEnvelope opened = event(FulfilmentEvents.AFTER_SALES_CASE_OPENED, "CUSTOMER_REQUEST",
                "PENDING_MERCHANT", 0L, 1L);
        stubUnseenAndCurrent(opened);
        Mockito.when(repository.insertIfAbsent(
                Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(),
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.anyLong(), Mockito.any(), Mockito.any())).thenReturn(1);

        service.handle(opened);

        ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
        InOrder steps = Mockito.inOrder(processedEventService, sequenceTracker, repository);
        steps.verify(processedEventService).markIfUnseen(
                CONSUMER, opened.eventId(), opened.eventId(), opened.eventType(), "AFTER_SALES_CASE", CASE_ID);
        steps.verify(sequenceTracker).advance(CONSUMER, opened);
        steps.verify(repository).insertIfAbsent(
                Mockito.eq(CASE_ID),
                Mockito.eq(30L),
                Mockito.eq(9030L),
                Mockito.eq(2L),
                Mockito.eq(3L),
                Mockito.eq("PENDING_MERCHANT"),
                Mockito.eq("MISSING_ITEM"),
                Mockito.eq("The fries were missing."),
                lines.capture(),
                Mockito.eq(new BigDecimal("16.00")),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.eq("USD"),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.eq(OPENED_AT.plusHours(24)),
                Mockito.eq(0L),
                Mockito.eq(OPENED_AT),
                Mockito.any(LocalDateTime.class)
        );
        Mockito.verifyNoMoreInteractions(repository);
        Mockito.verify(pushSignals).publish(PushRecipient.restaurant(2L), PushTopic.MERCHANT_AFTER_SALES);
        // Stored snake_case like every JSON of the application, each line with the case's currency.
        Assertions.assertTrue(lines.getValue().contains("\"menu_item_name\":\"Whopper Meal\""), lines.getValue());
        Assertions.assertEquals(
                List.of(new MerchantAfterSalesCaseDto.Line(
                        77L, "Whopper Meal", 2, new BigDecimal("8.00"), new BigDecimal("16.00"), "USD")),
                List.of(snakeCase().readValue(lines.getValue(), MerchantAfterSalesCaseDto.Line[].class))
        );
    }


    @Test
    void aLaterEventOfAKnownCase_shouldMoveTheRowToItsVersionOnlyIfItIsNewer() {
        AfterSalesEventEnvelope offered = event(FulfilmentEvents.AFTER_SALES_OFFER_MADE, "CUSTOMER_REQUEST",
                "COUNTER_OFFERED", 1L, 2L);
        stubUnseenAndCurrent(offered);

        service.handle(offered);

        // The row exists: the insert adds nothing, the update is conditional on the row's version being older.
        Mockito.verify(repository).updateIfNewer(
                Mockito.eq(CASE_ID),
                Mockito.eq("COUNTER_OFFERED"),
                Mockito.eq(new BigDecimal("5.00")),
                Mockito.isNull(),
                Mockito.eq("OFFERED"),
                Mockito.eq("Half the fries"),
                Mockito.eq(OPENED_AT.plusHours(30)),
                Mockito.eq(1L),
                Mockito.any(LocalDateTime.class)
        );
        // The mocked update changed no row (the row already had this version or a newer one): nothing to re-read.
        Mockito.verifyNoInteractions(pushSignals);
    }


    @Test
    void aDuplicateEvent_shouldChangeNothing() {
        AfterSalesEventEnvelope opened = event(FulfilmentEvents.AFTER_SALES_CASE_OPENED, "CUSTOMER_REQUEST",
                "PENDING_MERCHANT", 0L, 1L);
        Mockito.when(processedEventService.markIfUnseen(
                CONSUMER, opened.eventId(), opened.eventId(), opened.eventType(), "AFTER_SALES_CASE", CASE_ID))
                .thenReturn(false);

        service.handle(opened);

        Mockito.verifyNoInteractions(sequenceTracker, repository);
    }


    @Test
    void aStaleEvent_shouldBeSkippedAfterItsMark() {
        AfterSalesEventEnvelope opened = event(FulfilmentEvents.AFTER_SALES_CASE_OPENED, "CUSTOMER_REQUEST",
                "PENDING_MERCHANT", 0L, 1L);
        Mockito.when(processedEventService.markIfUnseen(
                CONSUMER, opened.eventId(), opened.eventId(), opened.eventType(), "AFTER_SALES_CASE", CASE_ID))
                .thenReturn(true);
        Mockito.when(sequenceTracker.advance(CONSUMER, opened)).thenReturn(false);

        service.handle(opened);

        Mockito.verifyNoInteractions(repository);
    }


    @Test
    void theAutomaticRefundOfACancelledOrderAndUnknownEvents_shouldBeDroppedBeforeTheMark() {
        service.handle(event(FulfilmentEvents.AFTER_SALES_CASE_OPENED, "ORDER_CANCELLED", "REFUNDING", 0L, 1L));
        service.handle(event(FulfilmentEvents.AFTER_SALES_CASE_REFUNDED, "ORDER_CANCELLED", "REFUNDED", 1L, 2L));
        service.handle(event("aftersales.something_else", "CUSTOMER_REQUEST", "PENDING_MERCHANT", 0L, 1L));
        service.handle(null);

        Mockito.verifyNoInteractions(processedEventService, sequenceTracker, repository);
    }


    @Test
    void everyAfterSalesEventType_shouldBeApplied() {
        for (String type : List.of(
                FulfilmentEvents.AFTER_SALES_CASE_OPENED,
                FulfilmentEvents.AFTER_SALES_OFFER_MADE,
                FulfilmentEvents.AFTER_SALES_REQUEST_REJECTED,
                FulfilmentEvents.AFTER_SALES_CASE_ESCALATED,
                FulfilmentEvents.AFTER_SALES_REFUND_APPROVED,
                FulfilmentEvents.AFTER_SALES_CASE_REFUNDED,
                FulfilmentEvents.AFTER_SALES_CASE_CLOSED
        )) {
            AfterSalesEventEnvelope event = event(type, "CUSTOMER_REQUEST", "PENDING_MERCHANT", 0L, 1L);
            stubUnseenAndCurrent(event);
            service.handle(event);
            Mockito.verify(sequenceTracker).advance(CONSUMER, event);
        }
        Mockito.verify(repository, Mockito.times(7)).updateIfNewer(
                Mockito.eq(CASE_ID), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any());
    }


    private static JsonMapper snakeCase() {
        return JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    }


    private void stubUnseenAndCurrent(AfterSalesEventEnvelope event) {
        Mockito.when(processedEventService.markIfUnseen(
                CONSUMER, event.eventId(), event.eventId(), event.eventType(), "AFTER_SALES_CASE", CASE_ID))
                .thenReturn(true);
        Mockito.when(sequenceTracker.advance(CONSUMER, event)).thenReturn(true);
    }


    private static AfterSalesEventEnvelope event(String type, String kind, String status, long version, long sequence) {
        boolean offered = "COUNTER_OFFERED".equals(status);
        AfterSalesCasePayload data = new AfterSalesCasePayload(
                CASE_ID,
                30L,
                9030L,
                3L,
                2L,
                kind,
                1,
                status,
                "OPEN",
                "CUSTOMER",
                3L,
                version,
                "CUSTOMER_REQUEST".equals(kind) ? "MISSING_ITEM" : null,
                "CUSTOMER_REQUEST".equals(kind) ? "The fries were missing." : null,
                new BigDecimal("16.00"),
                offered ? new BigDecimal("5.00") : null,
                null,
                null,
                "USD",
                offered ? "OFFERED" : null,
                offered ? "Half the fries" : null,
                null,
                null,
                offered ? OPENED_AT.plusHours(30) : OPENED_AT.plusHours(24),
                // An event line from before lines carried a currency: the case's applies.
                List.of(new AfterSalesCaseLinePayload(
                        77L, "Whopper Meal", 2, new BigDecimal("8.00"), new BigDecimal("16.00"), null)),
                OPENED_AT
        );
        return new AfterSalesEventEnvelope(type + "-" + version + "-" + kind, 1, type, "AFTER_SALES_CASE", CASE_ID,
                sequence, OPENED_AT, "correlation", null, data);
    }
}
