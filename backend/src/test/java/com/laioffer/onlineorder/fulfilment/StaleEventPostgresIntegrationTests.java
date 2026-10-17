package com.laioffer.onlineorder.fulfilment;


import com.laioffer.onlineorder.fulfilment.dispatch.DispatchService;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantInboxService;
import com.laioffer.onlineorder.fulfilment.timeout.OrderDeadlineService;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.service.EventSequenceTracker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;


import java.util.List;
import java.util.concurrent.atomic.AtomicLong;


import static com.laioffer.onlineorder.fulfilment.FulfilmentTestEvents.orderEvent;


/**
 * Consumers that keep per-aggregate state skip an event whose sequence is not above the last one they applied for the
 * order (a dead letter replayed after later events), on real PostgreSQL. The out-of-order rules they had before
 * sequences still hold: a late order.paid still records the payment, and event types a consumer ignores do not move its
 * position, so a replayed order.accepted after order.preparing still gets a rider.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"
})
@Testcontainers(disabledWithoutDocker = true)
class StaleEventPostgresIntegrationTests {

    private static final long BURGER_KING = 1L;

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


    private static final AtomicLong ORDER_IDS = new AtomicLong(900_000L);

    @Autowired
    private EventSequenceTracker tracker;

    @Autowired
    private OrderDeadlineService orderDeadlineService;

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private MerchantInboxService merchantInboxService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private PlatformTransactionManager transactionManager;


    @Test
    void tracker_shouldApplyNewerEventsAndSkipOlderOnesPerConsumer() {
        long orderId = ORDER_IDS.incrementAndGet();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        double skippedBefore = staleSkipped("tracker-test");

        List<Boolean> applied = transaction.execute(status -> List.of(
                tracker.advance("tracker-test", orderEvent("order.paid", orderId, BURGER_KING, "PAID", 2)),
                tracker.advance("tracker-test", orderEvent("order.accepted", orderId, BURGER_KING, "ACCEPTED", 3)),
                // A copy of an applied event, and an event older than the last applied one.
                tracker.advance("tracker-test", orderEvent("order.accepted", orderId, BURGER_KING, "ACCEPTED", 3)),
                tracker.advance("tracker-test", orderEvent("order.created", orderId, BURGER_KING, "PLACED", 1)),
                // Another consumer keeps its own position.
                tracker.advance("other-test", orderEvent("order.created", orderId, BURGER_KING, "PLACED", 1)),
                // An event written before sequences existed is always applied and moves nothing.
                tracker.advance("tracker-test", orderEvent("order.created", orderId, BURGER_KING, "PLACED"))
        ));

        Assertions.assertEquals(List.of(true, true, false, false, true, true), applied);
        Assertions.assertEquals(3L, position("tracker-test", orderId));
        Assertions.assertEquals(1L, position("other-test", orderId));
        Assertions.assertEquals(skippedBefore + 2, staleSkipped("tracker-test"));
    }


    @Test
    void timeout_aLateOrderCreated_shouldNotSetAPaymentDeadlineForAnOrderThatWasPaid() {
        long orderId = ORDER_IDS.incrementAndGet();

        orderDeadlineService.handle(orderEvent("order.created", orderId, BURGER_KING, "PLACED", 1));
        Assertions.assertEquals(List.of("PAYMENT"), deadlines(orderId));
        orderDeadlineService.handle(orderEvent("order.paid", orderId, BURGER_KING, "PAID", 2));
        Assertions.assertEquals(List.of(), deadlines(orderId));

        // A copy of order.created (a new event id, so processed_events does not catch it) replayed from the DLT.
        orderDeadlineService.handle(orderEvent("order.created", orderId, BURGER_KING, "PLACED", 1));

        Assertions.assertEquals(List.of(), deadlines(orderId), "the stale order.created set no deadline");
        Assertions.assertEquals(2L, position("order-timeout-consumer", orderId));
    }


    @Test
    void dispatch_shouldIgnoreOtherEventTypesForItsPositionAndSkipAnAcceptedOlderThanTheCancel() {
        long orderId = ORDER_IDS.incrementAndGet();

        // order.accepted (3) was dead-lettered for want of a rider; order.preparing (4) is not a dispatch event.
        dispatchService.handle(orderEvent("order.preparing", orderId, BURGER_KING, "PREPARING", 4));
        Assertions.assertNull(position("dispatch-consumer", orderId));
        // The operator replays the dead letter: it still gets a rider.
        dispatchService.handle(orderEvent("order.accepted", orderId, BURGER_KING, "ACCEPTED", 3));
        Assertions.assertEquals(List.of("ASSIGNED"), deliveries(orderId));

        dispatchService.handle(orderEvent("order.cancelled", orderId, BURGER_KING, "CANCELLED", 5));
        double skippedBefore = staleSkipped("dispatch-consumer");
        dispatchService.handle(orderEvent("order.accepted", orderId, BURGER_KING, "ACCEPTED", 3));

        Assertions.assertEquals(List.of("CANCELLED"), deliveries(orderId));
        Assertions.assertEquals(5L, position("dispatch-consumer", orderId));
        Assertions.assertEquals(skippedBefore + 1, staleSkipped("dispatch-consumer"));
    }


    @Test
    void merchantInbox_staleEventsShouldNotMoveTheStatusButALateOrderPaidStillRecordsThePayment() {
        long orderId = ORDER_IDS.incrementAndGet();

        // order.paid (1) and order.accepted (2) were dead-lettered from the merchant group; order.cancelled (3) arrives.
        merchantInboxService.handle(orderEvent("order.cancelled", orderId, BURGER_KING, "CANCELLED", 3));
        Assertions.assertEquals("CANCELLED", inboxStatus(orderId));
        Assertions.assertNull(paidAt(orderId), "a tombstone the merchant does not see");

        merchantInboxService.handle(orderEvent("order.accepted", orderId, BURGER_KING, "ACCEPTED", 2));
        merchantInboxService.handle(orderEvent("order.paid", orderId, BURGER_KING, "PAID", 1));

        Assertions.assertEquals("CANCELLED", inboxStatus(orderId));
        Assertions.assertNotNull(paidAt(orderId), "the late order.paid still recorded the payment");
        Assertions.assertEquals(3L, position("merchant-inbox-consumer", orderId));
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'MERCHANT_ORDER' AND aggregate_id = ?",
                Integer.class, orderId), "a stale order.paid announces nothing");
    }


    @Test
    void merchantInbox_inOrderEvents_shouldStillMoveTheRowForward() {
        long orderId = ORDER_IDS.incrementAndGet();
        OrderEventEnvelope paid = orderEvent("order.paid", orderId, BURGER_KING, "PAID", 1);

        merchantInboxService.handle(paid);
        merchantInboxService.handle(orderEvent("order.accepted", orderId, BURGER_KING, "ACCEPTED", 2));
        merchantInboxService.handle(orderEvent("order.preparing", orderId, BURGER_KING, "PREPARING", 3));

        Assertions.assertEquals("PREPARING", inboxStatus(orderId));
        Assertions.assertEquals(3L, position("merchant-inbox-consumer", orderId));
        // Burger King has active demo merchants, so the paid order was announced through the outbox with sequence 1.
        Assertions.assertEquals(1L, jdbcTemplate.queryForObject("""
                SELECT aggregate_sequence FROM outbox_events
                WHERE aggregate_type = 'MERCHANT_ORDER' AND aggregate_id = ?
                """, Long.class, orderId));
    }


    private Long position(String consumer, long orderId) {
        List<Long> positions = jdbcTemplate.queryForList("""
                SELECT last_sequence FROM consumer_aggregate_sequences
                WHERE consumer_name = ? AND aggregate_type = 'ORDER' AND aggregate_id = ?
                """, Long.class, consumer, orderId);
        return positions.isEmpty() ? null : positions.get(0);
    }


    private List<String> deadlines(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT deadline_type FROM order_deadlines WHERE order_id = ? ORDER BY deadline_type", String.class, orderId);
    }


    private List<String> deliveries(long orderId) {
        return jdbcTemplate.queryForList("SELECT status FROM deliveries WHERE order_id = ?", String.class, orderId);
    }


    private String inboxStatus(long orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM merchant_orders WHERE order_id = ?", String.class, orderId);
    }


    private Object paidAt(long orderId) {
        return jdbcTemplate.queryForObject("SELECT paid_at FROM merchant_orders WHERE order_id = ?", Object.class, orderId);
    }


    /**
     * Over all event types.
     */
    private double staleSkipped(String consumer) {
        return meterRegistry.find("onlineorder.events.stale_skipped").tag("consumer", consumer).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }
}
