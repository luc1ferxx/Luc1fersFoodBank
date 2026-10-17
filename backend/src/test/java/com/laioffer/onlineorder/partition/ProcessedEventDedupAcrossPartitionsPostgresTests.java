package com.laioffer.onlineorder.partition;


import com.laioffer.onlineorder.service.ProcessedEventService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;


import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;


/**
 * processed_events deduplication on the partitioned table (V10): no unique constraint spans the monthly partitions,
 * so ProcessedEventDedupImpl serializes marks of one key with an advisory lock and checks every partition. These
 * cases fail with a plain per-partition unique index alone.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ProcessedEventDedupAcrossPartitionsPostgresTests {

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
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.partitions.initial-delay-ms", () -> "3600000");
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }

    @Autowired
    private ProcessedEventService processedEventService;

    @Autowired
    private PartitionCatalog catalog;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;


    @Test
    void markIfUnseen_whenTheFirstMarkIsInAnEarlierMonthsPartition_shouldStillReportTheEventAsSeen() {
        // A dead letter replayed weeks later: the first delivery's mark sits in another monthly partition.
        LocalDateTime twoMonthsAgo = LocalDateTime.now().minusMonths(2);
        new PartitionMaintenanceService(true, false, 3, Duration.ofSeconds(5), Duration.ofDays(14), Duration.ZERO,
                Duration.ZERO, 1000, catalog, jdbcTemplate, transactionManager, new SimpleMeterRegistry())
                .maintainPartitions(twoMonthsAgo);
        jdbcTemplate.update("""
                INSERT INTO processed_events (consumer_name, event_id, dedup_key, event_type, processed_at)
                VALUES ('replay-consumer', 'evt-replayed', 'evt-replayed', 'order.paid', ?)
                """, Timestamp.valueOf(twoMonthsAgo));

        Assertions.assertFalse(mark("replay-consumer", "evt-replayed"));
        Assertions.assertTrue(mark("other-consumer", "evt-replayed"), "uniqueness stays per consumer");

        Assertions.assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT tableoid) FROM processed_events WHERE dedup_key = 'evt-replayed'", Integer.class));
        Assertions.assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM processed_events
                WHERE consumer_name = 'replay-consumer' AND dedup_key = 'evt-replayed'
                """, Integer.class));
    }


    @Test
    void markIfUnseen_fromConcurrentTransactions_shouldLetExactlyOneThrough() throws Exception {
        int threads = 12;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return transaction.execute(status -> {
                        boolean first = mark("race-consumer", "evt-race");
                        // Keep the transaction (and the lock) open a little, as a consumer does while it works.
                        sleep(20);
                        return first;
                    });
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                if (Boolean.TRUE.equals(result.get(30, TimeUnit.SECONDS))) {
                    winners++;
                }
            }
            Assertions.assertEquals(1, winners);
        } finally {
            executor.shutdownNow();
        }
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE dedup_key = 'evt-race'", Integer.class));
    }


    @Test
    void markIfUnseen_shouldWaitForTheFirstTransactionAndFollowItsOutcome() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            // The first consumer rolls back (its work failed): the redelivery must be processed.
            CountDownLatch marked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<Boolean> first = executor.submit(() -> transaction.execute(status -> {
                boolean result = mark("wait-consumer", "evt-wait");
                marked.countDown();
                await(release);
                status.setRollbackOnly();
                return result;
            }));
            Assertions.assertTrue(marked.await(10, TimeUnit.SECONDS));
            Future<Boolean> second = executor.submit(() -> mark("wait-consumer", "evt-wait"));
            Thread.sleep(300);
            Assertions.assertFalse(second.isDone(), "the second mark waits on the first transaction");
            release.countDown();
            Assertions.assertTrue(first.get(10, TimeUnit.SECONDS));
            Assertions.assertTrue(second.get(10, TimeUnit.SECONDS), "the first one rolled back");

            // This time the first one commits: the second one sees its mark.
            CountDownLatch markedAgain = new CountDownLatch(1);
            CountDownLatch releaseAgain = new CountDownLatch(1);
            Future<Boolean> third = executor.submit(() -> transaction.execute(status -> {
                boolean result = mark("wait-consumer", "evt-wait-commit");
                markedAgain.countDown();
                await(releaseAgain);
                return result;
            }));
            Assertions.assertTrue(markedAgain.await(10, TimeUnit.SECONDS));
            Future<Boolean> fourth = executor.submit(() -> mark("wait-consumer", "evt-wait-commit"));
            Thread.sleep(300);
            Assertions.assertFalse(fourth.isDone());
            releaseAgain.countDown();
            Assertions.assertTrue(third.get(10, TimeUnit.SECONDS));
            Assertions.assertFalse(fourth.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }


    @Test
    void markIfUnseen_inARepeatableReadTransaction_shouldBeRefused() {
        TransactionTemplate repeatableRead = new TransactionTemplate(transactionManager);
        repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        Assertions.assertThrows(IllegalStateException.class,
                () -> repeatableRead.execute(status -> mark("rr-consumer", "evt-rr")));
    }


    @Test
    void perPartitionUniqueIndex_shouldStillRejectADuplicateWithinOneMonth() {
        jdbcTemplate.update("""
                INSERT INTO processed_events (consumer_name, dedup_key, event_type, processed_at)
                VALUES ('raw-consumer', 'evt-raw', 'order.paid', LOCALTIMESTAMP)
                """);

        Assertions.assertThrows(DuplicateKeyException.class, () -> jdbcTemplate.update("""
                INSERT INTO processed_events (consumer_name, dedup_key, event_type, processed_at)
                VALUES ('raw-consumer', 'evt-raw', 'order.paid', LOCALTIMESTAMP)
                """));
    }


    private boolean mark(String consumer, String dedupKey) {
        return processedEventService.markIfUnseen(consumer, dedupKey, dedupKey, "order.paid", "ORDER", 1L);
    }


    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }


    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
