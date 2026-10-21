package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.PostgresRowLocks;
import com.laioffer.onlineorder.PostgresRowLocks.RowLockHolder;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantInboxService;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantOrderService;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands;
import com.laioffer.onlineorder.fulfilment.timeout.OrderDeadlineProcessor;
import com.laioffer.onlineorder.fulfilment.timeout.OrderDeadlineService;
import com.laioffer.onlineorder.messaging.MerchantEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.contract.EventTopic;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import com.laioffer.onlineorder.service.OrderNotificationService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;


/**
 * The automatic refund of a cancelled paid order on PostgreSQL, through every path that cancels a paid order: the
 * customer's cancel of an ACCEPTED order, the merchant's reject, the accept timeout and an admin's cancel (in another
 * currency). Also the race of the customer's cancel with the merchant's reject, queued on the order's row in both
 * orders (see {@link PostgresRowLocks}), and the rollback of the cancel when its after-sales event cannot be stored
 * (ADR 0002 section 19.13). Kafka is off: the tests feed the merchant inbox and the timeout module the events the
 * outbox recorded, as their listeners would.
 */
@SpringBootTest(properties = {
        // Sessions live in the MockMvc session instead of Redis.
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CancellationRefundPostgresIntegrationTests extends AbstractCancellationRefundIntegrationTests {

    static final String BURGER_KING_MERCHANT = "merchant.burgerking@laifood.com";

    static final String FASHION_WOK = "Fashion Wok";

    static final String OUT_OF_BUNS = "Out of buns";

    static final String FORCED_FAILURE = "forced after-sales outbox failure";

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
    private MerchantInboxService merchantInboxService;

    @Autowired
    private MerchantOrderService merchantOrderService;

    @Autowired
    private OrderDeadlineService orderDeadlineService;

    @Autowired
    private OrderDeadlineProcessor orderDeadlineProcessor;

    @Autowired
    private OrderNotificationService orderNotificationService;

    /**
     * The real outbox, which a test can make refuse the after-sales events only ({@link #refuseAfterSalesEvents}).
     */
    @MockitoSpyBean
    private OrderEventOutboxService orderEventOutboxService;


    @Test
    void customerCancel_ofAnAcceptedOrder_shouldOpenTheRefund() throws Exception {
        Customer erin = customer("cancel-refund-accepted-");
        OrderDto paid = paidOrder(erin, menuItem(BURGER_KING, 0), menuItem(BURGER_KING, 2));
        orderService.transitionOrderStatus(paid.id(), "ACCEPTED");

        OrderDto cancelled = orderService.idempotentCancelOrder(erin.id(), paid.id(), "cancel-" + paid.id());

        Assertions.assertEquals("CANCELLED", cancelled.status());
        assertRefundOpened(paid.id());
        // ADR 0002 section 24 item 7: the customer's own cancel promises the refund too.
        orderNotificationService.recordOrderEvent(latestOrderEvent(paid.id(), "order.cancelled"));
        Assertions.assertEquals("Order #" + orderNo(paid.id()) + " was cancelled. Your payment will be refunded "
                + "automatically.", cancelledNotice(paid.id()));
    }


    /**
     * Cancels that open no refund keep their notices: an unpaid order's (the customer's and an admin's cancel), and
     * the customer's cancel of an order without a capture (an admin marked it PAID) or of 0.00.
     */
    @Test
    void cancelNotices_ofOrdersWithoutACaptureAboveZero_shouldNotPromiseARefund() throws Exception {
        Customer jack = customer("cancel-refund-notice-");
        OrderDto unpaid = placedOrder(jack, menuItem(BURGER_KING, 0));
        OrderDto unpaidByAdmin = placedOrder(jack, menuItem(BURGER_KING, 1));
        OrderDto markedPaid = placedOrder(jack, menuItem(BURGER_KING, 0));
        orderService.transitionOrderStatus(markedPaid.id(), "PAID");
        OrderDto free = paidOrder(jack, freeMenuItem(BURGER_KING));

        orderService.idempotentCancelOrder(jack.id(), unpaid.id(), "cancel-" + unpaid.id());
        orderService.idempotentTransitionOrderStatus(
                adminId(), unpaidByAdmin.id(), "CANCELLED", "admin-cancel-" + unpaidByAdmin.id());
        orderService.idempotentCancelOrder(jack.id(), markedPaid.id(), "cancel-" + markedPaid.id());
        orderService.idempotentCancelOrder(jack.id(), free.id(), "cancel-" + free.id());

        for (OrderDto order : List.of(unpaid, unpaidByAdmin, markedPaid, free)) {
            assertNothingOpened(order.id());
            orderNotificationService.recordOrderEvent(latestOrderEvent(order.id(), "order.cancelled"));
            Assertions.assertEquals("Order #" + orderNo(order.id()) + " was cancelled.", cancelledNotice(order.id()));
        }
    }


    /**
     * The merchant's reject opens the refund, and the customer's notice of the reject says it is on its way.
     */
    @Test
    void merchantReject_shouldOpenTheRefundThatTheNoticePromises() throws Exception {
        Customer frank = customer("cancel-refund-reject-");
        OrderDto paid = paidOrder(frank, menuItem(BURGER_KING, 1), menuItem(BURGER_KING, 1));
        merchantInboxService.handle(latestOrderEvent(paid.id(), "order.paid"));

        Assertions.assertEquals("CANCELLED",
                merchantOrderService.reject(customerIdOf(BURGER_KING_MERCHANT), paid.id(), OUT_OF_BUNS).status());

        assertRefundOpened(paid.id());
        Assertions.assertEquals("MERCHANT_REJECTED: " + OUT_OF_BUNS, cancelReason(paid.id()));
        orderNotificationService.recordOrderEvent(latestOrderEvent(paid.id(), "order.cancelled"));
        Assertions.assertEquals("The restaurant could not take order #" + orderNo(paid.id()) + ": " + OUT_OF_BUNS
                + ". Your payment will be refunded automatically.", cancelledNotice(paid.id()));
    }


    /**
     * An order an admin marked PAID has no capture: the merchant's reject opens nothing, and the notice still points
     * the customer to support.
     */
    @Test
    void merchantReject_ofAnOrderWithoutACapture_shouldOpenNothingAndPointToSupport() throws Exception {
        Customer gina = customer("cancel-refund-reject-uncaptured-");
        OrderDto markedPaid = placedOrder(gina, menuItem(BURGER_KING, 0));
        orderService.transitionOrderStatus(markedPaid.id(), "PAID");
        merchantInboxService.handle(latestOrderEvent(markedPaid.id(), "order.paid"));

        merchantOrderService.reject(customerIdOf(BURGER_KING_MERCHANT), markedPaid.id(), OUT_OF_BUNS);

        Assertions.assertEquals("CANCELLED", orderStatus(markedPaid.id()));
        assertNothingOpened(markedPaid.id());
        orderNotificationService.recordOrderEvent(latestOrderEvent(markedPaid.id(), "order.cancelled"));
        Assertions.assertEquals("The restaurant could not take order #" + orderNo(markedPaid.id()) + ": " + OUT_OF_BUNS
                + ". Please contact support about your payment.", cancelledNotice(markedPaid.id()));
    }


    @Test
    void acceptTimeout_shouldOpenTheRefund() throws Exception {
        Customer hank = customer("cancel-refund-timeout-");
        OrderDto paid = paidOrder(hank, menuItem(FASHION_WOK, 0));
        armAcceptDeadline(paid.id());

        orderDeadlineProcessor.fireDueDeadlines();

        Assertions.assertEquals(OrderLifecycleCommands.MERCHANT_TIMEOUT, cancelReason(paid.id()));
        Assertions.assertEquals(0, deadlines(paid.id()));
        assertRefundOpened(paid.id());
        orderNotificationService.recordOrderEvent(latestOrderEvent(paid.id(), "order.cancelled"));
        Assertions.assertTrue(cancelledNotice(paid.id()).endsWith("Your payment will be refunded automatically."));
    }


    /**
     * An admin's cancel of a paid order in euros: the case, its lines, history, refund and event are all in euros.
     */
    @Test
    void adminCancel_ofAPaidOrderInEuros_shouldOpenTheRefundInEuros() throws Exception {
        Customer iris = customer("cancel-refund-admin-");
        String restaurant = "Cancel Refund EUR " + System.nanoTime();
        jdbcTemplate.update("INSERT INTO restaurants (name, currency) VALUES (?, 'EUR')", restaurant);
        insertEuroMenuItem(restaurant, "Croissant", "2.35");
        insertEuroMenuItem(restaurant, "Cafe Creme", "3.10");
        OrderDto paid = paidOrder(iris, menuItem(restaurant, 0), menuItem(restaurant, 0), menuItem(restaurant, 1));
        Assertions.assertEquals("EUR", paid.currency());
        Assertions.assertEquals(new BigDecimal("7.80"), paid.totalPrice().setScale(2));

        orderService.idempotentTransitionOrderStatus(adminId(), paid.id(), "CANCELLED", "admin-cancel-" + paid.id());

        AfterSalesCaseEntity opened = assertRefundOpened(paid.id());
        Assertions.assertEquals("EUR", opened.currency());
        Assertions.assertEquals(List.of("EUR", "EUR"), jdbcTemplate.queryForList(
                "SELECT currency FROM after_sales_case_lines WHERE case_id = ?", String.class, opened.id()));
        Assertions.assertEquals("EUR", jdbcTemplate.queryForObject(
                "SELECT currency FROM refunds WHERE case_id = ?", String.class, opened.id()));
        Assertions.assertEquals("EUR", jdbcTemplate.queryForObject(
                "SELECT currency FROM after_sales_case_history WHERE case_id = ?", String.class, opened.id()));
        // ADR 0002 section 24 item 7: an admin's cancel promises the refund too.
        orderNotificationService.recordOrderEvent(latestOrderEvent(paid.id(), "order.cancelled"));
        Assertions.assertEquals("Order #" + orderNo(paid.id()) + " was cancelled. Your payment will be refunded "
                + "automatically.", cancelledNotice(paid.id()));
    }


    /**
     * ADR 0002 section 14 P3, race 9: the customer's cancel and the merchant's reject of a paid order, queued on the
     * order's row in both orders. Whoever cancels first, the other finds the order cancelled (the reject is refused,
     * the cancel answers with the order), and the order gets exactly one ORDER_CANCELLED case with one refund of its
     * total, opened by the winner at the time of its cancel.
     */
    @Test
    void customerCancelRacingMerchantReject_shouldOpenExactlyOneRefund() throws Exception {
        Long merchantId = customerIdOf(BURGER_KING_MERCHANT);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 4; round++) {
                Customer customer = customer("cancel-refund-race-");
                OrderDto paid = paidOrder(customer, menuItem(BURGER_KING, 0), menuItem(BURGER_KING, 1));
                merchantInboxService.handle(latestOrderEvent(paid.id(), "order.paid"));
                String key = "race-cancel-" + paid.id();
                Callable<String> cancelCommand = () -> {
                    OrderDto cancelled = orderService.idempotentCancelOrder(customer.id(), paid.id(), key);
                    return cancelled.status() + "|" + cancelled.cancelReason();
                };
                Callable<String> rejectCommand = () -> {
                    try {
                        return merchantOrderService.reject(merchantId, paid.id(), OUT_OF_BUNS).status();
                    } catch (ConflictException ex) {
                        return "CONFLICT|" + ex.getCode() + "|" + ex.getMessage();
                    }
                };
                boolean cancelFirst = round % 2 == 0;

                Future<String> cancel;
                Future<String> reject;
                try (RowLockHolder orderRow = PostgresRowLocks.holdRowLock(
                        executor, transactionManager, jdbcTemplate, "orders", paid.id())) {
                    if (cancelFirst) {
                        cancel = orderRow.queue(cancelCommand);
                        reject = orderRow.queue(rejectCommand);
                    } else {
                        reject = orderRow.queue(rejectCommand);
                        cancel = orderRow.queue(cancelCommand);
                    }
                    orderRow.release();
                }

                String cancelResult = cancel.get(20, TimeUnit.SECONDS);
                String rejectResult = reject.get(20, TimeUnit.SECONDS);
                String rejected = "MERCHANT_REJECTED: " + OUT_OF_BUNS;
                if (cancelFirst) {
                    Assertions.assertEquals("CANCELLED|" + OrderLifecycleCommands.CUSTOMER_CANCELLED, cancelResult);
                    Assertions.assertEquals("CONFLICT|" + OrderLifecycleCommands.ORDER_STATUS_CONFLICT
                            + "|Order is CANCELLED (CUSTOMER_CANCELLED); only PAID orders can move to CANCELLED",
                            rejectResult);
                    Assertions.assertEquals(OrderLifecycleCommands.CUSTOMER_CANCELLED, cancelReason(paid.id()));
                } else {
                    Assertions.assertEquals("CANCELLED", rejectResult);
                    // The customer's cancel found the order cancelled and answers with it, unchanged.
                    Assertions.assertEquals("CANCELLED|" + rejected, cancelResult);
                    Assertions.assertEquals(rejected, cancelReason(paid.id()));
                }
                Assertions.assertEquals(1, jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM order_status_history
                        WHERE order_id = ?
                          AND to_status = 'CANCELLED'
                        """, Integer.class, paid.id()), "round " + round);
                AfterSalesCaseEntity opened = assertRefundOpened(paid.id());
                Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM after_sales_case_history WHERE case_id = ?", Integer.class, opened.id()));
            }
        } finally {
            executor.shutdownNow();
        }
    }


    /**
     * ADR 0002 section 19.13: when the after-sales event of the refund cannot be stored, the whole cancel rolls back
     * whichever path took it (the customer's cancel of a PAID order, an admin's cancel of an ACCEPTED one, the accept
     * timeout): the order keeps its status, and no case, refund, order event, history row, audit row or idempotency
     * record is left. The customer's retry with the same key then cancels and opens the refund.
     */
    @Test
    void whenTheAfterSalesEventCannotBeStored_theCancelShouldRollBackWhole() throws Exception {
        Customer jack = customer("cancel-refund-rollback-");
        OrderDto paid = paidOrder(jack, menuItem(BURGER_KING, 0));
        OrderDto accepted = paidOrder(jack, menuItem(BURGER_KING, 1));
        orderService.transitionOrderStatus(accepted.id(), "ACCEPTED");
        OrderDto timedOut = paidOrder(jack, menuItem(FASHION_WOK, 0));
        armAcceptDeadline(timedOut.id());
        OrderEventOutboxService outbox = refuseAfterSalesEvents();

        String customerKey = "rollback-cancel-" + paid.id();
        IllegalStateException customerCancel = Assertions.assertThrows(IllegalStateException.class,
                () -> orderService.idempotentCancelOrder(jack.id(), paid.id(), customerKey));
        String adminKey = "rollback-admin-" + accepted.id();
        IllegalStateException adminCancel = Assertions.assertThrows(IllegalStateException.class,
                () -> orderService.idempotentTransitionOrderStatus(adminId(), accepted.id(), "CANCELLED", adminKey));
        orderDeadlineProcessor.fireDueDeadlines();

        Assertions.assertEquals(FORCED_FAILURE, customerCancel.getMessage());
        Assertions.assertEquals(FORCED_FAILURE, adminCancel.getMessage());
        Assertions.assertEquals("PAID", orderStatus(paid.id()));
        Assertions.assertEquals("ACCEPTED", orderStatus(accepted.id()));
        Assertions.assertEquals("PAID", orderStatus(timedOut.id()));
        for (Long orderId : List.of(paid.id(), accepted.id(), timedOut.id())) {
            assertNothingOpened(orderId);
            Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM after_sales_cases WHERE order_id = ?", Integer.class, orderId));
            Assertions.assertEquals(0, jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM outbox_events
                    WHERE aggregate_type = 'ORDER'
                      AND aggregate_id = ?
                      AND event_type = 'order.cancelled'
                    """, Integer.class, orderId));
            Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'CANCELLED'",
                    Integer.class, orderId));
        }
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_requests WHERE idempotency_key IN (?, ?)",
                Integer.class, customerKey, adminKey));
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'order.status.update' AND target_id = ?",
                Integer.class, String.valueOf(accepted.id())));
        // The deadline is kept and backs off, so the timeout tries again later.
        Map<String, Object> deadline = jdbcTemplate.queryForMap(
                "SELECT attempts, last_error, due_at FROM order_deadlines WHERE order_id = ?", timedOut.id());
        Assertions.assertEquals(1, ((Number) deadline.get("attempts")).intValue());
        Assertions.assertTrue(String.valueOf(deadline.get("last_error")).contains(FORCED_FAILURE),
                () -> String.valueOf(deadline.get("last_error")));

        Mockito.doCallRealMethod().when(outbox)
                .enqueueEvent(Mockito.eq(AFTER_SALES_TOPIC), Mockito.anyString(), Mockito.any());
        Assertions.assertEquals("CANCELLED",
                orderService.idempotentCancelOrder(jack.id(), paid.id(), customerKey).status());
        assertRefundOpened(paid.id());
    }


    // ---- helpers ----


    /**
     * Makes the outbox refuse every after-sales event, and only those, until the test ends. The spy sits behind the
     * outbox's transactional proxy (its MANDATORY propagation would refuse a stubbing call outside a transaction).
     */
    private OrderEventOutboxService refuseAfterSalesEvents() {
        OrderEventOutboxService outbox = AopTestUtils.getUltimateTargetObject(orderEventOutboxService);
        Mockito.doThrow(new IllegalStateException(FORCED_FAILURE))
                .when(outbox)
                .enqueueEvent(Mockito.eq(AFTER_SALES_TOPIC), Mockito.anyString(), Mockito.any());
        return outbox;
    }


    /**
     * The order's merchant inbox and timeout module learn of the payment, and its ACCEPT deadline is due.
     */
    private void armAcceptDeadline(Long orderId) {
        merchantInboxService.handle(latestOrderEvent(orderId, "order.paid"));
        orderDeadlineService.handle(latestMerchantEvent(orderId));
        Assertions.assertEquals(1, deadlines(orderId), "the restaurant is staffed, so the order has an ACCEPT deadline");
        jdbcTemplate.update(
                "UPDATE order_deadlines SET due_at = ? WHERE order_id = ?", LocalDateTime.now().minusMinutes(1), orderId);
    }


    private void insertEuroMenuItem(String restaurant, String name, String price) {
        jdbcTemplate.update("""
                INSERT INTO menu_items (restaurant_id, name, price, currency)
                SELECT id, ?, ?, 'EUR'
                FROM restaurants
                WHERE name = ?
                """, name, new BigDecimal(price), restaurant);
    }


    private int deadlines(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_deadlines WHERE order_id = ?", Integer.class, orderId);
    }


    private OrderEventEnvelope latestOrderEvent(Long orderId, String eventType) {
        String payload = jdbcTemplate.queryForObject("""
                SELECT payload
                FROM outbox_events
                WHERE aggregate_type = 'ORDER'
                  AND aggregate_id = ?
                  AND event_type = ?
                ORDER BY id DESC
                LIMIT 1
                """, String.class, orderId, eventType);
        return eventContracts.read(EventTopic.ORDER_EVENTS, payload, OrderEventEnvelope.class);
    }


    private MerchantEventEnvelope latestMerchantEvent(Long orderId) {
        String payload = jdbcTemplate.queryForObject("""
                SELECT payload
                FROM outbox_events
                WHERE aggregate_type = 'MERCHANT_ORDER'
                  AND aggregate_id = ?
                  AND event_type = 'merchant.order_received'
                ORDER BY id DESC
                LIMIT 1
                """, String.class, orderId);
        return eventContracts.read(EventTopic.MERCHANT_EVENTS, payload, MerchantEventEnvelope.class);
    }


    private String cancelledNotice(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT message FROM order_notifications WHERE order_id = ? AND event_type = 'order.cancelled'",
                String.class, orderId);
    }


    private Long customerIdOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
    }


    private String orderStatus(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }


    private String cancelReason(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT cancel_reason FROM orders WHERE id = ?", String.class, orderId);
    }


    private Long orderNo(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT order_no FROM orders WHERE id = ?", Long.class, orderId);
    }
}
