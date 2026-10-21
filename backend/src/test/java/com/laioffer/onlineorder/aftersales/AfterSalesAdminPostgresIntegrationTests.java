package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.PostgresRowLocks;
import com.laioffer.onlineorder.PostgresRowLocks.RowLockHolder;
import com.laioffer.onlineorder.audit.AuditActor;
import com.laioffer.onlineorder.exception.ConflictException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;


import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * The admin's after-sales API on PostgreSQL, plus the race of an admin's decision with the customer's withdrawal (ADR
 * 0002 sections 14 and 19.1) and the executor paying a retried refund. Each class runs its own container, so the
 * executor only meets this class's refunds.
 */
@SpringBootTest(properties = {
        // Sessions live in the MockMvc session instead of Redis.
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AfterSalesAdminPostgresIntegrationTests extends AbstractAfterSalesAdminIntegrationTests {

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
    }


    @Autowired
    private AfterSalesAdminService afterSalesAdminService;

    @Autowired
    private AfterSalesCustomerService afterSalesCustomerService;

    @Autowired
    private RefundExecutor refundExecutor;


    /**
     * An admin deciding an escalated case while its customer withdraws it, both after the arbitration deadline passed
     * and before the poller fired: neither command has a deadline guard (section 19.1), so the lock order decides. Both
     * queue behind a lock holder on the case's row in a chosen order (see {@link PostgresRowLocks}), each side first in
     * every other round, with a refund (FULL) and a rejection (REJECT) decision. The command that locks second finds
     * the case closed or refunding and is refused whole: its key, its audit entry and its refund are never written.
     */
    @Test
    void adminDecisionRacingCustomerWithdrawal_shouldEndInTheOutcomeOfWhoeverLockedTheCaseFirst() throws Exception {
        Customer customer = customer("admin-race-");
        AuditActor admin = AuditActor.admin(adminId());
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 4; round++) {
                long caseId = escalatedCase(customer, completedOrder(customer, menuItem(BURGER_KING, 1)));
                jdbcTemplate.update("UPDATE after_sales_cases SET deadline_at = ? WHERE id = ?",
                        LocalDateTime.now().minusMinutes(5), caseId);
                boolean adminFirst = round % 2 == 0;
                boolean refund = round < 2;
                String decision = refund ? "FULL" : "REJECT";
                String arbitrateKey = "race-arbitrate-" + caseId;
                String withdrawKey = "race-withdraw-" + caseId;
                Callable<String> arbitrate = () -> outcome(() -> afterSalesAdminService.arbitrate(caseId,
                        new ArbitrateAfterSalesBody(decision, null, "Decided while the customer withdrew."),
                        arbitrateKey, admin));
                Callable<String> withdraw = () -> outcome(
                        () -> afterSalesCustomerService.withdraw(customer.id(), caseId, withdrawKey));

                Future<String> arbitrated;
                Future<String> withdrawn;
                try (RowLockHolder caseRow = PostgresRowLocks.holdRowLock(
                        executor, transactionManager, jdbcTemplate, "after_sales_cases", caseId)) {
                    if (adminFirst) {
                        arbitrated = caseRow.queue(arbitrate);
                        withdrawn = caseRow.queue(withdraw);
                    } else {
                        withdrawn = caseRow.queue(withdraw);
                        arbitrated = caseRow.queue(arbitrate);
                    }
                    caseRow.release();
                }

                String arbitrateResult = arbitrated.get(20, TimeUnit.SECONDS);
                String withdrawResult = withdrawn.get(20, TimeUnit.SECONDS);
                AfterSalesCaseEntity after = caseRepository.findById(caseId).orElseThrow();
                String context = "round " + round + ": " + arbitrateResult + " / " + withdrawResult;
                if (adminFirst) {
                    String decided = refund ? "REFUNDING" : "CLOSED";
                    Assertions.assertEquals(decided + "|" + caseId, arbitrateResult, context);
                    Assertions.assertEquals("CONFLICT|AFTER_SALES_STATUS_CONFLICT|After-sales case is " + decided
                            + "; only PENDING_MERCHANT or COUNTER_OFFERED or REJECTED or IN_ARBITRATION cases can be "
                            + "withdrawn", withdrawResult, context);
                    Assertions.assertEquals(refund ? "ARBITRATION_FULL" : null, after.resolution(), context);
                    Assertions.assertEquals(refund ? null : "ARBITRATION_REJECTED", after.closeReason(), context);
                    Assertions.assertEquals("Decided while the customer withdrew.", after.adminNote(), context);
                    Assertions.assertEquals(refund ? 1 : 0, refunds(caseId), context);
                    Assertions.assertEquals(1, auditEntries("AFTER_SALES_CASE", caseId).size(), context);
                    Assertions.assertEquals(refund ? "aftersales.refund_approved" : "aftersales.case_closed",
                            eventTypes(caseId).get(3), context);
                } else {
                    Assertions.assertEquals("CLOSED|" + caseId, withdrawResult, context);
                    Assertions.assertEquals("CONFLICT|AFTER_SALES_STATUS_CONFLICT|After-sales case is CLOSED; only "
                            + "IN_ARBITRATION cases can be arbitrated", arbitrateResult, context);
                    Assertions.assertEquals("WITHDRAWN", after.closeReason(), context);
                    Assertions.assertNull(after.approvedAmount(), context);
                    Assertions.assertNull(after.adminNote(), context);
                    Assertions.assertEquals(0, refunds(caseId), context);
                    Assertions.assertEquals(List.of(), auditEntries("AFTER_SALES_CASE", caseId), context);
                    Assertions.assertEquals("aftersales.case_closed", eventTypes(caseId).get(3), context);
                }
                // The opening, the rejection, the escalation and the winner's step; the loser wrote nothing.
                Assertions.assertEquals(3L, after.version(), context);
                Assertions.assertNull(after.deadlineAt(), context);
                Assertions.assertEquals(4, eventTypes(caseId).size(), context);
                Assertions.assertEquals(4, jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM after_sales_case_history WHERE case_id = ?", Integer.class, caseId));
                Assertions.assertEquals(adminFirst ? 1 : 0, idempotencyRows(
                        adminId(), "after-sales-arbitrate:v1:" + caseId, arbitrateKey), context);
                Assertions.assertEquals(adminFirst ? 0 : 1, idempotencyRows(
                        customer.id(), "after-sales-withdraw:v1:" + caseId, withdrawKey), context);
            }
        } finally {
            executor.shutdownNow();
        }
    }


    /**
     * A retried refund is due at once: the executor's next run pays it with the case's gateway key and the case ends
     * REFUNDED, as if the gateway had never failed.
     */
    @Test
    void retriedRefund_shouldBePaidOnTheExecutorsNextRun() throws Exception {
        Customer grace = customer("admin-retry-paid-");
        MockHttpSession admin = admin();
        CompletedOrder order = completedOrder(grace, menuItem(BURGER_KING, 1));
        long caseId = escalatedCase(grace, order);
        arbitrate(admin, caseId, "full-" + caseId, arbitration("FULL", null, "Refund it.")).andExpect(status().isOk());
        long refundId = givenUp(caseId, "simulated gateway timeout");
        mockMvc.perform(post("/admin/refunds/{id}/retry", refundId).session(admin)).andExpect(status().isOk());

        Assertions.assertTrue(refundExecutor.runDueRefunds() >= 1);

        RefundEntity paid = refundRepository.findById(refundId).orElseThrow();
        Assertions.assertEquals("SUCCEEDED", paid.status());
        Assertions.assertEquals(1, paid.attempts(), "the attempts started over");
        Assertions.assertEquals("after-sales-case:" + caseId, paid.gatewayKey());
        Assertions.assertNull(paid.lastError(), "a success clears the error");
        JsonNode refunded = json(mockMvc.perform(get("/admin/after-sales/{id}", caseId).session(admin))
                .andExpect(status().isOk()));
        Assertions.assertEquals("REFUNDED", refunded.get("status").asString());
        Assertions.assertEquals(order.total().amount(), refunded.get("refunded_amount").decimalValue().setScale(2));
        Assertions.assertEquals("aftersales.case_refunded", eventTypes(caseId).get(4));
    }


    /**
     * "STATUS|caseId" of a command that went through, "CONFLICT|code|message" of a refused one.
     */
    private static String outcome(Callable<AfterSalesCaseDto> command) {
        try {
            AfterSalesCaseDto result = command.call();
            return result.status() + "|" + result.id();
        } catch (ConflictException ex) {
            return "CONFLICT|" + ex.getCode() + "|" + ex.getMessage();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
