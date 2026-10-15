package com.laioffer.onlineorder;


import com.laioffer.onlineorder.service.MaintenanceCleanupService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;


import java.sql.Timestamp;
import java.time.LocalDateTime;


@SpringBootTest
@ActiveProfiles("h2")
class MaintenanceCleanupH2IntegrationTests {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
        registry.add("app.cleanup.enabled", () -> "true");
        // The scheduled run must not race the test; the test calls the service directly.
        registry.add("app.cleanup.initial-delay-ms", () -> "3600000");
        // Smaller than every expired set below, so each table needs several DELETE statements.
        registry.add("app.cleanup.batch-size", () -> "3");
    }

    @Autowired
    private MaintenanceCleanupService maintenanceCleanupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM processed_events");
        jdbcTemplate.update("DELETE FROM idempotency_requests");
        jdbcTemplate.update("DELETE FROM dead_letter_events");
    }


    @Test
    void cleanupExpiredRecords_shouldDeleteEveryExpiredRowAcrossSeveralBatches() {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 7; i++) {
            insertOutboxEvent("PUBLISHED", now.minusDays(10), now.minusDays(10));
        }
        insertOutboxEvent("PUBLISHED", now.minusDays(1), now.minusDays(1));
        insertOutboxEvent("PENDING", now.minusDays(10), null);
        insertOutboxEvent("FAILED", now.minusDays(10), null);

        for (int i = 0; i < 5; i++) {
            insertProcessedEvent(now.minusDays(20));
        }
        insertProcessedEvent(now.minusDays(1));

        long customerId = jdbcTemplate.queryForObject("SELECT MIN(id) FROM customers", Long.class);
        for (int i = 0; i < 4; i++) {
            insertIdempotencyRequest(customerId, now.minusDays(5));
        }
        insertIdempotencyRequest(customerId, now.minusHours(1));

        for (int i = 0; i < 4; i++) {
            insertDeadLetter("REPLAYED", now.minusDays(40));
        }
        insertDeadLetter("REPLAYED", now.minusDays(1));
        insertDeadLetter("PENDING", now.minusDays(40));

        maintenanceCleanupService.cleanupExpiredRecords();

        Assertions.assertEquals(
                3,
                count("SELECT COUNT(*) FROM outbox_events")
        );
        Assertions.assertEquals(
                0,
                count("SELECT COUNT(*) FROM outbox_events WHERE status = 'PUBLISHED' AND published_at < ?", now.minusDays(7))
        );
        Assertions.assertEquals(1, count("SELECT COUNT(*) FROM processed_events"));
        Assertions.assertEquals(1, count("SELECT COUNT(*) FROM idempotency_requests"));
        Assertions.assertEquals(2, count("SELECT COUNT(*) FROM dead_letter_events"));
        Assertions.assertEquals(1, count("SELECT COUNT(*) FROM dead_letter_events WHERE replay_status = 'PENDING'"));
    }


    private void insertOutboxEvent(String status, LocalDateTime updatedAt, LocalDateTime publishedAt) {
        String eventId = "cleanup-evt-" + System.nanoTime();
        jdbcTemplate.update(
                """
                        INSERT INTO outbox_events (
                            aggregate_type, aggregate_id, event_id, topic, event_key, event_type, payload,
                            status, attempts, last_error, created_at, updated_at, published_at
                        )
                        VALUES ('ORDER', 1, ?, 'order-events', '1', 'order.created', '{}', ?, 1, NULL, ?, ?, ?)
                        """,
                eventId,
                status,
                Timestamp.valueOf(updatedAt),
                Timestamp.valueOf(updatedAt),
                publishedAt == null ? null : Timestamp.valueOf(publishedAt)
        );
    }


    private void insertProcessedEvent(LocalDateTime processedAt) {
        jdbcTemplate.update(
                """
                        INSERT INTO processed_events (consumer_name, event_id, dedup_key, event_type, processed_at)
                        VALUES ('order-consumer', ?, ?, 'order.created', ?)
                        """,
                "processed-" + System.nanoTime(),
                "dedup-" + System.nanoTime(),
                Timestamp.valueOf(processedAt)
        );
    }


    private void insertIdempotencyRequest(long customerId, LocalDateTime updatedAt) {
        jdbcTemplate.update(
                """
                        INSERT INTO idempotency_requests (
                            customer_id, scope, idempotency_key, request_hash, status, order_id, created_at, updated_at
                        )
                        VALUES (?, 'checkout', ?, 'hash', 'SUCCEEDED', NULL, ?, ?)
                        """,
                customerId,
                "key-" + System.nanoTime(),
                Timestamp.valueOf(updatedAt),
                Timestamp.valueOf(updatedAt)
        );
    }


    private void insertDeadLetter(String replayStatus, LocalDateTime updatedAt) {
        jdbcTemplate.update(
                """
                        INSERT INTO dead_letter_events (
                            source_topic, dead_letter_topic, message_key, payload, replay_status, created_at, updated_at
                        )
                        VALUES ('order-events', 'order-events.dlt', '1', '{}', ?, ?, ?)
                        """,
                replayStatus,
                Timestamp.valueOf(updatedAt),
                Timestamp.valueOf(updatedAt)
        );
    }


    private long count(String sql, Object... args) {
        Object[] jdbcArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            jdbcArgs[i] = args[i] instanceof LocalDateTime time ? Timestamp.valueOf(time) : args[i];
        }
        return jdbcTemplate.queryForObject(sql, Long.class, jdbcArgs);
    }
}
