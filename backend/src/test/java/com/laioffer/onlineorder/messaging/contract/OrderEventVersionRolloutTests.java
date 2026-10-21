package com.laioffer.onlineorder.messaging.contract;


import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventItem;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;


import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;


/**
 * Order events version 2 (data.order_no) next to the previous release during a rollout (docs/operations.md section 8,
 * "Order events v2 and V14"). A consumer of the previous release knows order events up to version 1, and
 * {@link EventContracts#read} does two things with a version 2 event there: it checks it against version 1, the latest
 * version it knows ({@link EventSchemaRegistry#validate(EventTopic, JsonNode)}), and maps it onto its payload record,
 * which has no orderNo, with the application's JSON mapper. Both accept it, so the producer may switch first, as the
 * catalog says of BACKWARD versions; no older pod dead-letters a version 2 event.
 */
class OrderEventVersionRolloutTests {

    private static final List<String> ORDER_EVENT_TYPES = List.of(
            "order.created", "order.paid", "order.accepted", "order.preparing", "order.completed", "order.cancelled");


    @Test
    void aVersion2Event_shouldPassTheVersion1SchemaThePreviousReleaseChecksItAgainst() throws IOException {
        EventSchemaRegistry registry = EventSchemaRegistry.published();
        for (String eventType : ORDER_EVENT_TYPES) {
            JsonNode event = EventTestSupport.mapper().readTree(sample(eventType, 2));

            Assertions.assertEquals(List.of(), registry.validate(eventType, 1, event), eventType);
        }
    }


    @Test
    void aVersion2Event_shouldMapOntoThePreviousReleasesRecordWhichIgnoresTheOrderNumber() throws IOException {
        // The application mapper (Spring Boot's, with application.yml), which the previous release reads with too.
        Assertions.assertFalse(EventTestSupport.mapper().isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));

        PreviousOrderEvent event =
                EventTestSupport.mapper().readValue(sample("order.paid", 2), PreviousOrderEvent.class);

        Assertions.assertEquals(2, event.eventVersion());
        Assertions.assertEquals(1001L, event.aggregateId());
        Assertions.assertEquals(1L, event.data().restaurantId());
        Assertions.assertEquals(0, new BigDecimal("21.18").compareTo(event.data().totalPrice()));
    }


    @Test
    void thisRelease_shouldReadTheOrderNumberOfAVersion2EventAndNoneFromAVersion1Event() throws IOException {
        EventContracts contracts = EventTestSupport.contracts();

        OrderEventEnvelope v2 =
                contracts.read(EventTopic.ORDER_EVENTS, sample("order.paid", 2), OrderEventEnvelope.class);
        OrderEventEnvelope v1 =
                contracts.read(EventTopic.ORDER_EVENTS, sample("order.paid", 1), OrderEventEnvelope.class);

        Assertions.assertEquals(7_254_189_912_938_504_192L, v2.data().orderNo());
        Assertions.assertNull(v1.data().orderNo());
    }


    @Test
    void aNonNumericOrderNumber_shouldBreakTheVersion2Contract() throws IOException {
        String event = sample("order.paid", 2).replace("\"7254189912938504192\"", "\"A-17\"");

        EventSchemaViolationException violation = Assertions.assertThrows(EventSchemaViolationException.class,
                () -> EventTestSupport.contracts().read(EventTopic.ORDER_EVENTS, event, OrderEventEnvelope.class));

        Assertions.assertTrue(violation.getMessage().contains("/data/order_no"), violation.getMessage());
    }


    private static String sample(String eventType, int version) throws IOException {
        String path = "/event-samples/order-events/" + eventType + "/v" + version + "/current.json";
        try (InputStream sample = OrderEventVersionRolloutTests.class.getResourceAsStream(path)) {
            Assertions.assertNotNull(sample, path);
            return new String(sample.readAllBytes(), StandardCharsets.UTF_8);
        }
    }


    /**
     * The order event envelope of the previous release, whose payload has no order number.
     */
    record PreviousOrderEvent(
            String eventId,
            Integer eventVersion,
            String eventType,
            String aggregateType,
            Long aggregateId,
            Long sequence,
            LocalDateTime occurredAt,
            String correlationId,
            String idempotencyKey,
            PreviousOrderData data
    ) {
    }


    record PreviousOrderData(
            Long customerId,
            String status,
            BigDecimal totalPrice,
            String currency,
            LocalDateTime createdAt,
            List<OrderEventItem> items,
            Long restaurantId,
            String reason
    ) {
    }
}
