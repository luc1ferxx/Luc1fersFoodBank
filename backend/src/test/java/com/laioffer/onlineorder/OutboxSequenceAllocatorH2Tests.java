package com.laioffer.onlineorder;


import com.laioffer.onlineorder.service.H2OutboxSequenceAllocator;
import com.laioffer.onlineorder.service.OutboxSequenceAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;


import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;


/**
 * The H2 allocator on real H2 (h2 profile) when two transactions write the first event of the same aggregate: the
 * second one's INSERT waits for the first transaction, then steps the counter it committed or, after a rollback, starts
 * the counter itself. PostgreSQL's allocator has the same tests in OutboxAggregateOrderingPostgresTests.
 */
@SpringBootTest
@ActiveProfiles("h2")
class OutboxSequenceAllocatorH2Tests {

    private static final AtomicLong AGGREGATE_IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L);


    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.session.store-type", () -> "none");
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.outbox.publisher-enabled", () -> "false");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }


    @Autowired
    private OutboxSequenceAllocator allocator;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TransactionTemplate transaction;
    private ExecutorService pool;


    @BeforeEach
    void setup() {
        transaction = new TransactionTemplate(transactionManager);
        pool = Executors.newFixedThreadPool(2);
    }


    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }


    @Test
    void theH2Profile_shouldUseTheH2Allocator() {
        Assertions.assertInstanceOf(H2OutboxSequenceAllocator.class, allocator);
    }


    @Test
    void aSecondTransactionStartingTheSameCounter_shouldWaitForTheFirstAndTakeTheNextSequence() throws Exception {
        long deliveryId = AGGREGATE_IDS.incrementAndGet();

        Allocations allocations = allocateConcurrently(deliveryId, false);

        Assertions.assertEquals(1L, allocations.first());
        Assertions.assertEquals(2L, allocations.second());
        Assertions.assertTrue(allocations.secondWaitedMillis() >= 200,
                "the second transaction waited for the first: " + allocations.secondWaitedMillis() + "ms");
        Assertions.assertEquals(2L, lastSequence(deliveryId));
    }


    @Test
    void whenTheFirstTransactionRollsBack_theSecondShouldStartTheCounterAtOne() throws Exception {
        long deliveryId = AGGREGATE_IDS.incrementAndGet();

        Allocations allocations = allocateConcurrently(deliveryId, true);

        Assertions.assertEquals(1L, allocations.first());
        Assertions.assertEquals(1L, allocations.second(), "the rolled-back sequence is handed out again");
        Assertions.assertEquals(1L, lastSequence(deliveryId));
    }


    /**
     * The first transaction starts the counter and stays open for 400ms after the second one began its allocation, then
     * commits or rolls back.
     */
    private Allocations allocateConcurrently(long deliveryId, boolean rollBackFirst) throws Exception {
        CountDownLatch firstAllocated = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        Future<Long> first = pool.submit(() -> transaction.execute(status -> {
            long sequence = allocator.next("DELIVERY", deliveryId);
            firstAllocated.countDown();
            awaitQuietly(secondStarted);
            sleepQuietly(400);
            if (rollBackFirst) {
                status.setRollbackOnly();
            }
            return sequence;
        }));
        Future<long[]> second = pool.submit(() -> {
            Assertions.assertTrue(firstAllocated.await(10, TimeUnit.SECONDS));
            secondStarted.countDown();
            long started = System.nanoTime();
            Long sequence = transaction.execute(status -> allocator.next("DELIVERY", deliveryId));
            return new long[]{sequence, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)};
        });
        long[] secondResult = second.get(20, TimeUnit.SECONDS);
        return new Allocations(first.get(20, TimeUnit.SECONDS), secondResult[0], secondResult[1]);
    }


    private Long lastSequence(long deliveryId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_sequence FROM outbox_aggregate_sequences WHERE aggregate_type = 'DELIVERY' AND aggregate_id = ?",
                Long.class,
                deliveryId
        );
    }


    private static void awaitQuietly(CountDownLatch latch) {
        try {
            Assertions.assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }


    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }


    private record Allocations(long first, long second, long secondWaitedMillis) {
    }
}
