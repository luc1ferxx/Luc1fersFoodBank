package com.laioffer.onlineorder;


import com.laioffer.onlineorder.messaging.contract.EventTestSupport;
import com.laioffer.onlineorder.service.DeadLetterEventService;
import com.laioffer.onlineorder.service.DeadLetterProcessingService;
import com.laioffer.onlineorder.service.ProcessedEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;


import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;


@ExtendWith(MockitoExtension.class)
class DeadLetterProcessingServiceTests {

    private static final String ENVELOPE = """
            {"event_id":"evt-42","event_version":1,"event_type":"order.paid","aggregate_type":"ORDER","aggregate_id":42}
            """;

    @Mock
    private DeadLetterEventService deadLetterEventService;

    @Mock
    private ProcessedEventService processedEventService;


    @Test
    void handle_shouldStoreAnUnknownEventWithItsEventId() {
        DeadLetterProcessingService service = service();
        Mockito.when(deadLetterEventService.recordAgainIfKnown("order-events", "evt-42", "boom")).thenReturn(false);

        service.handle("order-events", "order-events.dlt", "42", ENVELOPE, "boom");

        Mockito.verify(deadLetterEventService).record("order-events", "order-events.dlt", "42", "evt-42", ENVELOPE, "boom");
        Mockito.verifyNoInteractions(processedEventService);
    }


    @Test
    void handle_shouldUpdateTheStoredRowWhenTheEventIsDeadLetteredAgain() {
        DeadLetterProcessingService service = service();
        Mockito.when(deadLetterEventService.recordAgainIfKnown("order-events", "evt-42", "boom again")).thenReturn(true);

        service.handle("order-events", "order-events.dlt", "42", ENVELOPE, "boom again");

        Mockito.verify(deadLetterEventService, Mockito.never()).record(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()
        );
        Mockito.verifyNoInteractions(processedEventService);
    }


    /**
     * Every dead letter is read as an order event envelope for its event id (ADR 0002 section 10.3); an after-sales
     * envelope has the same fields, and the application's mapper ignores the data it does not know.
     */
    @Test
    void handle_shouldStoreAnAfterSalesEventWithItsEventId() throws IOException {
        String payload = sample("/event-samples/after-sales-events/aftersales.refund_approved/v1/current.json");
        DeadLetterProcessingService service = new DeadLetterProcessingService(
                deadLetterEventService, EventTestSupport.mapper(), processedEventService);
        String eventId = "5d1c9a7e-2b4f-4c8d-9e3a-000000000207";
        Mockito.when(deadLetterEventService.recordAgainIfKnown("after-sales-events", eventId, "boom")).thenReturn(false);

        service.handle("after-sales-events", "order-events.dlt", "1001", payload, "boom");

        Mockito.verify(deadLetterEventService)
                .record("after-sales-events", "order-events.dlt", "1001", eventId, payload, "boom");
        Mockito.verifyNoInteractions(processedEventService);
    }


    @Test
    void handle_shouldDeduplicateAPayloadWithoutAnEventIdByContentHash() {
        DeadLetterProcessingService service = service();
        Mockito.when(processedEventService.markIfUnseen(
                Mockito.eq("order-dlt-consumer"), Mockito.startsWith("raw:"), Mockito.isNull(), Mockito.eq("dead-letter"),
                Mockito.isNull(), Mockito.isNull()
        )).thenReturn(true, false);

        service.handle("order-events", "order-events.dlt", "42", "{not-json}", "boom");
        service.handle("order-events", "order-events.dlt", "42", "{not-json}", "boom");

        Mockito.verify(deadLetterEventService, Mockito.times(1))
                .record("order-events", "order-events.dlt", "42", null, "{not-json}", "boom");
        Mockito.verify(deadLetterEventService, Mockito.never()).recordAgainIfKnown(Mockito.any(), Mockito.any(), Mockito.any());
    }


    private static String sample(String path) throws IOException {
        try (InputStream sample = DeadLetterProcessingServiceTests.class.getResourceAsStream(path)) {
            return new String(sample.readAllBytes(), StandardCharsets.UTF_8);
        }
    }


    private DeadLetterProcessingService service() {
        return new DeadLetterProcessingService(
                deadLetterEventService,
                // The application's mapper writes and reads envelopes in snake_case.
                JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build(),
                processedEventService
        );
    }
}
