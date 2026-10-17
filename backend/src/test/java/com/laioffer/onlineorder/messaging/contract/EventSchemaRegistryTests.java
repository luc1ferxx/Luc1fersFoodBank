package com.laioffer.onlineorder.messaging.contract;


import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;


import java.util.List;


class EventSchemaRegistryTests {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String ORDER_PAID = """
            {"event_id":"evt-1","event_version":%s,"event_type":"order.paid","aggregate_type":"ORDER",
             "aggregate_id":7,"sequence":2,"occurred_at":"2026-04-25T12:01:30.412873",
             "data":{"customer_id":3,"status":"PAID","total_price":21.18,"created_at":"2026-04-25T12:00:00",
                     "items":[{"menu_item_id":2,"menu_item_name":"Whopper Meal","price":10.59,"quantity":2}],
                     "restaurant_id":1%s}}
            """;


    @Test
    void validate_aPublishedVersion_shouldUseExactlyThatSchema() {
        EventValidation validation = registry().validate(EventTopic.ORDER_EVENTS, json(ORDER_PAID.formatted("1", "")));

        Assertions.assertTrue(validation.valid(), validation.violations()::toString);
        Assertions.assertTrue(validation.exactVersion());
        Assertions.assertEquals("order.paid", validation.eventType());
        Assertions.assertEquals(1, validation.version());
        Assertions.assertEquals("order-events/order.paid/v1.json", validation.schemaPath());
    }


    @Test
    void validate_aNewerVersion_shouldUseTheLatestKnownSchemaSoACompatibleAdditionPasses() {
        // A v7 producer added "tip"; this instance knows v1 only. Schemas are open, so the event passes.
        EventValidation validation = registry().validate(
                EventTopic.ORDER_EVENTS, json(ORDER_PAID.formatted("7", ",\"tip\":1.5")));

        Assertions.assertTrue(validation.valid(), validation.violations()::toString);
        Assertions.assertFalse(validation.exactVersion());
        Assertions.assertEquals("order-events/order.paid/v1.json", validation.schemaPath());
    }


    @Test
    void validate_aVersionOlderThanTheOldestListed_shouldBeRejected() {
        EventValidation validation = registry().validate(EventTopic.ORDER_EVENTS, json(ORDER_PAID.formatted("0", "")));

        Assertions.assertFalse(validation.valid());
        Assertions.assertTrue(validation.violations().get(0).contains("version 0 of order.paid is no longer supported"),
                validation.violations()::toString);
    }


    @Test
    void validate_anEventTypeOnAnotherTopic_shouldBeRejected() {
        EventValidation validation = registry().validate(EventTopic.DELIVERY_EVENTS, json(ORDER_PAID.formatted("1", "")));

        Assertions.assertEquals(
                List.of("/event_type: order.paid is published on order-events, not on delivery-events"),
                validation.violations()
        );
    }


    @Test
    void validate_anUnknownEventType_shouldOnlyCheckTheEnvelope() {
        EventValidation unknownButWellFormed = registry().validate(EventTopic.ORDER_EVENTS, json("""
                {"event_id":"evt-9","event_version":1,"event_type":"order.refunded","aggregate_type":"ORDER",
                 "aggregate_id":7,"occurred_at":"2026-04-25T12:00:00","data":{"anything":true}}
                """));
        EventValidation unknownAndBroken = registry().validate(EventTopic.ORDER_EVENTS, json("""
                {"event_version":"one","event_type":"order.refunded","aggregate_type":"ORDER","aggregate_id":-1,
                 "occurred_at":"25/04/2026","data":[]}
                """));

        Assertions.assertTrue(unknownButWellFormed.valid(), unknownButWellFormed.violations()::toString);
        Assertions.assertFalse(unknownButWellFormed.exactVersion());
        Assertions.assertEquals("common/envelope/v1.json", unknownButWellFormed.schemaPath());
        String violations = String.join("\n", unknownAndBroken.violations());
        Assertions.assertTrue(violations.contains("event_id"), violations);
        Assertions.assertTrue(violations.contains("/event_version"), violations);
        Assertions.assertTrue(violations.contains("/aggregate_id"), violations);
        Assertions.assertTrue(violations.contains("/occurred_at"), violations);
        Assertions.assertTrue(violations.contains("/data"), violations);
    }


    @Test
    void validate_aRecordThatIsNotAnObject_shouldBeRejected() {
        Assertions.assertFalse(registry().validate(EventTopic.ORDER_EVENTS, json("[1, 2]")).valid());
        Assertions.assertFalse(registry().validate(EventTopic.ORDER_EVENTS, json("\"order.paid\"")).valid());
    }


