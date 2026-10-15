package com.laioffer.onlineorder;


import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.service.ProcessedEventService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;


/**
 * Exercises {@code ProcessedEventRepository.insertIfAbsent} against a real database. The statement has to
 * parse and behave the same on PostgreSQL and on H2 in PostgreSQL mode (the {@code h2} profile), so the
 * cases live here and each database gets a subclass.
 */
abstract class AbstractProcessedEventDedupIntegrationTests {

    @Autowired
    private ProcessedEventService processedEventService;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    @Test
    void markIfUnseen_withEnvelope_shouldInsertOnceAndReportTheRepeatAsSeen() {
        String consumer = "dedup-test-consumer-" + System.nanoTime();
        OrderEventEnvelope event = buildEvent("evt-" + System.nanoTime());

        Assertions.assertTrue(processedEventService.markIfUnseen(consumer, event));
        Assertions.assertFalse(processedEventService.markIfUnseen(consumer, event));

        List<Map<String, Object>> rows = rowsFor(consumer, event.eventId());
        Assertions.assertEquals(1, rows.size());
        Assertions.assertEquals(event.eventId(), rows.get(0).get("event_id"));
        Assertions.assertEquals("order.paid", rows.get(0).get("event_type"));
        Assertions.assertEquals("ORDER", rows.get(0).get("aggregate_type"));
        Assertions.assertEquals(42L, ((Number) rows.get(0).get("aggregate_id")).longValue());
    }


    @Test
    void markIfUnseen_withRawDedupKeyAndNullColumns_shouldInsertOnceAndReportTheRepeatAsSeen() {
        // The dead-letter path for a payload that is not an envelope: no event id, no aggregate.
        String consumer = "dedup-test-consumer-" + System.nanoTime();
        String dedupKey = "raw:" + Long.toHexString(System.nanoTime());

        Assertions.assertTrue(processedEventService.markIfUnseen(consumer, dedupKey, null, "dead-letter", null, null));
        Assertions.assertFalse(processedEventService.markIfUnseen(consumer, dedupKey, null, "dead-letter", null, null));

        List<Map<String, Object>> rows = rowsFor(consumer, dedupKey);
        Assertions.assertEquals(1, rows.size());
        Assertions.assertNull(rows.get(0).get("event_id"));
        Assertions.assertNull(rows.get(0).get("aggregate_type"));
        Assertions.assertNull(rows.get(0).get("aggregate_id"));
    }


    @Test
    void markIfUnseen_withSameKeyForAnotherConsumer_shouldInsertForEachConsumer() {
        // Uniqueness is per (consumer_name, dedup_key); a second consumer sees the same event as new.
        String dedupKey = "shared-" + System.nanoTime();
        String firstConsumer = "dedup-test-consumer-a-" + System.nanoTime();
        String secondConsumer = "dedup-test-consumer-b-" + System.nanoTime();

        Assertions.assertTrue(processedEventService.markIfUnseen(firstConsumer, dedupKey, null, "order.paid", null, null));
        Assertions.assertTrue(processedEventService.markIfUnseen(secondConsumer, dedupKey, null, "order.paid", null, null));
        Assertions.assertFalse(processedEventService.markIfUnseen(firstConsumer, dedupKey, null, "order.paid", null, null));

        Assertions.assertEquals(1, rowsFor(firstConsumer, dedupKey).size());
        Assertions.assertEquals(1, rowsFor(secondConsumer, dedupKey).size());
    }


    private List<Map<String, Object>> rowsFor(String consumer, String dedupKey) {
        return jdbcTemplate.queryForList(
                "SELECT event_id, event_type, aggregate_type, aggregate_id FROM processed_events WHERE consumer_name = ? AND dedup_key = ?",
                consumer,
                dedupKey
        );
    }


    private static OrderEventEnvelope buildEvent(String eventId) {
        return new OrderEventEnvelope(
                eventId,
                1,
                "order.paid",
                "ORDER",
                42L,
                LocalDateTime.now(),
                "corr-" + eventId,
                null,
                null
        );
    }
}
