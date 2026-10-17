package com.laioffer.onlineorder;


import com.laioffer.onlineorder.entity.OutboxEventEntity;
import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import com.laioffer.onlineorder.messaging.DeliveryEventPayload;
import com.laioffer.onlineorder.messaging.EventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventItem;
import com.laioffer.onlineorder.messaging.OrderEventPayload;
import com.laioffer.onlineorder.messaging.OutboxEventPublisher;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.OutboxEventRepository;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;


import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.LongStream;


/**
 * Per-aggregate event order through the outbox on real PostgreSQL: the sequence each event gets when it is written, and
 * the head-of-line claim that publishes an aggregate's events one at a time in that order, across publisher instances,
 * through failures. Kafka is a mock that records what it acknowledged, in acknowledgement order.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OutboxAggregateOrderingPostgresTests {

    private static final int MAX_ATTEMPTS = 3;

    @Container
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("onlineorder")
            .withUsername("postgres")
            .withPassword("secret");


    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.session.store-type", () -> "none");
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.outbox.publisher-enabled", () -> "false");
        registry.add("app.outbox.max-attempts", () -> String.valueOf(MAX_ATTEMPTS));
        registry.add("app.outbox.batch-size", () -> "7");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }


    @Autowired
    private OrderEventOutboxService outboxService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ApplicationMetricsService metricsService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private TransactionTemplate transaction;
    private ExecutorService kafkaAcks;

    /**
     * What "Kafka" acknowledged, per aggregate, in acknowledgement order: the sequence of every acknowledged event.
     */
    private final Map<String, List<Long>> acknowledged = new ConcurrentHashMap<>();


    @BeforeEach
    void setup() {
        transaction = new TransactionTemplate(transactionManager);
        kafkaAcks = Executors.newFixedThreadPool(8);
        jdbcTemplate.update("DELETE FROM outbox_events");
        acknowledged.clear();
    }


    @AfterEach
    void tearDown() {
        kafkaAcks.shutdownNow();
    }


    @Test
    void twoPublishersWithInducedFailures_shouldPublishEveryAggregatesEventsOnceAndInSequenceOrder() throws Exception {
        long firstOrder = uniqueAggregateId();
        List<Long> orders = LongStream.range(firstOrder, firstOrder + 40).boxed().toList();
        List<Long> deliveries = LongStream.range(firstOrder, firstOrder + 10).boxed().toList();
        // One transaction per event. Each order's events are written back to back, so a batch of 7 by id holds most of
        // one order and the next batch the rest: without the head-of-line claim the second publisher would send an
        // order's later events while the first still has its earlier ones in flight. The deliveries' events are
        // interleaved with everything else.
        for (Long orderId : orders) {
            for (int step = 0; step < ORDER_LIFECYCLE.length; step++) {
                enqueueInOwnTransaction("order-events", orderId, orderEvent(orderId, step));
            }
            int deliveryStep = orders.indexOf(orderId) / deliveries.size();
            if (deliveryStep < DELIVERY_LIFECYCLE.length) {
                Long deliveryId = deliveries.get(orders.indexOf(orderId) % deliveries.size());
                enqueueInOwnTransaction("delivery-events", deliveryId, deliveryEvent(deliveryId, deliveryStep));
            }
        }
        int events = orders.size() * ORDER_LIFECYCLE.length + deliveries.size() * DELIVERY_LIFECYCLE.length;

        // Every event fails at most once with a record error (rejected by send) and at most once after send (an attempt
        // each, MAX_ATTEMPTS is 3), and any send may time out (no attempt used), so nothing reaches FAILED.
        Map<String, AtomicInteger> recordErrors = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> sendFailures = new ConcurrentHashMap<>();
        AtomicInteger firstPublisherAcks = new AtomicInteger();
        AtomicInteger secondPublisherAcks = new AtomicInteger();
        OutboxEventPublisher first = publisher(kafka(firstPublisherAcks, event ->
                recordErrors.computeIfAbsent(eventId(event), id -> new AtomicInteger()).getAndIncrement() == 0
                        && ThreadLocalRandom.current().nextInt(10) == 0,
                event -> sendFailures.computeIfAbsent(eventId(event), id -> new AtomicInteger()).getAndIncrement() == 0
                        && ThreadLocalRandom.current().nextInt(6) == 0));
        OutboxEventPublisher second = publisher(kafka(secondPublisherAcks, event ->
                recordErrors.computeIfAbsent(eventId(event), id -> new AtomicInteger()).getAndIncrement() == 0
                        && ThreadLocalRandom.current().nextInt(10) == 0,
                event -> sendFailures.computeIfAbsent(eventId(event), id -> new AtomicInteger()).getAndIncrement() == 0
                        && ThreadLocalRandom.current().nextInt(6) == 0));

        runConcurrently(Duration.ofSeconds(90), first, second);

        Assertions.assertEquals(events, countByStatus("PUBLISHED"), "every event published");
        Assertions.assertEquals(0, countByStatus("FAILED"));
        for (Long orderId : orders) {
            Assertions.assertEquals(List.of(1L, 2L, 3L, 4L, 5L), acknowledged.get("ORDER:" + orderId),
                    "order " + orderId + " reached Kafka once per event and in sequence order");
        }
        for (Long deliveryId : deliveries) {
            Assertions.assertEquals(List.of(1L, 2L, 3L), acknowledged.get("DELIVERY:" + deliveryId),
                    "delivery " + deliveryId + " reached Kafka once per event and in sequence order");
        }
        Assertions.assertTrue(firstPublisherAcks.get() > 0 && secondPublisherAcks.get() > 0,
                "both publishers published: " + firstPublisherAcks + " / " + secondPublisherAcks);
        Assertions.assertEquals(events, firstPublisherAcks.get() + secondPublisherAcks.get());
    }


    @Test
    void aFailedHead_shouldHoldBackItsAggregateUntilAnOperatorRetriesIt() throws Exception {
        long blockedOrder = uniqueAggregateId();
        long otherOrder = blockedOrder + 1;
        for (int step = 0; step < 3; step++) {
            enqueueInOwnTransaction("order-events", blockedOrder, orderEvent(blockedOrder, step));
        }
        for (int step = 0; step < 2; step++) {
            enqueueInOwnTransaction("order-events", otherOrder, orderEvent(otherOrder, step));
        }
        Long poisonedHead = outboxEventRepository.findAll().stream()
                .filter(event -> event.aggregateId() == blockedOrder && event.aggregateSequence() == 1L)
                .findFirst()
                .orElseThrow()
                .id();
        Set<String> poisoned = ConcurrentHashMap.newKeySet();
        poisoned.add("ORDER:" + blockedOrder + ":1");
        AtomicInteger acks = new AtomicInteger();
        List<String> sent = Collections.synchronizedList(new ArrayList<>());
        OutboxEventPublisher publisher = publisher(kafka(acks, event -> {
            sent.add(aggregateKey(event) + ":" + sequence(event));
            return false;
        }, event -> poisoned.contains(aggregateKey(event) + ":" + sequence(event))));

        for (int run = 0; run < MAX_ATTEMPTS + 2; run++) {
            publisher.publishPendingEvents();
        }

        OutboxEventEntity head = outboxEventRepository.findById(poisonedHead).orElseThrow();
        Assertions.assertEquals("FAILED", head.status());
        Assertions.assertEquals(MAX_ATTEMPTS, head.attempts());
        Assertions.assertEquals(List.of(1L, 2L), acknowledged.get("ORDER:" + otherOrder), "other aggregates flow");
        Assertions.assertNull(acknowledged.get("ORDER:" + blockedOrder));
        Assertions.assertFalse(sent.contains("ORDER:" + blockedOrder + ":2"), "the later events were never sent: " + sent);
        Assertions.assertTrue(outboxService.claimNextBatch().isEmpty(), "nothing of the blocked aggregate is claimable");
        Assertions.assertEquals(1L, outboxEventRepository.countBlockedAggregates());
        Assertions.assertEquals(2L, outboxEventRepository.countBlockedEvents());
        Assertions.assertEquals(1.0, meterRegistry.get("onlineorder.outbox.blocked_aggregates").gauge().value());
        Assertions.assertEquals(2.0, meterRegistry.get("onlineorder.outbox.blocked_events").gauge().value());

        poisoned.clear();
        outboxService.retryFailedEvent(poisonedHead);
        for (int run = 0; run < 4; run++) {
            publisher.publishPendingEvents();
        }

        Assertions.assertEquals(List.of(1L, 2L, 3L), acknowledged.get("ORDER:" + blockedOrder));
        Assertions.assertEquals(5, countByStatus("PUBLISHED"));
        Assertions.assertEquals(0L, outboxEventRepository.countBlockedAggregates());
        Assertions.assertEquals(0.0, meterRegistry.get("onlineorder.outbox.blocked_events").gauge().value());
    }


    @Test
    void concurrentWritersOfOneAggregate_shouldGetUniqueGaplessSequencesInCommitOrder() throws Exception {
        long orderId = uniqueAggregateId();
        int writers = 8;
        int eventsPerWriter = 15;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int writer = 0; writer < writers; writer++) {
                results.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < eventsPerWriter; i++) {
                        transaction.executeWithoutResult(status -> {
                            outboxService.enqueueEvent("order-events", String.valueOf(orderId), orderEvent(orderId, 1));
                            // Hold the transaction open a little: the next writer waits on the counter row meanwhile.
                            sleepQuietly(ThreadLocalRandom.current().nextInt(3));
                        });
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<OutboxEventEntity> events = eventsOf("ORDER", orderId);
        List<Long> expected = LongStream.rangeClosed(1, (long) writers * eventsPerWriter).boxed().toList();
        // Ordered by id, which each writer takes after it got its sequence: commit order is sequence order.
        Assertions.assertEquals(expected, events.stream().map(OutboxEventEntity::aggregateSequence).toList());
        for (OutboxEventEntity event : events) {
            Assertions.assertEquals(event.aggregateSequence(), objectMapper.readTree(event.payload()).get("sequence").asLong());
        }
        Assertions.assertEquals((long) writers * eventsPerWriter, jdbcTemplate.queryForObject(
                "SELECT last_sequence FROM outbox_aggregate_sequences WHERE aggregate_type = 'ORDER' AND aggregate_id = ?",
                Long.class, orderId));
    }


    @Test
    void concurrentFirstEventsOfOneAggregateAndARollback_shouldStillYieldGaplessSequences() throws Exception {
        long deliveryId = uniqueAggregateId();
        int writers = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int writer = 0; writer < writers; writer++) {
                results.add(pool.submit(() -> {
                    start.await();
                    enqueueInOwnTransaction("delivery-events", deliveryId, deliveryEvent(deliveryId, 0));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        // A transaction that rolls back after taking a sequence gives it back.
        Assertions.assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            outboxService.enqueueEvent("delivery-events", String.valueOf(deliveryId), deliveryEvent(deliveryId, 1));
            throw new IllegalStateException("rolled back");
        }));
        enqueueInOwnTransaction("delivery-events", deliveryId, deliveryEvent(deliveryId, 1));

        Assertions.assertEquals(
                LongStream.rangeClosed(1, writers + 1).boxed().toList(),
                eventsOf("DELIVERY", deliveryId).stream().map(OutboxEventEntity::aggregateSequence).toList()
        );
    }


    @Test
    void enqueueEvent_outsideATransaction_shouldBeRefused() {
        long orderId = uniqueAggregateId();

        Assertions.assertThrows(
                org.springframework.transaction.IllegalTransactionStateException.class,
                () -> outboxService.enqueueEvent("order-events", String.valueOf(orderId), orderEvent(orderId, 0))
        );
        Assertions.assertEquals(List.of(), eventsOf("ORDER", orderId));
    }


    private static final String[][] ORDER_LIFECYCLE = {
            {"order.created", "PLACED"},
            {"order.paid", "PAID"},
            {"order.accepted", "ACCEPTED"},
            {"order.preparing", "PREPARING"},
            {"order.completed", "COMPLETED"},
    };

    private static final String[][] DELIVERY_LIFECYCLE = {
            {"delivery.assigned", "ASSIGNED"},
            {"delivery.picked_up", "PICKED_UP"},
            {"delivery.delivered", "DELIVERED"},
    };

    private static final AtomicLong AGGREGATE_IDS = new AtomicLong(System.currentTimeMillis() % 1_000_000L * 1000L);


    private static long uniqueAggregateId() {
        return AGGREGATE_IDS.addAndGet(1000);
    }


    private static OrderEventEnvelope orderEvent(long orderId, int step) {
        String eventId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        return new OrderEventEnvelope(
                eventId,
                1,
                ORDER_LIFECYCLE[step][0],
                "ORDER",
                orderId,
                now,
                eventId,
                null,
                new OrderEventPayload(
                        3L,
                        ORDER_LIFECYCLE[step][1],
                        new BigDecimal("10.59"),
                        now,
                        List.of(new OrderEventItem(1L, 2L, 1L, new BigDecimal("10.59"), null, 1, "Whopper Meal", null, null)),
                        1L,
                        null
                )
        );
    }


    private static DeliveryEventEnvelope deliveryEvent(long deliveryId, int step) {
        String eventId = UUID.randomUUID().toString();
        return new DeliveryEventEnvelope(
                eventId,
                1,
                DELIVERY_LIFECYCLE[step][0],
                "DELIVERY",
                deliveryId,
                LocalDateTime.now(),
                eventId,
                null,
                new DeliveryEventPayload(deliveryId, 3L, 1L, 4L, "Alice", DELIVERY_LIFECYCLE[step][1])
        );
    }


    private void enqueueInOwnTransaction(String topic, long key, EventEnvelope envelope) {
        transaction.executeWithoutResult(status -> outboxService.enqueueEvent(topic, String.valueOf(key), envelope));
    }


    /**
     * A KafkaTemplate that acknowledges asynchronously after a short random delay and records each acknowledged event
     * under its aggregate before completing the send.
     *
     * @param recordError  events whose send is rejected right away with a record error (an attempt is used)
     * @param sendFailure  events whose send fails after it was accepted (an attempt is used); any other send may time
     *                     out instead (transient: no attempt is used)
     */
    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> kafka(
            AtomicInteger acks,
            Predicate<String> recordError,
            Predicate<String> sendFailure
    ) {
        KafkaTemplate<String, String> kafka = Mockito.mock(KafkaTemplate.class);
        Mockito.when(kafka.send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString())).thenAnswer(invocation -> {
            String payload = invocation.getArgument(2);
            if (recordError.test(payload)) {
                throw new SerializationException("induced record error");
            }
            boolean fail = sendFailure.test(payload);
            boolean timeOut = !fail && ThreadLocalRandom.current().nextInt(8) == 0;
            return CompletableFuture.supplyAsync(() -> {
                sleepQuietly(ThreadLocalRandom.current().nextInt(5));
                if (fail) {
                    throw new IllegalStateException("induced send failure");
                }
                if (timeOut) {
                    throw new TimeoutException("induced delivery timeout");
                }
                acknowledged.computeIfAbsent(aggregateKey(payload), key -> Collections.synchronizedList(new ArrayList<>()))
                        .add(sequence(payload));
                acks.incrementAndGet();
                return (SendResult<String, String>) null;
            }, kafkaAcks);
        });
        return kafka;
    }


    private OutboxEventPublisher publisher(KafkaTemplate<String, String> kafka) {
        return new OutboxEventPublisher(
                kafka,
                Duration.ofSeconds(5),
                7,
                Duration.ofMillis(50),
                Duration.ofSeconds(30),
                Duration.ZERO,
                outboxService,
                metricsService
        );
    }


    /**
     * Runs each publisher on its own thread, over and over, until no event is left unpublished.
     */
    private void runConcurrently(Duration timeout, OutboxEventPublisher... publishers) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(publishers.length);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (OutboxEventPublisher publisher : publishers) {
                runs.add(pool.submit(() -> {
                    start.await();
                    while (countUnpublished() > 0 && System.nanoTime() < deadline) {
                        publisher.publishPendingEvents();
                        Thread.sleep(1);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> run : runs) {
                run.get(timeout.toSeconds() + 30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }


    private List<OutboxEventEntity> eventsOf(String aggregateType, long aggregateId) {
        return outboxEventRepository.findAll().stream()
                .filter(event -> aggregateType.equals(event.aggregateType()) && event.aggregateId() == aggregateId)
                .sorted(java.util.Comparator.comparing(OutboxEventEntity::id))
                .toList();
    }


    private long countByStatus(String status) {
        return outboxEventRepository.countByStatus(status);
    }


    private long countUnpublished() {
        Long unpublished = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status <> 'PUBLISHED'", Long.class);
        return unpublished == null ? 0 : unpublished;
    }


    private String eventId(String payload) {
        return objectMapper.readTree(payload).get("event_id").asString();
    }


    private String aggregateKey(String payload) {
        var event = objectMapper.readTree(payload);
        return event.get("aggregate_type").asString() + ":" + event.get("aggregate_id").asLong();
    }


    private long sequence(String payload) {
        return objectMapper.readTree(payload).get("sequence").asLong();
    }


    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
