package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.PostgresRowLocks;
import com.laioffer.onlineorder.PostgresRowLocks.RowLockHolder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;


import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;


/**
 * The refund executor on PostgreSQL, plus the races of two executors on one refund (ADR 0002 section 14, races 7 and
 * 8), each in a forced serial order:
 * <ul>
 *     <li>a refund another executor is claiming (its row locked) is skipped, not waited for, and a refund it leased
 *     is not due: the second executor finds nothing and the gateway is called once;</li>
 *     <li>when the first executor stalls in the gateway past its lease, the second reclaims the refund and calls the
 *     gateway again with the same key. Both results are then recorded one after the other, queued on the refund's row
 *     lock in alternating order between rounds: whichever comes first, the stale result of the first attempt is
 *     discarded and the refund succeeds once, with one aftersales.case_refunded event.</li>
 * </ul>
 * The poller is off (Kafka is off), so that only the tests run the executor.
 */
@SpringBootTest(properties = {
        // Sessions live in the MockMvc session instead of Redis.
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class RefundExecutorPostgresIntegrationTests extends AbstractRefundExecutorIntegrationTests {

    private static final long WAIT_SECONDS = 20;

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
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("spring.session.store-type", () -> "none");
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.outbox.publisher-enabled", () -> "false");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
        // The races hold gateway calls open on purpose; well within the lease (60 s).
        registry.add("app.after-sales.refund-gateway.timeout", () -> "30s");
    }


    /**
     * Race 7 of section 14: a second executor never takes a refund the first one holds, neither while the first
     * claims it (SKIP LOCKED) nor while it waits for the gateway under its lease.
     */
    @Test
    void refundAnotherExecutorHolds_shouldNotBeTakenBySecondExecutor() throws Exception {
        Customer customer = customer("refund-race-held-");
        AfterSalesCaseEntity refunding = cancelledCase(customer);
        RefundEntity pending = refundOf(refunding.id());
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            try (RowLockHolder refundRow = PostgresRowLocks.holdRowLock(
                    executor, transactionManager, jdbcTemplate, "refunds", pending.id())) {
                Assertions.assertEquals(0, refundExecutor.runDueRefunds(), "a refund being claimed is skipped");
                refundRow.release();
            }
            Assertions.assertEquals("PENDING|0|null|null", state(refundOf(refunding.id())));

            CountDownLatch called = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Mockito.doAnswer(invocation -> {
                called.countDown();
                Assertions.assertTrue(release.await(WAIT_SECONDS, TimeUnit.SECONDS));
                return invocation.callRealMethod();
            }).when(gateway).refund(Mockito.any());
            Future<Integer> first = executor.submit(refundExecutor::runDueRefunds);
            Assertions.assertTrue(called.await(WAIT_SECONDS, TimeUnit.SECONDS), "the first executor never called");
            try {
                Assertions.assertEquals("PROCESSING|1|null|null", state(refundOf(refunding.id())));
                Assertions.assertEquals(0, refundExecutor.runDueRefunds(), "a leased refund is not due");
            } finally {
                release.countDown();
            }
            Assertions.assertEquals(1, first.get(WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertSucceeded(refunding, 1);
        Mockito.verify(gateway, Mockito.times(1)).refund(Mockito.any());
    }


    /**
     * Race 8 of section 14 (and race 7 once the lease is gone): the first executor stalls in the gateway past its
     * lease, the second reclaims the refund (attempt 2, a new version) and calls the gateway with the same key, and
     * both get the same answer. Their record transactions queue on the refund's row lock, the reclaimer first in even
     * rounds and the stalled executor first in odd ones. Either way the stalled result is discarded as superseded and
     * the refund succeeds exactly once: one SUCCEEDED write, one REFUND_SUCCEEDED step, one case_refunded event.
     */
    @Test
    void stalledExecutorAndReclaimer_shouldRefundOnceWhicheverRecordsFirst() throws Exception {
        Customer customer = customer("refund-race-reclaim-");
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            for (int round = 0; round < 4; round++) {
                AfterSalesCaseEntity refunding = cancelledCase(customer);
                RefundEntity pending = refundOf(refunding.id());
                Mockito.clearInvocations(gateway);
                CountDownLatch firstCalled = new CountDownLatch(1);
                CountDownLatch secondCalled = new CountDownLatch(1);
                CountDownLatch releaseFirst = new CountDownLatch(1);
                CountDownLatch releaseSecond = new CountDownLatch(1);
                Mockito.doAnswer(invocation -> {
                    boolean firstAttempt = invocation.<RefundGatewayRequest>getArgument(0).attempt() == 1;
                    (firstAttempt ? firstCalled : secondCalled).countDown();
                    Assertions.assertTrue((firstAttempt ? releaseFirst : releaseSecond)
                            .await(WAIT_SECONDS, TimeUnit.SECONDS));
                    return invocation.callRealMethod();
                }).when(gateway).refund(Mockito.any());
                double supersededBefore = refundCount(RefundExecutor.SUPERSEDED);
                double succeededBefore = refundCount(RefundExecutor.SUCCEEDED);

                Future<Integer> stalled = executor.submit(refundExecutor::runDueRefunds);
                Assertions.assertTrue(firstCalled.await(WAIT_SECONDS, TimeUnit.SECONDS), "round " + round);
                // The first executor stalls past its lease.
                Assertions.assertEquals(1, jdbcTemplate.update("UPDATE refunds SET claimed_until = ? WHERE id = ?",
                        LocalDateTime.now().minusSeconds(1), pending.id()));
                Future<Integer> reclaimer = executor.submit(refundExecutor::runDueRefunds);
                Assertions.assertTrue(secondCalled.await(WAIT_SECONDS, TimeUnit.SECONDS), "round " + round);
                Assertions.assertEquals("PROCESSING|2|null|null", state(refundOf(refunding.id())));
                boolean reclaimerFirst = round % 2 == 0;

                try (RowLockHolder refundRow = PostgresRowLocks.holdRowLock(
                        executor, transactionManager, jdbcTemplate, "refunds", pending.id())) {
                    Future<Integer> recordedFirst = refundRow.queue(() -> {
                        (reclaimerFirst ? releaseSecond : releaseFirst).countDown();
                        return (reclaimerFirst ? reclaimer : stalled).get(WAIT_SECONDS, TimeUnit.SECONDS);
                    });
                    Future<Integer> recordedSecond = refundRow.queue(() -> {
                        (reclaimerFirst ? releaseFirst : releaseSecond).countDown();
                        return (reclaimerFirst ? stalled : reclaimer).get(WAIT_SECONDS, TimeUnit.SECONDS);
                    });
                    refundRow.release();
                    Assertions.assertEquals(1, recordedFirst.get(WAIT_SECONDS, TimeUnit.SECONDS));
                    Assertions.assertEquals(1, recordedSecond.get(WAIT_SECONDS, TimeUnit.SECONDS));
                } finally {
                    releaseFirst.countDown();
                    releaseSecond.countDown();
                }

                assertSucceeded(refunding, 2);
                // Inserted at 0, claimed, reclaimed and recorded once: the stale result wrote nothing.
                Assertions.assertEquals(3L, refundOf(refunding.id()).version(), "round " + round);
                Assertions.assertEquals(1, jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM after_sales_case_history
                        WHERE case_id = ?
                          AND action = 'REFUND_SUCCEEDED'
                        """, Integer.class, refunding.id()));
                Assertions.assertEquals(List.of("aftersales.case_opened", "aftersales.case_refunded"),
                        eventTypes(refunding.id()));
                ArgumentCaptor<RefundGatewayRequest> calls = ArgumentCaptor.forClass(RefundGatewayRequest.class);
                Mockito.verify(gateway, Mockito.times(2)).refund(calls.capture());
                Assertions.assertEquals(List.of(1, 2),
                        calls.getAllValues().stream().map(RefundGatewayRequest::attempt).toList());
                Assertions.assertEquals(List.of(pending.gatewayKey(), pending.gatewayKey()),
                        calls.getAllValues().stream().map(RefundGatewayRequest::gatewayKey).toList());
                Assertions.assertEquals(supersededBefore + 1, refundCount(RefundExecutor.SUPERSEDED), "round " + round);
                Assertions.assertEquals(succeededBefore + 1, refundCount(RefundExecutor.SUCCEEDED), "round " + round);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
