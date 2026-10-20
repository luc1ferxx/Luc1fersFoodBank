package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.PostgresRowLocks;
import com.laioffer.onlineorder.PostgresRowLocks.RowLockHolder;
import com.laioffer.onlineorder.money.Money;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;


/**
 * The after-sales deadlines on PostgreSQL, plus the races of a timeout with the answer or decision it replaces (ADR
 * 0002 sections 6, 14 and 19.1), in both serial orders on every run, alternating between rounds:
 * <ul>
 *     <li>timeout first: a lock holder takes the case's outbox sequence row, the poller claims the case and fires its
 *     timeout up to the event, where it waits for that row, and the command queues behind the case row the poller
 *     holds; releasing the holder commits the timeout, then the command decides on the moved case;</li>
 *     <li>command first: a lock holder takes the case row and the command queues behind it; the poller, run meanwhile,
 *     passes over the locked case (SKIP LOCKED) instead of waiting; releasing the holder lets the command decide, then
 *     the poller runs again.</li>
 * </ul>
 * The poller bean is on in this context, so that it proves the context still holds one TaskScheduler, but its first
 * tick is an hour away: the tests run the processor themselves.
 */
@SpringBootTest(properties = {
        // Sessions live in the MockMvc session instead of Redis.
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AfterSalesDeadlinePostgresIntegrationTests extends AbstractAfterSalesDeadlineIntegrationTests {

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
        registry.add("app.after-sales.poller.enabled", () -> "true");
        registry.add("app.after-sales.poller.interval", () -> "1h");
    }


    /**
     * D13: the poller runs on its own executor, not as a second TaskScheduler, which would make Spring Boot back off
     * the scheduler every @Scheduled job runs on.
     */
    @Test
    void poller_shouldRunWithoutASecondTaskScheduler() {
        Assertions.assertTrue(applicationContext.getBean(AfterSalesPoller.class).isRunning());
        Assertions.assertEquals(
                List.of("taskScheduler"), List.of(applicationContext.getBeanNamesForType(TaskScheduler.class)));
    }


    /**
     * Race 1 of section 14: past the merchant's deadline the merchant is refused whoever locks first (the deadline
     * when the merchant is first, the status once the timeout fired), and the timeout always refunds the requested
     * amount, once.
     */
    @Test
    void merchantApproveRacingTheMerchantTimeout_shouldAlwaysEndInTheTimeout() throws Exception {
        Customer customer = customer("after-sales-race-merchant-");
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 4; round++) {
                AfterSalesCaseEntity late = caseIn(customer, AfterSalesStatus.PENDING_MERCHANT);
                LocalDateTime deadline = makeDue(late.id(), 1);
                Callable<String> approve = () -> outcome(() -> merchantAnswers(
                        late.id(), AfterSalesTrigger.MERCHANT_APPROVE, null, null));
                boolean timeoutFirst = round % 2 == 0;

                String merchantResult = race(executor, late.id(), timeoutFirst, approve);

                if (timeoutFirst) {
                    Assertions.assertEquals("CONFLICT|AFTER_SALES_STATUS_CONFLICT|After-sales case is REFUNDING; "
                            + "only PENDING_MERCHANT cases can be approved", merchantResult);
                } else {
                    Assertions.assertEquals("CONFLICT|AFTER_SALES_DEADLINE_PASSED|The deadline of this after-sales "
                            + "step passed at " + AfterSalesErrors.timestamp(deadline), merchantResult);
                }
                AfterSalesCaseEntity refunding = assertRefunding(
                        late, AfterSalesResolution.MERCHANT_TIMEOUT, late.requestedAmount());
                Assertions.assertNull(refunding.merchantDecision(), "round " + round);
                Assertions.assertNull(refunding.merchantNote());
                Assertions.assertEquals(2, history(late.id()).size(), history(late.id()).toString());
                Assertions.assertEquals(List.of("aftersales.case_opened", "aftersales.refund_approved"),
                        eventTypes(late.id()));
            }
        } finally {
            executor.shutdownNow();
        }
    }


    /**
     * Race 3 of section 14: the customer's acceptance of a counter-offer past its deadline is refused whoever locks
     * first, and the offer timeout refunds the offered amount, once. The refused acceptance keeps no key.
     */
    @Test
    void customerAcceptOfferRacingTheOfferTimeout_shouldAlwaysRefundTheOfferOnce() throws Exception {
        Customer customer = customer("after-sales-race-offer-");
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 4; round++) {
                AfterSalesCaseEntity late = caseIn(customer, AfterSalesStatus.COUNTER_OFFERED);
                LocalDateTime deadline = makeDue(late.id(), 1);
                String key = "race-accept-" + late.id();
                Callable<String> accept = () -> outcome(
                        () -> afterSalesCustomerService.acceptOffer(customer.id(), late.id(), key).status());
                boolean timeoutFirst = round % 2 == 1;

                String customerResult = race(executor, late.id(), timeoutFirst, accept);

                if (timeoutFirst) {
                    Assertions.assertEquals("CONFLICT|AFTER_SALES_STATUS_CONFLICT|After-sales case is REFUNDING; "
                            + "only COUNTER_OFFERED cases can be accepted", customerResult);
                } else {
                    Assertions.assertEquals("CONFLICT|AFTER_SALES_DEADLINE_PASSED|The deadline of this after-sales "
                            + "step passed at " + AfterSalesErrors.timestamp(deadline), customerResult);
                }
                assertRefunding(late, AfterSalesResolution.OFFER_TIMEOUT, new BigDecimal(OFFER));
                Assertions.assertEquals(0, idempotencyRows(customer.id(), key), "round " + round);
                Assertions.assertEquals(3, history(late.id()).size(), history(late.id()).toString());
                Assertions.assertEquals(
                        List.of("aftersales.case_opened", "aftersales.offer_made", "aftersales.refund_approved"),
                        eventTypes(late.id())
                );
            }
        } finally {
            executor.shutdownNow();
        }
    }


    /**
     * An admin's decision has no deadline (section 19.1): past the arbitration deadline the lock order decides. An
     * admin first decides as usual (a partial refund or a rejection) and leaves the timeout nothing to fire; a timeout
     * first refunds the requested amount and leaves the admin a status conflict.
     */
    @Test
    void adminDecisionRacingTheArbitrationTimeout_shouldFollowTheLockOrder() throws Exception {
        Customer customer = customer("after-sales-race-arbitration-");
        Long adminId = adminId();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 4; round++) {
                AfterSalesCaseEntity late = caseIn(customer, AfterSalesStatus.IN_ARBITRATION);
                makeDue(late.id(), 1);
                boolean partial = round < 2;
                AfterSalesTrigger decision = partial
                        ? AfterSalesTrigger.ADMIN_APPROVE_PARTIAL
                        : AfterSalesTrigger.ADMIN_REJECT;
                Money amount = partial ? Money.of("1.00", "USD") : null;
                Callable<String> decide = () -> outcome(
                        () -> adminDecides(late.id(), decision, amount, "Decided after the review."));
                boolean timeoutFirst = round % 2 == 0;

                String adminResult = race(executor, late.id(), timeoutFirst, decide);

                if (timeoutFirst) {
                    Assertions.assertEquals("CONFLICT|AFTER_SALES_STATUS_CONFLICT|After-sales case is REFUNDING; "
                            + "only IN_ARBITRATION cases can be arbitrated", adminResult);
                    AfterSalesCaseEntity refunding = assertRefunding(
                            late, AfterSalesResolution.ARBITRATION_TIMEOUT, late.requestedAmount());
                    Assertions.assertNull(refunding.adminNote());
                } else if (partial) {
                    Assertions.assertEquals("REFUNDING", adminResult);
                    AfterSalesCaseEntity refunding = reload(late.id());
                    Assertions.assertEquals("ARBITRATION_PARTIAL", refunding.resolution());
                    Assertions.assertEquals(new BigDecimal("1.00"), refunding.approvedAmount().setScale(2));
                    assertNoDeadline(refunding, late.version() + 1);
                    Assertions.assertEquals(1, refunds(late.id()));
                    Assertions.assertEquals(new BigDecimal("1.00"), refundAmount(late.id()));
                    Assertions.assertEquals("ADMIN_APPROVE_PARTIAL|IN_ARBITRATION->REFUNDING|ADMIN|" + adminId
                            + "|1.00", lastStep(late.id()));
                } else {
                    Assertions.assertEquals("CLOSED", adminResult);
                    AfterSalesCaseEntity closed = reload(late.id());
                    Assertions.assertEquals("ARBITRATION_REJECTED", closed.closeReason());
                    Assertions.assertNull(closed.approvedAmount());
                    assertNoDeadline(closed, late.version() + 1);
                    Assertions.assertEquals(0, refunds(late.id()));
                    Assertions.assertEquals("ADMIN_REJECT|IN_ARBITRATION->CLOSED|ADMIN|" + adminId + "|null",
                            lastStep(late.id()));
                }
                // The opening, the merchant's rejection, the escalation and the winner's step.
                Assertions.assertEquals(4, history(late.id()).size(), "round " + round);
            }
        } finally {
            executor.shutdownNow();
        }
    }


    /**
     * Runs {@code command} and the deadline processor on the due case {@code caseId} in the chosen serial order (see
     * the class comment) and returns the command's outcome. The poller fires the case exactly once.
     */
    private String race(ExecutorService executor, long caseId, boolean timeoutFirst, Callable<String> command)
            throws Exception {
        Callable<Integer> poller = deadlineProcessor::fireDueDeadlines;
        if (timeoutFirst) {
            Long sequenceRowId = jdbcTemplate.queryForObject("""
                    SELECT id
                    FROM outbox_aggregate_sequences
                    WHERE aggregate_type = 'AFTER_SALES_CASE'
                      AND aggregate_id = ?
                    """, Long.class, caseId);
            Future<Integer> fired;
            Future<String> answered;
            try (RowLockHolder sequenceRow = PostgresRowLocks.holdRowLock(
                    executor, transactionManager, jdbcTemplate, "outbox_aggregate_sequences", sequenceRowId)) {
                fired = sequenceRow.queue(poller);
                answered = sequenceRow.queue(command);
                sequenceRow.release();
            }
            Assertions.assertEquals(1, fired.get(20, TimeUnit.SECONDS), "the poller fired the due case");
            return answered.get(20, TimeUnit.SECONDS);
        }
        Future<String> answered;
        try (RowLockHolder caseRow = PostgresRowLocks.holdRowLock(
                executor, transactionManager, jdbcTemplate, "after_sales_cases", caseId)) {
            answered = caseRow.queue(command);
            Assertions.assertEquals(0, deadlineProcessor.fireDueDeadlines(), "the poller skips a locked case");
            caseRow.release();
        }
        String outcome = answered.get(20, TimeUnit.SECONDS);
        AfterSalesStatus afterCommand = AfterSalesStatus.valueOf(reload(caseId).status());
        Assertions.assertEquals(afterCommand.isOpen() ? 1 : 0, deadlineProcessor.fireDueDeadlines(),
                "the poller fires the case only while it still waits");
        return outcome;
    }
}
