package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.PostgresRowLocks;
import com.laioffer.onlineorder.PostgresRowLocks.RowLockHolder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;


import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * The after-sales deadlines on H2 (the {@code h2} profile): the same claim (FOR UPDATE SKIP LOCKED), steps, refunds
 * and back-off as on PostgreSQL.
 */
@SpringBootTest(properties = {
        // Sessions live in the MockMvc session instead of Redis.
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class AfterSalesDeadlineH2IntegrationTests extends AbstractAfterSalesDeadlineIntegrationTests {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.session.store-type", () -> "none");
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.outbox.publisher-enabled", () -> "false");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }


    /**
     * app.after-sales.poller.enabled follows app.kafka.enabled (D13): off here, as in the plain h2 profile. The
     * processor stays a bean, for the tests and for the poller of a context that enables it.
     */
    @Test
    void poller_shouldFollowKafkaAndBeOffWithoutIt() {
        Assertions.assertEquals(0, applicationContext.getBeanNamesForType(AfterSalesPoller.class).length);
        Assertions.assertEquals(1, applicationContext.getBeanNamesForType(AfterSalesDeadlineProcessor.class).length);
    }


    /**
     * ADR 0002 section 24: H2 skips a case row another transaction holds (a command answering the case) and claims the
     * next due case, the most overdue first; it neither waits for the lock (which would fail after the 3 s lock
     * timeout) nor gives the claim up because the first row in its order is locked. With both held, nothing is
     * claimed; the processor then fires the case that is free and leaves the held one for a later run.
     */
    @Test
    void caseRowsAnotherTransactionHolds_shouldBeSkippedByTheClaim() throws Exception {
        Customer judy = customer("after-sales-skip-locked-");
        AfterSalesCaseEntity mostOverdue = caseIn(judy, AfterSalesStatus.PENDING_MERCHANT);
        AfterSalesCaseEntity lessOverdue = caseIn(judy, AfterSalesStatus.PENDING_MERCHANT);
        makeDue(mostOverdue.id(), 2);
        makeDue(lessOverdue.id(), 1);
        ExecutorService holders = Executors.newFixedThreadPool(2);
        List<RowLockHolder> held = new ArrayList<>();
        try {
            List<Long> claimed = new ArrayList<>();
            for (AfterSalesCaseEntity due : List.of(mostOverdue, lessOverdue)) {
                claimed.add(claimNextDueId());
                held.add(PostgresRowLocks.holdRowLock(
                        holders, transactionManager, jdbcTemplate, "after_sales_cases", due.id()));
            }
            claimed.add(claimNextDueId());

            Assertions.assertEquals(Arrays.asList(mostOverdue.id(), lessOverdue.id(), null), claimed);
            held.get(1).release();
            Assertions.assertEquals(1, deadlineProcessor.fireDueDeadlines(), "the held case is skipped");
            Assertions.assertEquals("REFUNDING", reload(lessOverdue.id()).status());
            AfterSalesCaseEntity stillHeld = reload(mostOverdue.id());
            Assertions.assertEquals("PENDING_MERCHANT", stillHeld.status());
            Assertions.assertEquals(mostOverdue.version(), stillHeld.version());
            held.get(0).release();
        } finally {
            held.forEach(RowLockHolder::close);
            holders.shutdownNow();
        }
        // Released, the most overdue case fires too, which leaves nothing due.
        Assertions.assertEquals(1, deadlineProcessor.fireDueDeadlines());
        Assertions.assertEquals("REFUNDING", reload(mostOverdue.id()).status());
    }


    /**
     * The id of the case the processor's claim takes now, in a transaction that is then rolled back.
     */
    private Long claimNextDueId() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            status.setRollbackOnly();
            AfterSalesCaseEntity claimed = caseRepository.claimNextDue(LocalDateTime.now());
            return claimed == null ? null : claimed.id();
        });
    }
}
