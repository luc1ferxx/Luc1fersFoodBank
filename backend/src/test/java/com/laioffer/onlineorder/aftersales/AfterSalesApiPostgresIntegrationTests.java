package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.PostgresRowLocks;
import com.laioffer.onlineorder.PostgresRowLocks.RowLockHolder;
import com.laioffer.onlineorder.exception.ConflictException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;


import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;


/**
 * The customer's after-sales API on PostgreSQL, plus the races of two customer commands on one row (ADR 0002 sections
 * 14 and 20): both commands queue behind a lock holder in a chosen order (see {@link PostgresRowLocks}), each side
 * first in every other round, so both serial outcomes run on every run.
 */
@SpringBootTest(properties = {
        // Sessions live in the MockMvc session instead of Redis.
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AfterSalesApiPostgresIntegrationTests extends AbstractAfterSalesApiIntegrationTests {

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
    private AfterSalesCustomerService afterSalesCustomerService;

    /**
     * Two requests with different keys on one order, queued on the order's row: the first one opens the case, the
     * second finds it open and is refused whole, its idempotency row included.
     */
    @Test
    void twoConcurrentRequests_shouldOpenOneCase() throws Exception {
        Customer customer = customer("after-sales-race-open-");
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 4; round++) {
                CompletedOrder order = completedOrder(customer, menuItem(BURGER_KING, 0), menuItem(BURGER_KING, 1));
                Long orderId = order.orderId();
                String wholeKey = "race-whole-" + orderId;
                String itemsKey = "race-items-" + orderId;
                Callable<String> whole = () -> openOutcome(customer, orderId, wholeKey, wholeOrder());
                Callable<String> partial = () -> openOutcome(
                        customer, orderId, itemsKey, items(order.itemIds().get(0), 1));
                boolean wholeFirst = round % 2 == 0;

                Future<String> wholeResult;
                Future<String> partialResult;
                try (RowLockHolder orderRow = PostgresRowLocks.holdRowLock(
                        executor, transactionManager, jdbcTemplate, "orders", orderId)) {
                    if (wholeFirst) {
                        wholeResult = orderRow.queue(whole);
                        partialResult = orderRow.queue(partial);
                    } else {
                        partialResult = orderRow.queue(partial);
                        wholeResult = orderRow.queue(whole);
                    }
                    orderRow.release();
                }

                String winner = (wholeFirst ? wholeResult : partialResult).get(20, TimeUnit.SECONDS);
                String loser = (wholeFirst ? partialResult : wholeResult).get(20, TimeUnit.SECONDS);
                List<AfterSalesCaseEntity> cases = caseRepository.findAllByOrderId(orderId);
                Assertions.assertEquals(1, cases.size(), "round " + round + ": " + winner + " / " + loser);
                AfterSalesCaseEntity opened = cases.get(0);
                Assertions.assertEquals(1, opened.attemptNo());
                Assertions.assertEquals("PENDING_MERCHANT", opened.status());
                Assertions.assertEquals("PENDING_MERCHANT|" + opened.id(), winner);
                Assertions.assertEquals(
                        "CONFLICT|AFTER_SALES_NOT_ELIGIBLE|An after-sales case is already open for this order", loser);
                Assertions.assertEquals(
                        wholeFirst ? order.total().amount() : lineAmount(order.itemIds().get(0)),
                        opened.requestedAmount().setScale(2)
                );
                Assertions.assertEquals(1, idempotencyRows(customer.id(), "after-sales-open:v1:" + orderId,
                        wholeFirst ? wholeKey : itemsKey));
                Assertions.assertEquals(0, idempotencyRows(customer.id(), "after-sales-open:v1:" + orderId,
                        wholeFirst ? itemsKey : wholeKey), "the refused request keeps no key");
                Assertions.assertEquals(List.of("aftersales.case_opened"), eventTypes(opened.id()));
            }
        } finally {
            executor.shutdownNow();
        }
    }


    /**
     * The customer withdrawing a counter-offered case while accepting the offer (two tabs), queued on the case's row:
     * either the case is withdrawn and the acceptance refused, or the offer is accepted (one refund) and the withdrawal
     * refused. Neither ends with both, and the refused command writes nothing.
     */
    @Test
    void withdrawRacingAcceptOffer_shouldEndInOneOfTheTwoSerialOutcomes() throws Exception {
        Customer customer = customer("after-sales-race-withdraw-");
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 4; round++) {
                CompletedOrder order = completedOrder(customer, menuItem(BURGER_KING, 1));
                long caseId = openWholeOrder(customer, order);
                merchantOffers(caseId, "2.00");
                String withdrawKey = "race-withdraw-" + caseId;
                String acceptKey = "race-accept-" + caseId;
                Callable<String> withdraw = () -> outcome(
                        () -> afterSalesCustomerService.withdraw(customer.id(), caseId, withdrawKey));
                Callable<String> accept = () -> outcome(
                        () -> afterSalesCustomerService.acceptOffer(customer.id(), caseId, acceptKey));
                boolean withdrawFirst = round % 2 == 0;

                Future<String> withdrawn;
                Future<String> accepted;
                try (RowLockHolder caseRow = PostgresRowLocks.holdRowLock(
                        executor, transactionManager, jdbcTemplate, "after_sales_cases", caseId)) {
                    if (withdrawFirst) {
                        withdrawn = caseRow.queue(withdraw);
                        accepted = caseRow.queue(accept);
                    } else {
                        accepted = caseRow.queue(accept);
                        withdrawn = caseRow.queue(withdraw);
                    }
                    caseRow.release();
                }

                String withdrawResult = withdrawn.get(20, TimeUnit.SECONDS);
                String acceptResult = accepted.get(20, TimeUnit.SECONDS);
                AfterSalesCaseEntity after = caseRepository.findById(caseId).orElseThrow();
                if (withdrawFirst) {
                    Assertions.assertEquals("CLOSED|" + caseId, withdrawResult);
                    Assertions.assertEquals("CONFLICT|AFTER_SALES_STATUS_CONFLICT|After-sales case is CLOSED; only "
                            + "COUNTER_OFFERED cases can be accepted", acceptResult);
                    Assertions.assertEquals("WITHDRAWN", after.closeReason());
                    Assertions.assertNull(after.approvedAmount());
                    Assertions.assertEquals(0, refunds(caseId));
                    Assertions.assertEquals(
                            List.of("aftersales.case_opened", "aftersales.offer_made", "aftersales.case_closed"),
                            eventTypes(caseId)
                    );
                } else {
                    Assertions.assertEquals("REFUNDING|" + caseId, acceptResult);
                    Assertions.assertEquals("CONFLICT|AFTER_SALES_STATUS_CONFLICT|After-sales case is REFUNDING; only "
                                    + "PENDING_MERCHANT or COUNTER_OFFERED or REJECTED or IN_ARBITRATION cases can be "
                                    + "withdrawn", withdrawResult);
                    Assertions.assertEquals("OFFER_ACCEPTED", after.resolution());
                    Assertions.assertEquals(new BigDecimal("2.00"), after.approvedAmount().setScale(2));
                    Assertions.assertEquals(1, refunds(caseId));
                    Assertions.assertEquals(new BigDecimal("2.00"), jdbcTemplate.queryForObject(
                            "SELECT amount FROM refunds WHERE case_id = ?", BigDecimal.class, caseId).setScale(2));
                    Assertions.assertEquals(
                            List.of("aftersales.case_opened", "aftersales.offer_made", "aftersales.refund_approved"),
                            eventTypes(caseId)
                    );
                }
                // One step each: the opening, the offer and the winner's; the loser wrote nothing, its key included.
                Assertions.assertEquals(2L, after.version(), "round " + round);
                Assertions.assertEquals(3, jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM after_sales_case_history WHERE case_id = ?", Integer.class, caseId));
                Assertions.assertEquals(List.of(1L, 2L, 3L), aggregateSequences(caseId));
                Assertions.assertEquals(withdrawFirst ? 1 : 0, idempotencyRows(
                        customer.id(), "after-sales-withdraw:v1:" + caseId, withdrawKey));
                Assertions.assertEquals(withdrawFirst ? 0 : 1, idempotencyRows(
                        customer.id(), "after-sales-accept-offer:v1:" + caseId, acceptKey));
            }
        } finally {
            executor.shutdownNow();
        }
    }


    private String openOutcome(Customer customer, Long orderId, String key, String body) {
        return outcome(() -> afterSalesCustomerService.open(
                customer.id(), orderId, objectMapper.readValue(body, OpenAfterSalesBody.class), key));
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


    private BigDecimal lineAmount(Long orderItemId) {
        return jdbcTemplate.queryForObject(
                "SELECT price * quantity FROM order_history_items WHERE id = ?", BigDecimal.class, orderItemId)
                .setScale(2);
    }
}
