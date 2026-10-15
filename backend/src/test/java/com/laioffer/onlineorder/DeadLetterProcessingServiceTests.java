package com.laioffer.onlineorder;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.laioffer.onlineorder.service.DeadLetterEventService;
import com.laioffer.onlineorder.service.DeadLetterProcessingService;
import com.laioffer.onlineorder.service.ProcessedEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


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


    private DeadLetterProcessingService service() {
        return new DeadLetterProcessingService(
                deadLetterEventService,
                // The application's mapper writes and reads envelopes in snake_case.
                new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE),
                processedEventService
        );
    }
}
