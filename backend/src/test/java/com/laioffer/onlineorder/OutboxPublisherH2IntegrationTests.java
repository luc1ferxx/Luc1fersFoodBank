package com.laioffer.onlineorder;


import com.laioffer.onlineorder.messaging.OutboxEventPublisher;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;


import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;


@SpringBootTest
@ActiveProfiles("h2")
class OutboxPublisherH2IntegrationTests {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisherH2IntegrationTests.class);

    private static final int EVENT_COUNT = 1000;
    private static final int KEY_COUNT = 125;
    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.session.store-type", () -> "none");
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.outbox.publisher-enabled", () -> "true");
        registry.add("app.outbox.publish-interval", () -> "1h");
        registry.add("app.outbox.batch-size", () -> "200");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private OutboxEventPublisher publisher;

    @Autowired
    private OrderEventOutboxService orderEventOutboxService;

    @Autowired
    private ApplicationMetricsService metricsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    @BeforeEach
    void clearOutbox() {
        jdbcTemplate.update("DELETE FROM outbox_events");
    }


    @Test
    void publishPendingEvents_shouldDrainBacklogInKeyOrderWithoutHoldingTransactionsDuringSends() {
        List<SentEvent> sentEvents = Collections.synchronizedList(new ArrayList<>());
        Mockito.when(kafkaTemplate.send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(invocation -> {
                    sentEvents.add(new SentEvent(
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            TransactionSynchronizationManager.isActualTransactionActive()
                    ));
                    return CompletableFuture.completedFuture(null);
                });
        for (int index = 0; index < EVENT_COUNT; index++) {
            // Pairs of consecutive events share a key, and every key recurs in several batches.
            insertEvent("order-" + (index / 2) % KEY_COUNT, "PENDING", 0);
        }

        long startedAt = System.nanoTime();
        publisher.publishPendingEvents();
        long elapsedNanos = System.nanoTime() - startedAt;

        LOGGER.info(
                "Published {} outbox events in {} ms: {} events/s",
                EVENT_COUNT,
                Duration.ofNanos(elapsedNanos).toMillis(),
                Math.round(EVENT_COUNT * 1_000_000_000d / elapsedNanos)
        );
        Assertions.assertEquals(EVENT_COUNT, sentEvents.size());
        Assertions.assertEquals(EVENT_COUNT, countEvents(
                "status = 'PUBLISHED' AND attempts = 1 AND published_at IS NOT NULL AND last_error IS NULL"
        ));
        Assertions.assertEquals(EVENT_COUNT, countEvents("1 = 1"));
        Assertions.assertEquals(payloadsByKeyInIdOrder(), payloadsByKey(sentEvents));
        Assertions.assertTrue(sentEvents.stream().noneMatch(SentEvent::transactionActive));
    }


    @Test
    void publishPendingEvents_whenBrokerIsDown_shouldReleaseUnsentEventsWithoutConsumingAttempts() {
        Mockito.when(kafkaTemplate.send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenThrow(new KafkaException(
                        "Send failed",
                        new TimeoutException("Topic order-events not present in metadata after 5000 ms.")
                ));
        Long first = insertEvent("order-1", "PENDING", 0);
        Long second = insertEvent("order-2", "PENDING", 0);
        Long third = insertEvent("order-1", "PENDING", 0);
        OutboxEventPublisher brokerDownPublisher = new OutboxEventPublisher(
                kafkaTemplate,
                Duration.ofSeconds(5),
                200,
                Duration.ofHours(1),
                Duration.ofSeconds(30),
                Duration.ofHours(1),
                orderEventOutboxService,
                metricsService
        );

        brokerDownPublisher.publishPendingEvents();

        Mockito.verify(kafkaTemplate, Mockito.times(1)).send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
        Map<String, Object> failed = event(first);
        Assertions.assertEquals("PENDING", failed.get("status"));
        Assertions.assertEquals(0, failed.get("attempts"));
        Assertions.assertTrue(failed.get("last_error").toString().contains("not present in metadata"));
        for (Long releasedId : List.of(second, third)) {
            Map<String, Object> released = event(releasedId);
            Assertions.assertEquals("PENDING", released.get("status"));
            Assertions.assertEquals(0, released.get("attempts"));
            Assertions.assertNull(released.get("last_error"));
        }
    }


    @Test
    void releaseClaims_shouldReturnOnlyTheClaimsProcessingEventsToPendingAndGiveBackTheirAttempt() {
        Long claimed = insertEvent("order-1", "PROCESSING", 2, "claim-a");
        Long claimedWithoutAttempts = insertEvent("order-2", "PROCESSING", 0, "claim-a");
        Long published = insertEvent("order-3", "PUBLISHED", 1, null);
        Long reclaimed = insertEvent("order-4", "PROCESSING", 1, "claim-b");

        orderEventOutboxService.releaseClaims(
                "claim-a",
                List.of(claimed, claimedWithoutAttempts, published, reclaimed)
        );

        Assertions.assertEquals("PENDING", event(claimed).get("status"));
        Assertions.assertEquals(1, event(claimed).get("attempts"));
        Assertions.assertNull(event(claimed).get("claim_token"));
        Assertions.assertEquals("PENDING", event(claimedWithoutAttempts).get("status"));
        Assertions.assertEquals(0, event(claimedWithoutAttempts).get("attempts"));
        Assertions.assertEquals("PUBLISHED", event(published).get("status"));
        Assertions.assertEquals(1, event(published).get("attempts"));
        // Another claim holds it: claim-a expired and the event was claimed again.
        Assertions.assertEquals("PROCESSING", event(reclaimed).get("status"));
        Assertions.assertEquals(1, event(reclaimed).get("attempts"));
        Assertions.assertEquals("claim-b", event(reclaimed).get("claim_token"));
    }


    @Test
    void releaseFailedClaims_shouldReturnOnlyTheClaimsProcessingEventsToPendingWithTheirError() {
        Long claimed = insertEvent("order-1", "PROCESSING", 2, "claim-a");
        Long published = insertEvent("order-2", "PUBLISHED", 1, null);
        Long reclaimed = insertEvent("order-3", "PROCESSING", 1, "claim-b");

        orderEventOutboxService.releaseFailedClaims(
                "claim-a",
                List.of(claimed, published, reclaimed),
                "Kafka timed out"
        );

        Assertions.assertEquals("PENDING", event(claimed).get("status"));
        Assertions.assertEquals(1, event(claimed).get("attempts"));
        Assertions.assertEquals("Kafka timed out", event(claimed).get("last_error"));
        Assertions.assertEquals("PUBLISHED", event(published).get("status"));
        Assertions.assertNull(event(published).get("last_error"));
        Assertions.assertEquals("PROCESSING", event(reclaimed).get("status"));
        Assertions.assertNull(event(reclaimed).get("last_error"));
    }


    @Test
    void outboxStatus_shouldRefuseAStatusTheCodeDoesNotWrite() {
        Long event = insertEvent("order-1", "PENDING", 0, null);

        Assertions.assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("UPDATE outbox_events SET status = 'SENT' WHERE id = ?", event)
        );
    }


    private Long insertEvent(String eventKey, String status, int attempts) {
        return insertEvent(eventKey, status, attempts, null);
    }


    private Long insertEvent(String eventKey, String status, int attempts, String claimToken) {
        String eventId = "evt-h2-" + EVENT_SEQUENCE.incrementAndGet();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbcTemplate.update(
                """
                        INSERT INTO outbox_events (
                            aggregate_type, aggregate_id, event_id, topic, event_key, event_type, payload,
                            status, attempts, created_at, updated_at, claim_token
                        )
                        VALUES ('ORDER', ?, ?, 'order-events', ?, 'order.created', ?, ?, ?, ?, ?, ?)
                        """,
                Long.valueOf(eventKey.replaceAll("\\D", "")),
                eventId,
                eventKey,
                "{\"event_id\":\"" + eventId + "\"}",
                status,
                attempts,
                now,
                now,
                claimToken
        );
        return jdbcTemplate.queryForObject("SELECT id FROM outbox_events WHERE event_id = ?", Long.class, eventId);
    }


    private Map<String, Object> event(Long id) {
        return jdbcTemplate.queryForMap(
                "SELECT status, attempts, last_error, claim_token FROM outbox_events WHERE id = ?",
                id
        );
    }


    private long countEvents(String condition) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE " + condition, Long.class);
        return count == null ? 0 : count;
    }


    private Map<String, List<String>> payloadsByKeyInIdOrder() {
        Map<String, List<String>> payloadsByKey = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT event_key, payload FROM outbox_events ORDER BY id",
                resultSet -> {
                    payloadsByKey.computeIfAbsent(resultSet.getString("event_key"), key -> new ArrayList<>())
                            .add(resultSet.getString("payload"));
                }
        );
        return payloadsByKey;
    }


    private Map<String, List<String>> payloadsByKey(List<SentEvent> sentEvents) {
        Map<String, List<String>> payloadsByKey = new LinkedHashMap<>();
        for (SentEvent sentEvent : sentEvents) {
            payloadsByKey.computeIfAbsent(sentEvent.key(), key -> new ArrayList<>()).add(sentEvent.payload());
        }
        return payloadsByKey;
    }


    private record SentEvent(String key, String payload, boolean transactionActive) {
    }
}
