package com.laioffer.onlineorder.messaging.contract;


import com.laioffer.onlineorder.messaging.AfterSalesEventEnvelope;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;


/**
 * The topic names EventContracts is configured with (app.kafka.order-topic, app.fulfilment.delivery-topic,
 * app.fulfilment.merchant-topic, app.after-sales.topic): each one carries the events of its own contract topic, and two
 * contract topics cannot share a name.
 */
class EventContractsTopicsTests {

    private static final String CASE_OPENED = "/event-samples/after-sales-events/aftersales.case_opened/v1/current.json";


    @Test
    void anAfterSalesEvent_shouldBeWrittenOnlyToTheConfiguredAfterSalesTopic() throws IOException {
        EventContracts contracts = contracts("orders", "deliveries", "merchants", "refund-cases");
        AfterSalesEventEnvelope opened = EventTestSupport.mapper().readValue(sample(), AfterSalesEventEnvelope.class);

        String written = contracts.serialize("refund-cases", opened);

        Assertions.assertEquals(
                opened,
                contracts.read(EventTopic.AFTER_SALES_EVENTS, written, AfterSalesEventEnvelope.class)
        );
        EventSchemaViolationException onTheOrderTopic = Assertions.assertThrows(
                EventSchemaViolationException.class, () -> contracts.serialize("orders", opened));
        Assertions.assertTrue(
                onTheOrderTopic.getMessage().contains("is published on after-sales-events, not on order-events"),
                onTheOrderTopic.getMessage()
        );
        EventSchemaViolationException onTheDefaultName = Assertions.assertThrows(
                EventSchemaViolationException.class, () -> contracts.serialize("after-sales-events", opened));
        Assertions.assertTrue(
                onTheDefaultName.getMessage().contains("topic after-sales-events has no event contracts"),
                onTheDefaultName.getMessage()
        );
    }


    @Test
    void twoContractTopicsWithTheSameName_shouldFailTheStart() {
        IllegalStateException clash = Assertions.assertThrows(
                IllegalStateException.class,
                () -> contracts("order-events", "delivery-events", "merchant-events", "order-events")
        );

        Assertions.assertEquals(
                "The order, delivery, merchant and after-sales topics must have different names",
                clash.getMessage()
        );
    }


    /**
     * EventContracts as Spring builds it, from the four configured topic names.
     */
    private static EventContracts contracts(String order, String delivery, String merchant, String afterSales) {
        return new EventContracts(
                EventTestSupport.mapper(),
                new ApplicationMetricsService(new SimpleMeterRegistry()),
                order,
                delivery,
                merchant,
                afterSales
        );
    }


    private static String sample() throws IOException {
        try (InputStream sample = EventContractsTopicsTests.class.getResourceAsStream(CASE_OPENED)) {
            Assertions.assertNotNull(sample, CASE_OPENED);
            return new String(sample.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
