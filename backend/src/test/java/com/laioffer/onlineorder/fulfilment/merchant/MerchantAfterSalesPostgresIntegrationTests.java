package com.laioffer.onlineorder.fulfilment.merchant;


import com.laioffer.onlineorder.PostgresRowLocks;
import com.laioffer.onlineorder.PostgresRowLocks.RowLockHolder;
import com.laioffer.onlineorder.aftersales.AfterSalesCustomerService;
import com.laioffer.onlineorder.exception.ConflictException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;


import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;


/**
 * The merchant's side of after-sales on PostgreSQL, plus the race of the merchant's answer with the customer's
 * withdrawal on one case (ADR 0002 sections 14 and 19.1): both commands queue behind a lock holder on the case row in a
 * chosen order (see {@link PostgresRowLocks}), each side first in every other round, so both serial outcomes run on
 * every run. The context is closed after the class: its container stops with it.
 */
@SpringBootTest(properties = {
        // Sessions live in the MockMvc session instead of Redis.
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext
class MerchantAfterSalesPostgresIntegrationTests extends AbstractMerchantAfterSalesIntegrationTests {

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
    private MerchantAfterSalesService merchantAfterSalesService;

    @Autowired
    private AfterSalesCustomerService afterSalesCustomerService;

    @Autowired
    private PlatformTransactionManager transactionManager;


    /**
     * The merchant approving a request while the customer withdraws it, queued on the case's row: either the case is
     * refunded (one refund) and the withdrawal refused, or it is withdrawn and the approval refused. Neither ends with
     * both, and the refused command writes nothing: no step, no key, and the merchant's inbox row stays as it was.
     */
    @Test
    void merchantApproveRacingCustomerWithdraw_shouldEndInOneOfTheTwoSerialOutcomes() throws Exception {
        Customer customer = customer("merchant-race-");
        Long merchantId = merchantId();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 4; round++) {
                long caseId = openWholeOrder(customer, completedOrder(customer, menuItem(BURGER_KING, 0)));
                deliverEvents(caseId);
                String withdrawKey = "race-withdraw-" + caseId;
                Callable<String> approve = () -> outcome(
                        () -> merchantAfterSalesService.approve(merchantId, caseId).status());
                Callable<String> withdraw = () -> outcome(
                        () -> afterSalesCustomerService.withdraw(customer.id(), caseId, withdrawKey).status());
                boolean approveFirst = round % 2 == 0;

                Future<String> approved;
                Future<String> withdrawn;
                try (RowLockHolder caseRow = PostgresRowLocks.holdRowLock(
                        executor, transactionManager, jdbcTemplate, "after_sales_cases", caseId)) {
                    if (approveFirst) {
                        approved = caseRow.queue(approve);
                        withdrawn = caseRow.queue(withdraw);
                    } else {
                        withdrawn = caseRow.queue(withdraw);
                        approved = caseRow.queue(approve);
                    }
                    caseRow.release();
                }

                String approveResult = approved.get(20, TimeUnit.SECONDS);
                String withdrawResult = withdrawn.get(20, TimeUnit.SECONDS);
                Map<String, Object> after = caseRow(caseId);
                if (approveFirst) {
                    Assertions.assertEquals("REFUNDING", approveResult, "round " + round);
                    Assertions.assertEquals("CONFLICT|AFTER_SALES_STATUS_CONFLICT|After-sales case is REFUNDING; only "
                            + "PENDING_MERCHANT or COUNTER_OFFERED or REJECTED or IN_ARBITRATION cases can be "
                            + "withdrawn", withdrawResult);
                    Assertions.assertEquals("MERCHANT_APPROVED", after.get("resolution"));
                    Assertions.assertEquals(1, refunds(caseId));
                    Assertions.assertEquals(
                            List.of("aftersales.case_opened", "aftersales.refund_approved"), eventTypes(caseId));
                    Assertions.assertEquals("REFUNDING|1", projection(caseId));
                } else {
                    Assertions.assertEquals("CLOSED", withdrawResult, "round " + round);
                    Assertions.assertEquals("CONFLICT|AFTER_SALES_STATUS_CONFLICT|After-sales case is CLOSED; only "
                            + "PENDING_MERCHANT cases can be approved", approveResult);
                    Assertions.assertEquals("WITHDRAWN", after.get("close_reason"));
                    Assertions.assertNull(after.get("merchant_decision"));
                    Assertions.assertEquals(0, refunds(caseId));
                    Assertions.assertEquals(
                            List.of("aftersales.case_opened", "aftersales.case_closed"), eventTypes(caseId));
                    // The refused approval rolled back with the inbox row; the closing event brings it up to date.
                    Assertions.assertEquals("PENDING_MERCHANT|0", projection(caseId));
                    deliverEvents(caseId);
                    Assertions.assertEquals("CLOSED|1", projection(caseId));
                }
                // One step each: the opening and the winner's; the loser wrote nothing, its key included.
                Assertions.assertEquals(1L, ((Number) after.get("version")).longValue(), "round " + round);
                Assertions.assertEquals(2, historyRows(caseId));
                Assertions.assertEquals(approveFirst ? 0 : 1, jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM idempotency_requests
                        WHERE customer_id = ?
                          AND scope = ?
                          AND idempotency_key = ?
                        """, Integer.class, customer.id(), "after-sales-withdraw:v1:" + caseId, withdrawKey));
            }
        } finally {
            executor.shutdownNow();
        }
    }


    /**
     * The status a command answered with, or "CONFLICT|code|message" when it was refused.
     */
    private static String outcome(Callable<String> command) {
        try {
            return command.call();
        } catch (ConflictException ex) {
            return "CONFLICT|" + ex.getCode() + "|" + ex.getMessage();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
