package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.CustomerService;
import com.laioffer.onlineorder.service.OrderService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.ActiveProfiles;


import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;


/**
 * The demo profile (ADR 0002 section 20.4) on top of h2, as {@code SPRING_PROFILES_ACTIVE=h2,demo} runs it: the short
 * after-sales time limits, and the after-sales poller running although h2 has no Kafka, so that a request the merchant
 * leaves unanswered times out and its refund completes on the poller's own thread, with nobody calling the processor
 * or the executor.
 */
@SpringBootTest(properties = {
        // Sessions and the cache live in memory instead of Redis; everything else is the profiles'.
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration",
        "spring.cache.type=none"
})
@ActiveProfiles({"h2", "demo"})
class AfterSalesDemoProfileTests {

    private static final Duration POLLER_WAIT = Duration.ofSeconds(20);

    private static final LocalDateTime ENTERED = LocalDateTime.of(2026, 10, 21, 12, 0);

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private AfterSalesDeadlines deadlines;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private AfterSalesCustomerService afterSalesCustomerService;

    @Autowired
    private AfterSalesCaseRepository caseRepository;


    @Test
    void demoProfile_shouldShortenTheAfterSalesTimeLimits() {
        Assertions.assertEquals(Duration.ofHours(2), deadlines.requestWindow());
        Assertions.assertEquals(
                ENTERED.plusMinutes(2), deadlines.deadlineAt(AfterSalesStatus.PENDING_MERCHANT, ENTERED));
        Assertions.assertEquals(
                ENTERED.plusMinutes(3), deadlines.deadlineAt(AfterSalesStatus.COUNTER_OFFERED, ENTERED));
        Assertions.assertEquals(ENTERED.plusMinutes(3), deadlines.deadlineAt(AfterSalesStatus.REJECTED, ENTERED));
        Assertions.assertEquals(
                ENTERED.plusMinutes(5), deadlines.deadlineAt(AfterSalesStatus.IN_ARBITRATION, ENTERED));
    }


    /**
     * h2 turns Kafka off, which the poller follows by default; the demo profile turns the poller on anyway, on its own
     * thread and without a second TaskScheduler.
     */
    @Test
    void demoProfile_shouldRunTheAfterSalesPollerWithoutKafka() {
        Assertions.assertEquals("false", environment.getProperty("app.kafka.enabled"));
        Assertions.assertEquals("true", environment.getProperty("app.after-sales.poller.enabled"));
        Assertions.assertTrue(applicationContext.getBean(AfterSalesPoller.class).isRunning());
        Assertions.assertEquals(
                List.of("taskScheduler"), List.of(applicationContext.getBeanNamesForType(TaskScheduler.class)));
    }


    /**
     * The after-sales demo (docs/after-sales.md): a request on a completed order gets the merchant's two minutes; once
     * they are over (here moved into the past instead of waited for) the poller fires MERCHANT_TIMEOUT and executes the
     * refund it created, and the case ends REFUNDED.
     */
    @Test
    void unansweredRequest_shouldTimeOutAndBeRefundedByThePoller() {
        String email = "after-sales-demo-" + System.nanoTime() + "@example.com";
        customerService.signUp(email, "demo123", "Demo", "Customer");
        Long customerId = jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
        Long menuItemId = jdbcTemplate.queryForObject("""
                SELECT menu_items.id
                FROM menu_items
                JOIN restaurants ON restaurants.id = menu_items.restaurant_id
                WHERE restaurants.name = 'Burger King'
                  AND menu_items.price > 0
                ORDER BY menu_items.id
                LIMIT 1
                """, Long.class);
        cartService.addMenuItemToCart(customerId, menuItemId);
        OrderDto paid = cartService.checkout(customerId, "PAID");
        for (String next : List.of("ACCEPTED", "PREPARING", "COMPLETED")) {
            orderService.transitionOrderStatus(paid.id(), next);
        }
        long caseId = afterSalesCustomerService.open(customerId, paid.id(), new OpenAfterSalesBody(
                "WHOLE_ORDER", List.of(), "MISSING_ITEM", "The burger was missing."), "demo-open-" + paid.id()).id();
        AfterSalesCaseEntity opened = caseRepository.findById(caseId).orElseThrow();
        Assertions.assertEquals(opened.createdAt().plusMinutes(2), opened.deadlineAt());

        jdbcTemplate.update("UPDATE after_sales_cases SET deadline_at = ? WHERE id = ?",
                LocalDateTime.now().minusSeconds(1), caseId);

        AfterSalesCaseEntity refunded = await("the poller to time out and refund case " + caseId, () ->
                caseRepository.findById(caseId).filter(current -> "REFUNDED".equals(current.status())).orElse(null));
        Assertions.assertEquals("MERCHANT_TIMEOUT", refunded.resolution());
        Assertions.assertEquals(0, refunded.requestedAmount().compareTo(refunded.refundedAmount()));
        Assertions.assertEquals("SUCCEEDED", jdbcTemplate.queryForObject(
                "SELECT status FROM refunds WHERE case_id = ?", String.class, caseId));
    }


    /**
     * Waits for the poller's thread, which runs every second, to produce a value.
     */
    private static <T> T await(String what, Supplier<T> value) {
        Instant deadline = Instant.now().plus(POLLER_WAIT);
        while (true) {
            T current = value.get();
            if (current != null) {
                return current;
            }
            if (Instant.now().isAfter(deadline)) {
                Assertions.fail("Timed out after " + POLLER_WAIT + " waiting for " + what);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + what, ex);
            }
        }
    }
}
