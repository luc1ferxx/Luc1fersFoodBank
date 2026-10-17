package com.laioffer.onlineorder.messaging.contract;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;


class EventSchemaCatalogTests {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();


    @Test
    void parse_shouldReadTopicsAndVersionsInOrder() {
        EventSchemaCatalog catalog = parse("""
                {"envelope":"common/envelope/v1.json","event_types":{
                  "order.paid":{"topic":"order-events","versions":{"2":"BACKWARD","1":"INITIAL","3":"BREAKING"}}}}
                """);

        EventSchemaCatalog.EventTypeContract paid = catalog.eventTypes().get("order.paid");
        Assertions.assertEquals(EventTopic.ORDER_EVENTS, paid.topic());
        Assertions.assertEquals(1, paid.oldestVersion());
        Assertions.assertEquals(3, paid.latestVersion());
        Assertions.assertEquals(EventSchemaCatalog.Compatibility.BREAKING, paid.versions().get(3));
        Assertions.assertEquals("order-events/order.paid/v2.json", paid.schemaPath(2));
    }


    @Test
    void parse_aMalformedCatalog_shouldFailWithTheReason() {
        assertRejected("{\"event_types\":{}}", "names no envelope schema");
        assertRejected("{\"envelope\":\"e.json\",\"event_types\":{}}", "lists no event types");
        assertRejected(catalog("rider-events", "{\"1\":\"INITIAL\"}"), "unknown topic: 'rider-events'");
        assertRejected(catalog("order-events", "{}"), "lists no schema versions");
        assertRejected(catalog("order-events", "{\"one\":\"INITIAL\"}"), "invalid version: 'one'");
        assertRejected(catalog("order-events", "{\"0\":\"INITIAL\"}"), "invalid version: '0'");
        assertRejected(catalog("order-events", "{\"1\":\"FORWARD\"}"), "unknown compatibility: 'FORWARD'");
        assertRejected(catalog("order-events", "{\"1\":\"INITIAL\",\"3\":\"BACKWARD\"}"), "has no version 2");
        assertRejected(catalog("order-events", "{\"1\":\"BACKWARD\"}"), "exactly version 1 is INITIAL");
        assertRejected(catalog("order-events", "{\"1\":\"INITIAL\",\"2\":\"INITIAL\"}"), "exactly version 1 is INITIAL");
    }


    private static String catalog(String topic, String versions) {
        return "{\"envelope\":\"e.json\",\"event_types\":{\"order.paid\":{\"topic\":\"" + topic + "\",\"versions\":"
                + versions + "}}}";
    }


    private static void assertRejected(String catalog, String reason) {
        IllegalStateException failure = Assertions.assertThrows(IllegalStateException.class, () -> parse(catalog));
        Assertions.assertTrue(failure.getMessage().contains(reason), failure.getMessage());
    }


    private static EventSchemaCatalog parse(String catalog) {
        return EventSchemaCatalog.parse(MAPPER.readTree(catalog));
    }
}