    @Test
    void validate_moneyAmounts_shouldAcceptAnIso4217CurrencyNextToThemAndRejectAnythingElse() {
        Assertions.assertTrue(registry().validate(EventTopic.ORDER_EVENTS,
                json(ORDER_PAID.formatted("1", ",\"currency\":\"EUR\""))).valid());

        EventValidation lowerCase = registry().validate(EventTopic.ORDER_EVENTS,
                json(ORDER_PAID.formatted("1", ",\"currency\":\"eur\"")));
        EventValidation negative = registry().validate(EventTopic.ORDER_EVENTS,
                json(ORDER_PAID.formatted("1", "").replace("21.18", "-1")));

        Assertions.assertTrue(lowerCase.violations().get(0).startsWith("/data/currency"), lowerCase.violations()::toString);
        Assertions.assertTrue(negative.violations().get(0).startsWith("/data/total_price"), negative.violations()::toString);
    }


    @Test
    void validateAgainstAVersion_whenItIsNotPublished_shouldThrow() {
        JsonNode event = json(ORDER_PAID.formatted("1", ""));

        Assertions.assertEquals(List.of(), registry().validate("order.paid", 1, event));
        Assertions.assertThrows(IllegalArgumentException.class, () -> registry().validate("order.paid", 2, event));
        Assertions.assertThrows(IllegalArgumentException.class, () -> registry().validate("order.refunded", 1, event));
    }


    @Test
    void load_withoutACatalog_shouldFail() {
        IllegalStateException failure = Assertions.assertThrows(
                IllegalStateException.class, () -> EventSchemaRegistry.load("no-such-schemas/"));

        Assertions.assertTrue(failure.getMessage().contains("no-such-schemas/catalog.json"), failure.getMessage());
    }


    @Test
    void read_whenTheRecordIsEmptyOrCannotBeMapped_shouldThrowANonRetryableViolation() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        EventContracts contracts = EventTestSupport.contracts(meterRegistry);

        Assertions.assertThrows(EventSchemaViolationException.class,
                () -> contracts.read(EventTopic.ORDER_EVENTS, null, OrderEventEnvelope.class));
        Assertions.assertThrows(EventSchemaViolationException.class,
                () -> contracts.read(EventTopic.ORDER_EVENTS, " ", OrderEventEnvelope.class));
        // An unknown event type passes the envelope check, but its data does not fit the order event record.
        EventSchemaViolationException unmappable = Assertions.assertThrows(EventSchemaViolationException.class,
                () -> contracts.read(EventTopic.ORDER_EVENTS, """
                        {"event_id":"evt-9","event_version":1,"event_type":"order.refunded","aggregate_type":"ORDER",
                         "aggregate_id":7,"occurred_at":"2026-04-25T12:00:00","data":{"items":"none"}}
                        """, OrderEventEnvelope.class));

        Assertions.assertTrue(unmappable.getMessage().contains("cannot be read as OrderEventEnvelope"),
                unmappable.getMessage());
        Assertions.assertEquals(3.0, meterRegistry.get("onlineorder.events.schema_violations")
                .tag("side", "consumer")
                .counters()
                .stream()
                .mapToDouble(counter -> counter.count())
                .sum());
    }


    @Test
    void read_aDeliveryEventFromTheOrderTopic_shouldBeRejected() throws Exception {
        String delivered = new String(
                getClass().getResourceAsStream("/event-samples/delivery-events/delivery.delivered/v1/current.json")
                        .readAllBytes());

        DeliveryEventEnvelope event = EventTestSupport.contracts()
                .read(EventTopic.DELIVERY_EVENTS, delivered, DeliveryEventEnvelope.class);

        Assertions.assertEquals(3L, event.sequence());
        Assertions.assertThrows(EventSchemaViolationException.class, () -> EventTestSupport.contracts()
                .read(EventTopic.ORDER_EVENTS, delivered, OrderEventEnvelope.class));
    }


    @Test
    void violationMessage_shouldReportAtMostTenViolations() {
        List<String> violations = java.util.stream.IntStream.rangeClosed(1, 12).mapToObj(i -> "/v" + i).toList();

        EventSchemaViolationException exception = new EventSchemaViolationException("Event x", violations);

        Assertions.assertTrue(exception.getMessage().endsWith("/v10; and 2 more"), exception.getMessage());
        Assertions.assertEquals(12, exception.violations().size());
    }


    private static EventSchemaRegistry registry() {
        return EventSchemaRegistry.published();
    }


    private static JsonNode json(String content) {
        return MAPPER.readTree(content);
    }
}
