package com.laioffer.onlineorder.fulfilment.notification;


import com.laioffer.onlineorder.messaging.contract.EventSchemaViolationException;
import com.laioffer.onlineorder.messaging.contract.EventTestSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Objects;


@ExtendWith(MockitoExtension.class)
class AfterSalesNotificationConsumerTests {

    private static final String CASE_OPENED_SAMPLE =
            "/event-samples/after-sales-events/aftersales.case_opened/v1/current.json";

    @Mock
    private AfterSalesNotificationService afterSalesNotificationService;


    @Test
    void consume_shouldReadTheRecordThroughItsContractAndNotify() throws IOException {
        AfterSalesNotificationConsumer consumer =
                new AfterSalesNotificationConsumer(EventTestSupport.contracts(), afterSalesNotificationService);

        consumer.consume(sample(CASE_OPENED_SAMPLE));

        Mockito.verify(afterSalesNotificationService).handle(Mockito.argThat(event ->
                "aftersales.case_opened".equals(event.eventType())
                        && event.aggregateId().equals(501L)
                        && event.data().caseId().equals(501L)
                        && event.data().orderNo().equals(7_254_189_912_938_504_192L)
                        && event.data().requestedAmount().compareTo(new BigDecimal("10.59")) == 0
                        && "USD".equals(event.data().currency())
        ));
    }


    @Test
    void consume_shouldThrowANonRetryableViolationForARecordThatBreaksItsContract() throws IOException {
        AfterSalesNotificationConsumer consumer =
                new AfterSalesNotificationConsumer(EventTestSupport.contracts(), afterSalesNotificationService);

        Assertions.assertThrows(EventSchemaViolationException.class, () -> consumer.consume("{not-json}"));
        Assertions.assertThrows(EventSchemaViolationException.class, () -> consumer.consume(
                sample(CASE_OPENED_SAMPLE).replace("\"currency\": \"USD\"", "\"currency\": \"usd\"")));
        Mockito.verifyNoInteractions(afterSalesNotificationService);
    }


    @Test
    void consume_whenTheNotificationFails_shouldThrowForTheRetryingErrorHandler() throws IOException {
        AfterSalesNotificationConsumer consumer =
                new AfterSalesNotificationConsumer(EventTestSupport.contracts(), afterSalesNotificationService);
        Mockito.doThrow(new IllegalStateException("database unavailable"))
                .when(afterSalesNotificationService).handle(Mockito.any());

        IllegalStateException failure = Assertions.assertThrows(IllegalStateException.class,
                () -> consumer.consume(sample(CASE_OPENED_SAMPLE)));

        Assertions.assertEquals("Unable to process after-sales event for notifications", failure.getMessage());
        Assertions.assertEquals("database unavailable", failure.getCause().getMessage());
    }


    private static String sample(String path) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                AfterSalesNotificationConsumerTests.class.getResourceAsStream(path), path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
