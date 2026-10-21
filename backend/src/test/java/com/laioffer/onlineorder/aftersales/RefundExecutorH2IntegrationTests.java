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
 * The refund executor on H2 (the {@code h2} profile): the same claim (FOR UPDATE SKIP LOCKED), lease, record and case
 * step as on PostgreSQL.
 */
@SpringBootTest(properties = {
        // Sessions live in the MockMvc session instead of Redis.
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class RefundExecutorH2IntegrationTests extends AbstractRefundExecutorIntegrationTests {

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
     * ADR 0002 section 24: H2 skips a refund row another transaction holds, in both claims, and takes the next due
     * refund; it neither waits for the lock (which would fail after the 3 s lock timeout) nor gives the claim up
     * because the first row in its order is locked. Each claim here takes the next refund, expired leases (longest
     * expired first) before due PENDING refunds (most overdue first); another transaction then holds that refund, and
     * the next claim passes over it. With all four held, nothing is claimed.
     */
    @Test
    void refundRowsAnotherTransactionHolds_shouldBeSkippedByBothClaims() throws Exception {
        Customer ivan = customer("refund-skip-locked-");
        List<RefundEntity> inClaimOrder = new ArrayList<>();
        for (int refund = 0; refund < 4; refund++) {
            inClaimOrder.add(refundOf(cancelledCase(ivan).id()));
        }
        LocalDateTime now = LocalDateTime.now();
        leaseAsIfClaimed(inClaimOrder.get(0).id(), 1, now.minusMinutes(2));
        leaseAsIfClaimed(inClaimOrder.get(1).id(), 1, now.minusMinutes(1));
        dueSince(inClaimOrder.get(2).id(), now.minusMinutes(2));
        dueSince(inClaimOrder.get(3).id(), now.minusMinutes(1));
        ExecutorService holders = Executors.newFixedThreadPool(inClaimOrder.size());
        List<RowLockHolder> held = new ArrayList<>();
        try {
            List<Long> claimed = new ArrayList<>();
            for (RefundEntity refund : inClaimOrder) {
                claimed.add(claimNextDueId());
                held.add(PostgresRowLocks.holdRowLock(
                        holders, transactionManager, jdbcTemplate, "refunds", refund.id()));
            }
            claimed.add(claimNextDueId());

            Assertions.assertEquals(Arrays.asList(inClaimOrder.get(0).id(), inClaimOrder.get(1).id(),
                    inClaimOrder.get(2).id(), inClaimOrder.get(3).id(), null), claimed);
            for (RowLockHolder holder : held) {
                holder.release();
            }
        } finally {
            held.forEach(RowLockHolder::close);
            holders.shutdownNow();
        }
        // Nothing held any more: the executor takes all four, which leaves nothing due.
        Assertions.assertEquals(4, refundExecutor.runDueRefunds());
    }


    /**
     * The id of the refund the executor's claim takes now, in a transaction that is then rolled back.
     */
    private Long claimNextDueId() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            status.setRollbackOnly();
            RefundEntity claimed = refundRepository.claimNextDue(LocalDateTime.now());
            return claimed == null ? null : claimed.id();
        });
    }


    private void dueSince(Long refundId, LocalDateTime nextAttemptAt) {
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE refunds SET next_attempt_at = ? WHERE id = ?", nextAttemptAt, refundId));
    }
}
