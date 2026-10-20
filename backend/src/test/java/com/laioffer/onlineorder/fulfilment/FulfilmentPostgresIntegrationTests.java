package com.laioffer.onlineorder.fulfilment;


import com.laioffer.onlineorder.PostgresRowLocks;
import com.laioffer.onlineorder.PostgresRowLocks.RowLockHolder;
import com.laioffer.onlineorder.audit.AuditActor;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.fulfilment.dispatch.DeliveryAdminService;
import com.laioffer.onlineorder.fulfilment.dispatch.DeliveryDto;
import com.laioffer.onlineorder.fulfilment.dispatch.DeliveryRepository;
import com.laioffer.onlineorder.fulfilment.dispatch.DispatchService;
import com.laioffer.onlineorder.fulfilment.dispatch.RiderService;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantInboxService;
import com.laioffer.onlineorder.fulfilment.notification.DeliveryNotificationService;
import com.laioffer.onlineorder.fulfilment.order.DeliveryCompletionService;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantOrderService;
import com.laioffer.onlineorder.fulfilment.order.OrderActor;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands;
import com.laioffer.onlineorder.fulfilment.timeout.OrderDeadlineProcessor;
import com.laioffer.onlineorder.fulfilment.timeout.OrderDeadlineService;
import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import com.laioffer.onlineorder.messaging.MerchantEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.CustomerService;
import com.laioffer.onlineorder.service.OrderService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;


import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * The fulfilment modules on real PostgreSQL (row locks, SKIP LOCKED, constraints) with Kafka switched off: the tests
 * feed the consumers' handlers with the events the outbox recorded, exactly as the listeners would.
 */
@SpringBootTest(properties = {
        // Sessions live in the MockMvc session instead of Redis.
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class FulfilmentPostgresIntegrationTests {

    static final String BURGER_KING_MERCHANT = "merchant.burgerking@laifood.com";
    static final String SGD_TOFU_MERCHANT = "merchant.sgdtofu@laifood.com";
    static final String MERCHANT_PASSWORD = "merchant123";
    static final String ALICE_RIDER = "rider.alice@laifood.com";
    static final String BOB_RIDER = "rider.bob@laifood.com";

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
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderLifecycleCommands orderLifecycleCommands;

    @Autowired
    private MerchantInboxService merchantInboxService;

    @Autowired
    private MerchantOrderService merchantOrderService;

    @Autowired
    private OrderDeadlineService orderDeadlineService;

    @Autowired
    private OrderDeadlineProcessor orderDeadlineProcessor;

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private RiderService riderService;

    @Autowired
    private DeliveryAdminService deliveryAdminService;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private OrderService orderService;

    @Autowired
    private DeliveryCompletionService deliveryCompletionService;

    @Autowired
    private DeliveryNotificationService deliveryNotificationService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private PlatformTransactionManager transactionManager;


    @Test
    void merchant_shouldOnlySeeAndActOnOrdersOfTheirOwnRestaurant() throws Exception {
        PaidOrder order = paidOrderAt("Burger King");
        deliverOrderEvent(order.orderId(), "order.paid", merchantInboxService::handle);
        MockHttpSession burgerKing = login(BURGER_KING_MERCHANT, MERCHANT_PASSWORD);
        MockHttpSession sgdTofu = login(SGD_TOFU_MERCHANT, MERCHANT_PASSWORD);

        mockMvc.perform(get("/merchant/orders").param("status", "PAID").session(burgerKing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.order_id == %d)].status".formatted(order.orderId())).value("PAID"));
        mockMvc.perform(get("/merchant/orders").session(sgdTofu))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.order_id == %d)]".formatted(order.orderId())).isEmpty());

        mockMvc.perform(post("/merchant/orders/{id}/accept", order.orderId()).session(sgdTofu))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(post("/merchant/orders/{id}/reject", order.orderId())
                        .session(sgdTofu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"not mine\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/merchant/orders/{id}/preparing", order.orderId()).session(sgdTofu))
                .andExpect(status().isNotFound());
        Assertions.assertEquals("PAID", orderStatus(order.orderId()));
        Assertions.assertEquals(List.of("order.paid"), orderEventTypes(order.orderId()));

        mockMvc.perform(post("/merchant/orders/{id}/accept", order.orderId()).session(burgerKing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.items[0].menu_item_name").exists());
        Assertions.assertEquals("ACCEPTED", orderStatus(order.orderId()));

        // A retried accept is answered with the current state and writes no second event.
        mockMvc.perform(post("/merchant/orders/{id}/accept", order.orderId()).session(burgerKing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        Assertions.assertEquals(List.of("order.paid", "order.accepted"), orderEventTypes(order.orderId()));

        mockMvc.perform(post("/merchant/orders/{id}/reject", order.orderId())
                        .session(burgerKing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"too late\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(OrderLifecycleCommands.ORDER_STATUS_CONFLICT));

        mockMvc.perform(post("/merchant/orders/{id}/preparing", order.orderId()).session(burgerKing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREPARING"));
        mockMvc.perform(get("/merchant/orders").param("status", "PREPARING").session(burgerKing))
                .andExpect(jsonPath("$[?(@.order_id == %d)].status".formatted(order.orderId())).value("PREPARING"));
        Assertions.assertEquals(
                List.of("order.paid", "order.accepted", "order.preparing"),
                orderEventTypes(order.orderId())
        );
    }


    @Test
    void merchantReject_shouldCancelTheOrderWithTheReasonAndRequireOne() throws Exception {
        PaidOrder order = paidOrderAt("Burger King");
        deliverOrderEvent(order.orderId(), "order.paid", merchantInboxService::handle);
        MockHttpSession burgerKing = login(BURGER_KING_MERCHANT, MERCHANT_PASSWORD);

        mockMvc.perform(post("/merchant/orders/{id}/reject", order.orderId())
                        .session(burgerKing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/merchant/orders/{id}/reject", order.orderId())
                        .session(burgerKing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Out of buns\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancel_reason").value("MERCHANT_REJECTED: Out of buns"));

        Assertions.assertEquals("CANCELLED", orderStatus(order.orderId()));
        Assertions.assertEquals("MERCHANT_REJECTED: Out of buns", jdbcTemplate.queryForObject(
                "SELECT cancel_reason FROM orders WHERE id = ?", String.class, order.orderId()));
        Assertions.assertEquals(
                "PAID->CANCELLED|MERCHANT|" + customerIdOf(BURGER_KING_MERCHANT) + "|MERCHANT_REJECTED: Out of buns",
                statusHistory(order.orderId()).get(1)
        );
        OrderEventEnvelope cancelled = latestOrderEvent(order.orderId(), "order.cancelled");
        Assertions.assertEquals("MERCHANT_REJECTED: Out of buns", cancelled.data().reason());
    }


    @Test
    void orderHistoryEndpoint_shouldServeTheOwnerAndAdminsOnly() throws Exception {
        PaidOrder order = paidOrderAt("SGD Tofu House");
        deliverOrderEvent(order.orderId(), "order.paid", merchantInboxService::handle);
        merchantOrderService.reject(customerIdOf(SGD_TOFU_MERCHANT), order.orderId(), "Closed early");
        PaidOrder otherOrder = paidOrderAt("SGD Tofu House");
        MockHttpSession owner = login(order.customerEmail(), "demo123");
        MockHttpSession admin = login("demo@laifood.com", "demo123");
        MockHttpSession merchant = login(SGD_TOFU_MERCHANT, MERCHANT_PASSWORD);
        MockHttpSession otherCustomer = login(otherOrder.customerEmail(), "demo123");

        for (MockHttpSession reader : List.of(owner, admin)) {
            mockMvc.perform(get("/orders/{id}/history", order.orderId()).session(reader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    // Every entry has every key, with null where there is no value.
                    .andExpect(jsonPath("$[0]", Matchers.hasKey("from_status")))
                    .andExpect(jsonPath("$[0].from_status").value(Matchers.nullValue()))
                    .andExpect(jsonPath("$[0].to_status").value("PAID"))
                    .andExpect(jsonPath("$[0].actor_type").value("CUSTOMER"))
                    .andExpect(jsonPath("$[0].actor_id").value(order.customerId()))
                    .andExpect(jsonPath("$[0]", Matchers.hasKey("reason")))
                    .andExpect(jsonPath("$[0].occurred_at").isNotEmpty())
                    .andExpect(jsonPath("$[1].from_status").value("PAID"))
                    .andExpect(jsonPath("$[1].to_status").value("CANCELLED"))
                    .andExpect(jsonPath("$[1].actor_type").value("MERCHANT"))
                    .andExpect(jsonPath("$[1].actor_id").value(customerIdOf(SGD_TOFU_MERCHANT)))
                    .andExpect(jsonPath("$[1].reason").value("MERCHANT_REJECTED: Closed early"));
        }
        // Not the owner and not an admin (the restaurant's merchant included): the order does not exist for them.
        for (MockHttpSession stranger : List.of(otherCustomer, merchant)) {
            mockMvc.perform(get("/orders/{id}/history", order.orderId()).session(stranger))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        mockMvc.perform(get("/orders/{id}/history", 987_654_321_987L).session(admin))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/orders/{id}/history", order.orderId()))
                .andExpect(status().isUnauthorized());
        // The order itself reports when it last changed.
        mockMvc.perform(get("/orders").session(owner))
                .andExpect(jsonPath("$[?(@.id == %d)].updated_at".formatted(order.orderId())).isNotEmpty());
    }


    @Test
    void customersAndAdmins_shouldNotReachMerchantEndpoints() throws Exception {
        PaidOrder order = paidOrderAt("Burger King");
        deliverOrderEvent(order.orderId(), "order.paid", merchantInboxService::handle);
        MockHttpSession customer = login(order.customerEmail(), "demo123");
        MockHttpSession admin = login("demo@laifood.com", "demo123");

        mockMvc.perform(get("/merchant/orders").session(customer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post("/merchant/orders/{id}/accept", order.orderId()).session(customer))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/merchant/orders/{id}/accept", order.orderId()).session(admin))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/merchant/orders"))
                .andExpect(status().isUnauthorized());
        Assertions.assertEquals("PAID", orderStatus(order.orderId()));
    }


    @Test
    void duplicateOrderPaidDeliveries_shouldBuildOneInboxRow() {
        PaidOrder order = paidOrderAt("SGD Tofu House");
        OrderEventEnvelope paid = latestOrderEvent(order.orderId(), "order.paid");

        merchantInboxService.handle(paid);
        merchantInboxService.handle(paid);
        // Even a copy under a new event id (a replay that re-enqueued the event) does not add a second row.
        merchantInboxService.handle(new OrderEventEnvelope(
                paid.eventId() + "-copy", paid.eventVersion(), paid.eventType(), paid.aggregateType(), paid.aggregateId(),
                paid.occurredAt(), paid.correlationId(), paid.idempotencyKey(), paid.data()));

        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM merchant_orders WHERE order_id = ?", Integer.class, order.orderId()));
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE consumer_name = 'merchant-inbox-consumer' AND event_id = ?",
                Integer.class,
                paid.eventId()
        ));
    }


    @Test
    void merchantAcceptRacingTheAcceptTimeout_shouldHaveExactlyOneWinner() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 6; round++) {
                PaidOrder order = paidOrderAt("Burger King");
                deliverOrderEvent(order.orderId(), "order.paid", merchantInboxService::handle);
                CountDownLatch start = new CountDownLatch(1);

                Future<String> accept = executor.submit(awaiting(start, () -> {
                    try {
                        return merchantOrderService.accept(customerIdOf(BURGER_KING_MERCHANT), order.orderId()).status();
                    } catch (ConflictException ex) {
                        return "CONFLICT";
                    }
                }));
                Future<Boolean> timeout = executor.submit(awaiting(start, () -> orderLifecycleCommands.cancelIfStillIn(
                        order.orderId(), OrderStatus.PAID, OrderLifecycleCommands.MERCHANT_TIMEOUT, OrderActor.system()).isPresent()));
                start.countDown();

                String acceptResult = accept.get(20, TimeUnit.SECONDS);
                boolean timedOut = timeout.get(20, TimeUnit.SECONDS);
                List<String> events = orderEventTypes(order.orderId());
                // The history has exactly one entry for the change, by the winner.
                List<String> history = statusHistory(order.orderId());
                Assertions.assertEquals(2, history.size(), history.toString());
                if (timedOut) {
                    Assertions.assertEquals("CONFLICT", acceptResult);
                    Assertions.assertEquals("CANCELLED", orderStatus(order.orderId()));
                    Assertions.assertEquals(List.of("order.paid", "order.cancelled"), events);
                    Assertions.assertEquals(
                            "PAID->CANCELLED|SYSTEM|null|" + OrderLifecycleCommands.MERCHANT_TIMEOUT, history.get(1));
                } else {
                    Assertions.assertEquals("ACCEPTED", acceptResult);
                    Assertions.assertEquals("ACCEPTED", orderStatus(order.orderId()));
                    Assertions.assertEquals(List.of("order.paid", "order.accepted"), events);
                    Assertions.assertEquals(
                            "PAID->ACCEPTED|MERCHANT|" + customerIdOf(BURGER_KING_MERCHANT) + "|null", history.get(1));
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }


    // ---- order commands racing on the order row, and order behaviour a new state machine must keep ----
    // ADR 0002 P1, invariants I2-I12 and I24. Each race queues both commands on the order row behind a lock holder
    // (holdRow): one command first in the even rounds, the other first in the odd rounds. Every run so goes through both
    // serial outcomes, and the second command is shown to wait for the row and decide on what the first committed (I7).


    @Test
    void customerCancelRacingMerchantAccept_shouldEndInOneOfTheTwoSerialOutcomes() throws Exception {
        Long merchantId = customerIdOf(BURGER_KING_MERCHANT);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 6; round++) {
                PaidOrder order = paidOrderAt("Burger King");
                deliverOrderEvent(order.orderId(), "order.paid", merchantInboxService::handle);
                String key = "cancel-" + order.orderId();
                boolean cancelFirst = round % 2 == 0;
                Callable<String> acceptCommand = () -> {
                    try {
                        return merchantOrderService.accept(merchantId, order.orderId()).status();
                    } catch (ConflictException ex) {
                        return conflictOutcome(ex);
                    }
                };
                Callable<OrderDto> cancelCommand = () ->
                        orderService.idempotentCancelOrder(order.customerId(), order.orderId(), key);

                Future<String> accept;
                Future<OrderDto> cancel;
                try (RowLockHolder orderRow = holdRow(executor, "orders", order.orderId())) {
                    if (cancelFirst) {
                        cancel = orderRow.queue(cancelCommand);
                        accept = orderRow.queue(acceptCommand);
                    } else {
                        accept = orderRow.queue(acceptCommand);
                        cancel = orderRow.queue(cancelCommand);
                    }
                    orderRow.release();
                }

                String acceptResult = accept.get(20, TimeUnit.SECONDS);
                // The customer is never refused: a PAID and an ACCEPTED order can both be cancelled.
                OrderDto cancelled = cancel.get(20, TimeUnit.SECONDS);
                Assertions.assertEquals("CANCELLED", cancelled.status());
                Assertions.assertEquals(OrderLifecycleCommands.CUSTOMER_CANCELLED, cancelled.cancelReason());
                Assertions.assertEquals("CANCELLED", orderStatus(order.orderId()));
                Assertions.assertEquals(OrderLifecycleCommands.CUSTOMER_CANCELLED, cancelReason(order.orderId()));
                String paid = "->PAID|CUSTOMER|" + order.customerId() + "|null";
                String customerCancelled = "CANCELLED|CUSTOMER|" + order.customerId() + "|"
                        + OrderLifecycleCommands.CUSTOMER_CANCELLED;
                if (cancelFirst) {
                    // The accept found the order cancelled.
                    Assertions.assertEquals(
                            "CONFLICT|" + OrderLifecycleCommands.ORDER_STATUS_CONFLICT
                                    + "|Order is CANCELLED (CUSTOMER_CANCELLED); only PAID orders can move to ACCEPTED",
                            acceptResult
                    );
                    Assertions.assertEquals(List.of("order.paid", "order.cancelled"), orderEventTypes(order.orderId()));
                    Assertions.assertEquals(List.of(paid, "PAID->" + customerCancelled), statusHistory(order.orderId()));
                    // The refused accept threw before its inbox update; the inbox learns of the cancel from its event.
                    Assertions.assertEquals("PAID", inboxStatus(order.orderId()));
                } else {
                    Assertions.assertEquals("ACCEPTED", acceptResult);
                    Assertions.assertEquals(
                            List.of("order.paid", "order.accepted", "order.cancelled"),
                            orderEventTypes(order.orderId())
                    );
                    Assertions.assertEquals(List.of(
                            paid,
                            "PAID->ACCEPTED|MERCHANT|" + merchantId + "|null",
                            "ACCEPTED->" + customerCancelled
                    ), statusHistory(order.orderId()));
                    Assertions.assertEquals("ACCEPTED", inboxStatus(order.orderId()));
                }
                Assertions.assertEquals(
                        List.of("order-cancel:v1:" + order.orderId() + "|" + key + "|SUCCEEDED|" + order.orderId()),
                        orderIdempotencyRequests(order.orderId())
                );
                Assertions.assertEquals(List.of(), orderStatusAudits(order.orderId()), "a customer cancel is not audited");
            }
        } finally {
            executor.shutdownNow();
        }
    }


    @Test
    void adminPatchPaidRacingThePaymentTimeout_shouldHaveExactlyOneWinner() throws Exception {
        Long adminId = customerIdOf("demo@laifood.com");
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 6; round++) {
                PaidOrder order = placedOrderAt("SGD Tofu House");
                deliverOrderEvent(order.orderId(), "order.created", orderDeadlineService::handle);
                Assertions.assertEquals(List.of("PAYMENT"), deadlineTypes(order.orderId()));
                makeDeadlinesDue(order.orderId());
                String key = "mark-paid-" + order.orderId();
                boolean timeoutFirst = round % 2 == 0;
                Callable<String> patchCommand = () -> {
                    try {
                        return orderService.idempotentTransitionOrderStatus(adminId, order.orderId(), "PAID", key).status();
                    } catch (ConflictException ex) {
                        return conflictOutcome(ex);
                    }
                };
                // The poller fires every due deadline in the database, up to its batch size, not only this order's. No
                // test leaves a deadline due; with a full batch of others the poller would never queue for this row.
                Callable<Integer> pollerCommand = orderDeadlineProcessor::fireDueDeadlines;

                Future<String> patch;
                Future<Integer> poller;
                try (RowLockHolder orderRow = holdRow(executor, "orders", order.orderId())) {
                    if (timeoutFirst) {
                        poller = orderRow.queue(pollerCommand);
                        patch = orderRow.queue(patchCommand);
                    } else {
                        patch = orderRow.queue(patchCommand);
                        poller = orderRow.queue(pollerCommand);
                    }
                    orderRow.release();
                }

                String patchResult = patch.get(20, TimeUnit.SECONDS);
                Assertions.assertTrue(poller.get(20, TimeUnit.SECONDS) >= 1, "the poller handled the due deadline");
                // Either way the deadline is gone: fired, or dropped because the order was no longer PLACED.
                Assertions.assertTrue(deadlineTypes(order.orderId()).isEmpty());
                List<String> history = statusHistory(order.orderId());
                Assertions.assertEquals(2, history.size(), history.toString());
                Assertions.assertEquals("->PLACED|CUSTOMER|" + order.customerId() + "|null", history.get(0));
                if (timeoutFirst) {
                    // The PATCH found the order cancelled by the timeout.
                    Assertions.assertEquals("CONFLICT|CONFLICT|Invalid order status transition: CANCELLED -> PAID", patchResult);
                    Assertions.assertEquals("CANCELLED", orderStatus(order.orderId()));
                    Assertions.assertEquals(OrderLifecycleCommands.PAYMENT_TIMEOUT, cancelReason(order.orderId()));
                    Assertions.assertEquals(List.of("order.created", "order.cancelled"), orderEventTypes(order.orderId()));
                    Assertions.assertEquals(
                            "PLACED->CANCELLED|SYSTEM|null|" + OrderLifecycleCommands.PAYMENT_TIMEOUT, history.get(1));
                    // The refused PATCH rolled back whole: no audit row, and no idempotency row for its key.
                    Assertions.assertEquals(List.of(), orderStatusAudits(order.orderId()));
                    Assertions.assertEquals(List.of(), orderIdempotencyRequests(order.orderId()));
                } else {
                    // The poller found the order paid and dropped the deadline without cancelling it.
                    Assertions.assertEquals("PAID", patchResult);
                    Assertions.assertEquals("PAID", orderStatus(order.orderId()));
                    Assertions.assertNull(cancelReason(order.orderId()));
                    Assertions.assertEquals(List.of("order.created", "order.paid"), orderEventTypes(order.orderId()));
                    Assertions.assertEquals("PLACED->PAID|ADMIN|" + adminId + "|null", history.get(1));
                    Assertions.assertEquals(List.of(adminId + "|PLACED->PAID|true|" + key), orderStatusAudits(order.orderId()));
                    Assertions.assertEquals(
                            List.of("order-status:v1:" + order.orderId() + "|" + key + "|SUCCEEDED|" + order.orderId()),
                            orderIdempotencyRequests(order.orderId())
                    );
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }


    /**
     * The delivery.delivered consumer and an admin PATCH to COMPLETED, with the order in ACCEPTED (the merchant never
     * marked it PREPARING) or in PREPARING when the rider delivers.
     */
    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"ACCEPTED", "PREPARING"})
    void deliveryCompletionRacingAdminPatchCompleted_shouldCompleteOnce(OrderStatus statusWhenDelivered) throws Exception {
        Long merchantId = customerIdOf(BURGER_KING_MERCHANT);
        Long adminId = customerIdOf("demo@laifood.com");
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 6; round++) {
                PaidOrder order = paidOrderAt("Burger King");
                deliverOrderEvent(order.orderId(), "order.paid", merchantInboxService::handle);
                merchantOrderService.accept(merchantId, order.orderId());
                deliverOrderEvent(order.orderId(), "order.accepted", dispatchService::handle);
                if (statusWhenDelivered == OrderStatus.PREPARING) {
                    merchantOrderService.markPreparing(merchantId, order.orderId());
                }
                Long deliveryId = jdbcTemplate.queryForObject(
                        "SELECT id FROM deliveries WHERE order_id = ?", Long.class, order.orderId());
                dispatchService.markPickedUp(deliveryId);
                dispatchService.markDelivered(deliveryId);
                DeliveryEventEnvelope delivered = latestDeliveryEvent(deliveryId, "delivery.delivered");
                Long riderId = delivered.data().riderId();
                String key = "complete-" + order.orderId();
                boolean completionFirst = round % 2 == 0;
                Callable<Object> completionCommand = () -> {
                    deliveryCompletionService.handle(delivered);
                    return null;
                };
                Callable<String> patchCommand = () -> {
                    try {
                        return orderService.idempotentTransitionOrderStatus(adminId, order.orderId(), "COMPLETED", key)
                                .status();
                    } catch (ConflictException ex) {
                        return conflictOutcome(ex);
                    }
                };

                Future<Object> completion;
                Future<String> patch;
                try (RowLockHolder orderRow = holdRow(executor, "orders", order.orderId())) {
                    if (completionFirst) {
                        completion = orderRow.queue(completionCommand);
                        patch = orderRow.queue(patchCommand);
                    } else {
                        patch = orderRow.queue(patchCommand);
                        completion = orderRow.queue(completionCommand);
                    }
                    orderRow.release();
                }

                completion.get(20, TimeUnit.SECONDS);
                String patchResult = patch.get(20, TimeUnit.SECONDS);
                // Completed exactly once, whoever got there first.
                Assertions.assertEquals("COMPLETED", orderStatus(order.orderId()));
                List<String> events = orderEventTypes(order.orderId());
                Assertions.assertEquals(List.of("order.paid", "order.accepted", "order.preparing", "order.completed"), events);
                Assertions.assertEquals(1, Collections.frequency(events, "order.completed"));
                List<String> history = statusHistory(order.orderId());
                Assertions.assertEquals(
                        1L, history.stream().filter(row -> row.contains("->COMPLETED|")).count(), history.toString());
                assertUpdatedAtIsTheLastStatusChange(order.orderId());
                String paid = "->PAID|CUSTOMER|" + order.customerId() + "|null";
                String accepted = "PAID->ACCEPTED|MERCHANT|" + merchantId + "|null";
                String riderCompleted = "PREPARING->COMPLETED|RIDER|" + riderId + "|DELIVERED";
                String noOpAudit = adminId + "|COMPLETED->COMPLETED|false|" + key;
                String statusRequest = "order-status:v1:" + order.orderId() + "|" + key + "|SUCCEEDED|" + order.orderId();
                if (statusWhenDelivered == OrderStatus.ACCEPTED) {
                    // An admin cannot skip PREPARING, so only the completion moves the order, through PREPARING.
                    Assertions.assertEquals(List.of(
                            paid,
                            accepted,
                            "ACCEPTED->PREPARING|RIDER|" + riderId + "|DELIVERED",
                            riderCompleted
                    ), history);
                    if (!completionFirst) {
                        // The PATCH found the order ACCEPTED and was refused; the completion then moved it.
                        Assertions.assertEquals(
                                "CONFLICT|CONFLICT|Invalid order status transition: ACCEPTED -> COMPLETED", patchResult);
                        Assertions.assertEquals(List.of(), orderStatusAudits(order.orderId()));
                        Assertions.assertEquals(List.of(), orderIdempotencyRequests(order.orderId()));
                    } else {
                        // The PATCH found the order COMPLETED: an audited no-op.
                        Assertions.assertEquals("COMPLETED", patchResult);
                        Assertions.assertEquals(List.of(noOpAudit), orderStatusAudits(order.orderId()));
                        Assertions.assertEquals(List.of(statusRequest), orderIdempotencyRequests(order.orderId()));
                    }
                } else {
                    // From PREPARING both may complete the order, so the PATCH is never refused.
                    Assertions.assertEquals("COMPLETED", patchResult);
                    String prepared = "ACCEPTED->PREPARING|MERCHANT|" + merchantId + "|null";
                    String adminCompleted = "PREPARING->COMPLETED|ADMIN|" + adminId + "|null";
                    if (!completionFirst) {
                        // The completion then found the order COMPLETED (ALREADY_COMPLETED).
                        Assertions.assertEquals(List.of(paid, accepted, prepared, adminCompleted), history);
                        Assertions.assertEquals(
                                List.of(adminId + "|PREPARING->COMPLETED|true|" + key), orderStatusAudits(order.orderId()));
                    } else {
                        Assertions.assertEquals(List.of(paid, accepted, prepared, riderCompleted), history);
                        Assertions.assertEquals(List.of(noOpAudit), orderStatusAudits(order.orderId()));
                    }
                    Assertions.assertEquals(List.of(statusRequest), orderIdempotencyRequests(order.orderId()));
                }
            }
        } finally {
            executor.shutdownNow();
        }
        assertEveryRiderLoadMatchesTheirOpenDeliveries();
    }


    @Test
    void customerCancelRacingTheAcceptTimeout_shouldKeepTheWinnersReason() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 6; round++) {
                PaidOrder order = paidOrderAt("Burger King");
                deliverOrderEvent(order.orderId(), "order.paid", merchantInboxService::handle);
                deliverMerchantOrderReceived(order.orderId());
                Assertions.assertEquals(List.of("ACCEPT"), deadlineTypes(order.orderId()));
                makeDeadlinesDue(order.orderId());
                String key = "cancel-" + order.orderId();
                boolean customerFirst = round % 2 == 0;
                Callable<OrderDto> cancelCommand = () ->
                        orderService.idempotentCancelOrder(order.customerId(), order.orderId(), key);
                // As in the race above, the poller fires every due deadline in the database, not only this order's.
                Callable<Integer> pollerCommand = orderDeadlineProcessor::fireDueDeadlines;

                Future<OrderDto> cancel;
                Future<Integer> poller;
                try (RowLockHolder orderRow = holdRow(executor, "orders", order.orderId())) {
                    if (customerFirst) {
                        cancel = orderRow.queue(cancelCommand);
                        poller = orderRow.queue(pollerCommand);
                    } else {
                        poller = orderRow.queue(pollerCommand);
                        cancel = orderRow.queue(cancelCommand);
                    }
                    orderRow.release();
                }

                // The customer is never refused: an order the timeout cancelled first is returned as it is.
                OrderDto cancelled = cancel.get(20, TimeUnit.SECONDS);
                Assertions.assertTrue(poller.get(20, TimeUnit.SECONDS) >= 1, "the poller handled the due deadline");
                Assertions.assertTrue(deadlineTypes(order.orderId()).isEmpty());
                Assertions.assertEquals("CANCELLED", orderStatus(order.orderId()));
                String reason = cancelReason(order.orderId());
                List<String> history = statusHistory(order.orderId());
                Assertions.assertEquals(2, history.size(), history.toString());
                if (!customerFirst) {
                    // The timeout cancelled the order; the customer's cancel was a no-op that kept its reason.
                    Assertions.assertEquals(OrderLifecycleCommands.MERCHANT_TIMEOUT, reason);
                    Assertions.assertEquals(
                            "PAID->CANCELLED|SYSTEM|null|" + OrderLifecycleCommands.MERCHANT_TIMEOUT, history.get(1));
                } else {
                    // The poller found the order cancelled and dropped the deadline.
                    Assertions.assertEquals(OrderLifecycleCommands.CUSTOMER_CANCELLED, reason);
                    Assertions.assertEquals(
                            "PAID->CANCELLED|CUSTOMER|" + order.customerId() + "|" + OrderLifecycleCommands.CUSTOMER_CANCELLED,
                            history.get(1)
                    );
                }
                // One cancel, and the winner's reason everywhere: the order, its event and the customer's answer.
                Assertions.assertEquals("CANCELLED", cancelled.status());
                Assertions.assertEquals(reason, cancelled.cancelReason());
                Assertions.assertEquals(List.of("order.paid", "order.cancelled"), orderEventTypes(order.orderId()));
                Assertions.assertEquals(reason, latestOrderEvent(order.orderId(), "order.cancelled").data().reason());
                Assertions.assertEquals(
                        List.of("order-cancel:v1:" + order.orderId() + "|" + key + "|SUCCEEDED|" + order.orderId()),
                        orderIdempotencyRequests(order.orderId())
                );
            }
        } finally {
            executor.shutdownNow();
        }
    }


    @Test
    void twoAdminsPatchingTheSameTargetWithDifferentKeys_shouldAuditOneChange() throws Exception {
        Long firstAdmin = customerIdOf("demo@laifood.com");
        Long secondAdmin = newAdmin();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 6; round++) {
                PaidOrder order = paidOrderAt("Fashion Wok");
                String firstKey = "first-admin-" + order.orderId();
                String secondKey = "second-admin-" + order.orderId();
                CountDownLatch start = new CountDownLatch(1);

                Future<String> first = executor.submit(awaiting(start, () -> orderService.idempotentTransitionOrderStatus(
                        firstAdmin, order.orderId(), "ACCEPTED", firstKey).status()));
                Future<String> second = executor.submit(awaiting(start, () -> orderService.idempotentTransitionOrderStatus(
                        secondAdmin, order.orderId(), "ACCEPTED", secondKey).status()));
                start.countDown();

                // Both are answered with the accepted order; only the first to lock the row changed it.
                Assertions.assertEquals("ACCEPTED", first.get(20, TimeUnit.SECONDS));
                Assertions.assertEquals("ACCEPTED", second.get(20, TimeUnit.SECONDS));
                Assertions.assertEquals("ACCEPTED", orderStatus(order.orderId()));
                Assertions.assertEquals(List.of("order.paid", "order.accepted"), orderEventTypes(order.orderId()));
                List<String> history = statusHistory(order.orderId());
                Assertions.assertEquals(2, history.size(), history.toString());
                boolean firstChangedIt = history.get(1).equals("PAID->ACCEPTED|ADMIN|" + firstAdmin + "|null");
                if (!firstChangedIt) {
                    Assertions.assertEquals("PAID->ACCEPTED|ADMIN|" + secondAdmin + "|null", history.get(1));
                }
                String change = "|PAID->ACCEPTED|true|";
                String noOp = "|ACCEPTED->ACCEPTED|false|";
                List<String> audits = orderStatusAudits(order.orderId());
                Assertions.assertEquals(2, audits.size(), audits.toString());
                Assertions.assertEquals(Set.of(
                        firstAdmin + (firstChangedIt ? change : noOp) + firstKey,
                        secondAdmin + (firstChangedIt ? noOp : change) + secondKey
                ), Set.copyOf(audits));
                Assertions.assertEquals(Set.of(
                        "order-status:v1:" + order.orderId() + "|" + firstKey + "|SUCCEEDED|" + order.orderId(),
                        "order-status:v1:" + order.orderId() + "|" + secondKey + "|SUCCEEDED|" + order.orderId()
                ), Set.copyOf(orderIdempotencyRequests(order.orderId())));
            }
        } finally {
            executor.shutdownNow();
        }
    }


    /**
     * The merchant commands through the merchant module record a MERCHANT actor (not SYSTEM), so a state machine that
     * checks actors has to allow the merchant on accept, reject and preparing (ADR 0002 section 4.1).
     */
    @Test
    void merchantCommands_shouldRecordTheMerchantAsTheActorOfAcceptRejectAndPreparing() {
        Long merchantId = customerIdOf(BURGER_KING_MERCHANT);
        PaidOrder prepared = paidOrderAt("Burger King");
        PaidOrder rejected = paidOrderAt("Burger King");
        deliverOrderEvent(prepared.orderId(), "order.paid", merchantInboxService::handle);
        deliverOrderEvent(rejected.orderId(), "order.paid", merchantInboxService::handle);

        Assertions.assertEquals("ACCEPTED", merchantOrderService.accept(merchantId, prepared.orderId()).status());
        Assertions.assertEquals("PREPARING", merchantOrderService.markPreparing(merchantId, prepared.orderId()).status());
        Assertions.assertEquals("CANCELLED", merchantOrderService.reject(merchantId, rejected.orderId(), "Out of buns").status());

        Assertions.assertEquals(List.of(
                "->PAID|CUSTOMER|" + prepared.customerId() + "|null",
                "PAID->ACCEPTED|MERCHANT|" + merchantId + "|null",
                "ACCEPTED->PREPARING|MERCHANT|" + merchantId + "|null"
        ), statusHistory(prepared.orderId()));
        Assertions.assertEquals(
                List.of("order.paid", "order.accepted", "order.preparing"), orderEventTypes(prepared.orderId()));
        Assertions.assertEquals(List.of(
                "->PAID|CUSTOMER|" + rejected.customerId() + "|null",
                "PAID->CANCELLED|MERCHANT|" + merchantId + "|MERCHANT_REJECTED: Out of buns"
        ), statusHistory(rejected.orderId()));
        Assertions.assertEquals(List.of("order.paid", "order.cancelled"), orderEventTypes(rejected.orderId()));
    }


    /**
     * A known gap (ADR 0002 I24): an admin PATCH to COMPLETED does not close the order's delivery, which stays with its
     * rider until the rider finishes it.
     */
    @Test
    void adminPatchCompletedFromPreparing_shouldLeaveTheDeliveryOpen() {
        Long merchantId = customerIdOf(BURGER_KING_MERCHANT);
        Long adminId = customerIdOf("demo@laifood.com");
        PaidOrder order = paidOrderAt("Burger King");
        deliverOrderEvent(order.orderId(), "order.paid", merchantInboxService::handle);
        merchantOrderService.accept(merchantId, order.orderId());
        int totalLoadBefore = totalRiderLoad();
        deliverOrderEvent(order.orderId(), "order.accepted", dispatchService::handle);
        merchantOrderService.markPreparing(merchantId, order.orderId());
        Long deliveryId = jdbcTemplate.queryForObject(
                "SELECT id FROM deliveries WHERE order_id = ?", Long.class, order.orderId());

        OrderDto completed = orderService.idempotentTransitionOrderStatus(
                adminId, order.orderId(), "COMPLETED", "complete-" + order.orderId());
        // The dispatch module follows order.accepted and order.cancelled only.
        deliverOrderEvent(order.orderId(), "order.completed", dispatchService::handle);

        Assertions.assertEquals("COMPLETED", completed.status());
        Assertions.assertEquals("ASSIGNED", jdbcTemplate.queryForObject(
                "SELECT status FROM deliveries WHERE id = ?", String.class, deliveryId));
        Assertions.assertEquals(totalLoadBefore + 1, totalRiderLoad(), "the rider still carries the delivery");
        Assertions.assertEquals(List.of("delivery.assigned"), deliveryEventTypes(deliveryId));

        // The rider can still finish it; the completion then finds the order COMPLETED and changes nothing.
        dispatchService.markPickedUp(deliveryId);
        dispatchService.markDelivered(deliveryId);
        deliveryCompletionService.handle(latestDeliveryEvent(deliveryId, "delivery.delivered"));

        Assertions.assertEquals(totalLoadBefore, totalRiderLoad());
        Assertions.assertEquals(
                List.of("order.paid", "order.accepted", "order.preparing", "order.completed"),
                orderEventTypes(order.orderId())
        );
        Assertions.assertEquals(List.of(
                "->PAID|CUSTOMER|" + order.customerId() + "|null",
                "PAID->ACCEPTED|MERCHANT|" + merchantId + "|null",
                "ACCEPTED->PREPARING|MERCHANT|" + merchantId + "|null",
                "PREPARING->COMPLETED|ADMIN|" + adminId + "|null"
        ), statusHistory(order.orderId()));
        assertEveryRiderLoadMatchesTheirOpenDeliveries();
    }


    @Test
    void eventSequences_shouldGrowByOnePerAppliedTransition() {
        Long merchantId = customerIdOf(BURGER_KING_MERCHANT);
        Long adminId = customerIdOf("demo@laifood.com");
        PaidOrder order = paidOrderAt("Burger King");
        Long orderId = order.orderId();
        deliverOrderEvent(orderId, "order.paid", merchantInboxService::handle);
        Assertions.assertEquals(List.of(1L), aggregateSequences("ORDER", orderId));

        merchantOrderService.accept(merchantId, orderId);
        // Commands that change nothing or are refused take no number: a repeated accept, an admin PATCH to the
        // current status, a reject of an accepted order and an illegal admin PATCH.
        merchantOrderService.accept(merchantId, orderId);
        orderService.idempotentTransitionOrderStatus(adminId, orderId, "ACCEPTED", "same-status-" + orderId);
        Assertions.assertThrows(ConflictException.class, () -> merchantOrderService.reject(merchantId, orderId, "Too late"));
        Assertions.assertThrows(ConflictException.class,
                () -> orderService.idempotentTransitionOrderStatus(adminId, orderId, "PAID", "backwards-" + orderId));
        Assertions.assertEquals(List.of(1L, 2L), aggregateSequences("ORDER", orderId));
        Assertions.assertEquals(2L, lastAggregateSequence("ORDER", orderId));

        deliverOrderEvent(orderId, "order.accepted", dispatchService::handle);
        Long deliveryId = jdbcTemplate.queryForObject("SELECT id FROM deliveries WHERE order_id = ?", Long.class, orderId);
        dispatchService.markPickedUp(deliveryId);
        dispatchService.markDelivered(deliveryId);
        DeliveryEventEnvelope delivered = latestDeliveryEvent(deliveryId, "delivery.delivered");
        // The two completion steps take a number each; a redelivery and a direct repeat take none.
        deliveryCompletionService.handle(delivered);
        deliveryCompletionService.handle(delivered);
        Assertions.assertEquals(
                OrderLifecycleCommands.DeliveryCompletion.ALREADY_COMPLETED,
                orderLifecycleCommands.completeDeliveredOrder(orderId, OrderActor.rider(delivered.data().riderId()))
        );
        Assertions.assertThrows(ConflictException.class, () -> orderService.cancelOrder(order.customerId(), orderId));

        Assertions.assertEquals(
                List.of("order.paid", "order.accepted", "order.preparing", "order.completed"),
                orderEventTypes(orderId)
        );
        Assertions.assertEquals(List.of(1L, 2L, 3L, 4L), aggregateSequences("ORDER", orderId));
        Assertions.assertEquals(4L, lastAggregateSequence("ORDER", orderId));
        Assertions.assertEquals(4L, latestOrderEvent(orderId, "order.completed").sequence(), "the envelope carries it");
        Assertions.assertEquals(4, statusHistory(orderId).size(), "one history row per numbered event");
        // The delivery is an aggregate of its own, numbered from 1.
        Assertions.assertEquals(List.of(1L, 2L, 3L), aggregateSequences("DELIVERY", deliveryId));
        Assertions.assertEquals(3L, lastAggregateSequence("DELIVERY", deliveryId));
    }


    /**
     * ADR 0002 I2 on the stored rows: every applied transition, whichever command makes it, writes one history row and
     * one event of its target status at the same instant, keyed by the order id, and leaves that instant in the order's
     * updated_at.
     */
    @Test
    void appliedOrderTransitions_shouldWriteTheHistoryRowAndTheEventAtTheOrdersUpdateTime() {
        Long merchantId = customerIdOf(BURGER_KING_MERCHANT);
        Long adminId = customerIdOf("demo@laifood.com");

        // The merchant accepts, then the rider's delivery completes the order through PREPARING (two steps, one
        // transaction).
        PaidOrder delivered = paidOrderAt("Burger King");
        deliverOrderEvent(delivered.orderId(), "order.paid", merchantInboxService::handle);
        merchantOrderService.accept(merchantId, delivered.orderId());
        assertEveryOrderEventMatchesItsHistoryRow(delivered.orderId());
        deliverOrderEvent(delivered.orderId(), "order.accepted", dispatchService::handle);
        Long deliveryId = jdbcTemplate.queryForObject(
                "SELECT id FROM deliveries WHERE order_id = ?", Long.class, delivered.orderId());
        dispatchService.markPickedUp(deliveryId);
        dispatchService.markDelivered(deliveryId);
        deliveryCompletionService.handle(latestDeliveryEvent(deliveryId, "delivery.delivered"));
        Assertions.assertEquals("COMPLETED", orderStatus(delivered.orderId()));
        assertEveryOrderEventMatchesItsHistoryRow(delivered.orderId());

        // A merchant reject, two admin PATCHes, a customer cancel and the payment timeout.
        PaidOrder rejected = paidOrderAt("Burger King");
        deliverOrderEvent(rejected.orderId(), "order.paid", merchantInboxService::handle);
        merchantOrderService.reject(merchantId, rejected.orderId(), "Out of buns");
        PaidOrder patched = paidOrderAt("Fashion Wok");
        orderService.idempotentTransitionOrderStatus(adminId, patched.orderId(), "ACCEPTED", "accept-" + patched.orderId());
        orderService.idempotentTransitionOrderStatus(adminId, patched.orderId(), "PREPARING", "prepare-" + patched.orderId());
        PaidOrder cancelled = paidOrderAt("SGD Tofu House");
        orderService.idempotentCancelOrder(cancelled.customerId(), cancelled.orderId(), "cancel-" + cancelled.orderId());
        PaidOrder timedOut = placedOrderAt("SGD Tofu House");
        deliverOrderEvent(timedOut.orderId(), "order.created", orderDeadlineService::handle);
        makeDeadlinesDue(timedOut.orderId());
        orderDeadlineProcessor.fireDueDeadlines();
        Assertions.assertEquals("CANCELLED", orderStatus(timedOut.orderId()));

        for (PaidOrder order : List.of(rejected, patched, cancelled, timedOut)) {
            assertEveryOrderEventMatchesItsHistoryRow(order.orderId());
        }
    }


    // ---- delivery commands racing on the delivery row ----
    // ADR 0002 P1, invariants I7 and I17-I24. As in the order races above, both commands queue on the delivery row
    // behind a lock holder, each side first in every other round; the rider loads and the delivery's timestamps (I19)
    // are checked after every round.


    /**
     * ADR 0002 I19 on the stored row: a status change sets its own timestamp and updated_at to the time of the change
     * and keeps the others; a reassignment only moves updated_at; a repeated move or a late cancellation writes nothing;
     * assigned_at never moves.
     */
    @Test
    void deliveryTimestamps_shouldSetTheTargetStatusTimeAndKeepTheOthers() {
        Long aliceId = riderIdOf(ALICE_RIDER);
        Long bobId = riderIdOf(BOB_RIDER);

        // Assigned, reassigned, picked up, reassigned again, delivered.
        PaidOrder delivered = paidOrderAt("Burger King");
        Long deliveryId = dispatchTo(delivered, aliceId);
        DeliveryTimes assigned = deliveryTimes(deliveryId);
        Assertions.assertNotNull(assigned.assignedAt());
        Assertions.assertEquals(new DeliveryTimes(assigned.assignedAt(), null, null, null, assigned.updatedAt()), assigned);
        Assertions.assertFalse(assigned.updatedAt().isBefore(assigned.assignedAt()));

        dispatchService.reassign(deliveryId, bobId);
        DeliveryTimes reassigned = deliveryTimes(deliveryId);
        Assertions.assertEquals(assigned.touched(reassigned.updatedAt()), reassigned);

        dispatchService.markPickedUp(deliveryId);
        DeliveryTimes pickedUp = deliveryTimes(deliveryId);
        Assertions.assertEquals(reassigned.pickedUp(pickedUp.updatedAt()), pickedUp);
        // A repeated pick-up writes nothing, not even updated_at (I17).
        dispatchService.markPickedUp(deliveryId);
        Assertions.assertEquals(pickedUp, deliveryTimes(deliveryId));

        dispatchService.reassign(deliveryId, aliceId);
        DeliveryTimes reassignedAgain = deliveryTimes(deliveryId);
        Assertions.assertEquals(pickedUp.touched(reassignedAgain.updatedAt()), reassignedAgain);

        dispatchService.markDelivered(deliveryId);
        DeliveryTimes done = deliveryTimes(deliveryId);
        Assertions.assertEquals(reassignedAgain.delivered(done.updatedAt()), done);
        dispatchService.markDelivered(deliveryId);
        Assertions.assertEquals(done, deliveryTimes(deliveryId), "a repeated delivery writes nothing");
        // The order is still ACCEPTED (its completion was not handled here), so the customer can cancel it; the
        // cancellation leaves the delivered delivery alone (I23).
        orderService.cancelOrder(delivered.customerId(), delivered.orderId());
        deliverOrderEvent(delivered.orderId(), "order.cancelled", dispatchService::handle);
        Assertions.assertEquals(done, deliveryTimes(deliveryId));
        assertUpdatedAtOnlyMovesForward(List.of(assigned, reassigned, pickedUp, reassignedAgain, done));

        // Cancelled after the pick-up: picked_up_at stays and delivered_at stays empty.
        PaidOrder cancelledAfterPickUp = paidOrderAt("Burger King");
        Long pickedUpId = dispatchTo(cancelledAfterPickUp, aliceId);
        dispatchService.markPickedUp(pickedUpId);
        DeliveryTimes beforeCancel = deliveryTimes(pickedUpId);
        Assertions.assertNotNull(beforeCancel.pickedUpAt());
        orderService.cancelOrder(cancelledAfterPickUp.customerId(), cancelledAfterPickUp.orderId());
        deliverOrderEvent(cancelledAfterPickUp.orderId(), "order.cancelled", dispatchService::handle);
        DeliveryTimes cancelledAfterThePickUp = deliveryTimes(pickedUpId);
        Assertions.assertEquals(beforeCancel.cancelled(cancelledAfterThePickUp.updatedAt()), cancelledAfterThePickUp);
        assertUpdatedAtOnlyMovesForward(List.of(beforeCancel, cancelledAfterThePickUp));

        // Cancelled before the pick-up: only cancelled_at is set.
        PaidOrder cancelledAssigned = paidOrderAt("Burger King");
        Long assignedId = dispatchTo(cancelledAssigned, aliceId);
        DeliveryTimes stillAssigned = deliveryTimes(assignedId);
        orderService.cancelOrder(cancelledAssigned.customerId(), cancelledAssigned.orderId());
        deliverOrderEvent(cancelledAssigned.orderId(), "order.cancelled", dispatchService::handle);
        DeliveryTimes cancelledBeforeThePickUp = deliveryTimes(assignedId);
        Assertions.assertEquals(stillAssigned.cancelled(cancelledBeforeThePickUp.updatedAt()), cancelledBeforeThePickUp);
        Assertions.assertNull(cancelledBeforeThePickUp.pickedUpAt());
        assertUpdatedAtOnlyMovesForward(List.of(stillAssigned, cancelledBeforeThePickUp));
        assertEveryRiderLoadMatchesTheirOpenDeliveries();
    }


    /**
     * ADR 0002 section 4.2 on the stored row: the compare-and-set writes change a delivery only while it still has the
     * status and rider the caller read under the lock. A write from a stale read changes nothing and returns 0, which
     * DispatchService turns into a rolled-back StaleTransitionException.
     */
    @Test
    void deliveryCompareAndSet_shouldOnlyChangeARowThatStillHasTheExpectedStatusAndRider() {
        Long aliceId = riderIdOf(ALICE_RIDER);
        Long bobId = riderIdOf(BOB_RIDER);
        Long deliveryId = dispatchTo(paidOrderAt("Burger King"), aliceId);
        DeliveryTimes assigned = deliveryTimes(deliveryId);
        LocalDateTime now = LocalDateTime.now();

        Assertions.assertEquals(0, deliveryRepository.compareAndSetStatus(
                deliveryId, "PICKED_UP", aliceId, "DELIVERED", assigned.assignedAt(), now, null, now),
                "another status");
        Assertions.assertEquals(0, deliveryRepository.compareAndSetStatus(
                deliveryId, "ASSIGNED", bobId, "PICKED_UP", now, null, null, now),
                "another rider");
        Assertions.assertEquals(0, deliveryRepository.compareAndSetRider(deliveryId, bobId, aliceId, now),
                "another rider");
        Assertions.assertEquals("ASSIGNED|" + aliceId, deliveryState(deliveryId));
        Assertions.assertEquals(assigned, deliveryTimes(deliveryId));

        // Through the service each write changes exactly one row (it throws otherwise).
        dispatchService.markPickedUp(deliveryId);
        dispatchService.markDelivered(deliveryId);
        DeliveryTimes delivered = deliveryTimes(deliveryId);

        Assertions.assertEquals(0, deliveryRepository.compareAndSetRider(deliveryId, aliceId, bobId, now),
                "a finished delivery keeps its rider");
        Assertions.assertEquals("DELIVERED|" + aliceId, deliveryState(deliveryId));
        Assertions.assertEquals(delivered, deliveryTimes(deliveryId));
        assertEveryRiderLoadMatchesTheirOpenDeliveries();
    }


    /**
     * The rider delivering a picked-up delivery while an admin reassigns it to another rider. Both lock the delivery row
     * before they change anything: either the rider delivered it and the reassignment is refused, or it moved to the
     * other rider and the first rider no longer reaches it (404). The first rider is released exactly once either way.
     */
    @Test
    void riderDeliveredRacingAdminReassign_shouldKeepRiderLoadsConsistent() throws Exception {
        Long adminId = customerIdOf("demo@laifood.com");
        Long aliceLogin = customerIdOf(ALICE_RIDER);
        Long aliceId = riderIdOf(ALICE_RIDER);
        Long bobLogin = customerIdOf(BOB_RIDER);
        Long bobId = riderIdOf(BOB_RIDER);
        // Another open delivery keeps Alice's load above one during the race, so a second release would show (a load
        // never drops below zero).
        dispatchTo(paidOrderAt("Burger King"), aliceId);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 6; round++) {
                PaidOrder order = paidOrderAt("Burger King");
                Long deliveryId = dispatchTo(order, aliceId);
                dispatchService.markPickedUp(deliveryId);
                List<String> eventsBefore = deliveryEventTypes(deliveryId);
                DeliveryTimes timesBefore = deliveryTimes(deliveryId);
                int aliceLoadBefore = riderLoad(aliceId);
                int bobLoadBefore = riderLoad(bobId);
                int totalLoadBefore = totalRiderLoad();
                Assertions.assertTrue(aliceLoadBefore >= 2, "Alice's load before the race: " + aliceLoadBefore);
                Assertions.assertEquals(List.of(), deliveryAudits(deliveryId));
                boolean riderFirst = round % 2 == 0;
                Callable<String> deliverCommand = () -> {
                    try {
                        return riderService.markDelivered(aliceLogin, deliveryId).status();
                    } catch (ResourceNotFoundException ex) {
                        return "NOT_FOUND|" + ex.getMessage();
                    }
                };
                Callable<String> reassignCommand = () -> {
                    try {
                        DeliveryDto reassigned = deliveryAdminService.reassign(deliveryId, bobId, AuditActor.admin(adminId));
                        return reassigned.status() + "|" + reassigned.riderId();
                    } catch (ConflictException ex) {
                        return conflictOutcome(ex);
                    }
                };

                Future<String> delivered;
                Future<String> reassign;
                try (RowLockHolder deliveryRow = holdRow(executor, "deliveries", deliveryId)) {
                    if (riderFirst) {
                        delivered = deliveryRow.queue(deliverCommand);
                        reassign = deliveryRow.queue(reassignCommand);
                    } else {
                        reassign = deliveryRow.queue(reassignCommand);
                        delivered = deliveryRow.queue(deliverCommand);
                    }
                    deliveryRow.release();
                }

                String deliveredResult = delivered.get(20, TimeUnit.SECONDS);
                String reassignResult = reassign.get(20, TimeUnit.SECONDS);
                // Alice is released once either way: she delivered it, or its load moved to Bob.
                Assertions.assertEquals(aliceLoadBefore - 1, riderLoad(aliceId), "round " + round);
                assertEveryRiderLoadMatchesTheirOpenDeliveries();
                List<String> events = new java.util.ArrayList<>(eventsBefore);
                // One audit row, by the winner; the refused side rolled back whole.
                List<String> audits = deliveryAudits(deliveryId);
                DeliveryTimes times = deliveryTimes(deliveryId);
                if (riderFirst) {
                    // The reassignment found the delivery delivered.
                    Assertions.assertEquals("DELIVERED", deliveredResult);
                    Assertions.assertEquals(
                            "CONFLICT|DELIVERY_STATUS_CONFLICT|Delivery is DELIVERED; only an open delivery can be reassigned",
                            reassignResult
                    );
                    Assertions.assertEquals("DELIVERED|" + aliceId, deliveryState(deliveryId));
                    Assertions.assertEquals(bobLoadBefore, riderLoad(bobId));
                    Assertions.assertEquals(totalLoadBefore - 1, totalRiderLoad());
                    events.add("delivery.delivered");
                    Assertions.assertEquals(List.of("delivery.delivered|ROLE_RIDER|" + aliceLogin), audits);
                    Assertions.assertEquals(timesBefore.delivered(times.updatedAt()), times);
                } else {
                    // The reassignment went first: the delivery is no longer Alice's, so she gets the 404 of a stranger
                    // (her locking read, re-checked after the wait, no longer matches the row).
                    Assertions.assertEquals("NOT_FOUND|Delivery not found", deliveredResult);
                    Assertions.assertEquals("PICKED_UP|" + bobId, reassignResult);
                    Assertions.assertEquals("PICKED_UP|" + bobId, deliveryState(deliveryId));
                    Assertions.assertEquals(bobLoadBefore + 1, riderLoad(bobId));
                    Assertions.assertEquals(totalLoadBefore, totalRiderLoad());
                    events.add("delivery.reassigned");
                    Assertions.assertEquals(List.of("delivery.reassign|ROLE_ADMIN|" + adminId), audits);
                    Assertions.assertEquals(timesBefore.touched(times.updatedAt()), times, "a reassignment keeps the status times");
                }
                Assertions.assertFalse(times.updatedAt().isBefore(timesBefore.updatedAt()));
                Assertions.assertEquals(events, deliveryEventTypes(deliveryId));
                Assertions.assertEquals(
                        LongStream.rangeClosed(1, events.size()).boxed().toList(),
                        aggregateSequences("DELIVERY", deliveryId),
                        "one sequence number per delivery event, without gaps"
                );
                if (!riderFirst) {
                    // Bob finishes it through his own login and gives back the load he took over.
                    Assertions.assertEquals("DELIVERED", riderService.markDelivered(bobLogin, deliveryId).status());
                    Assertions.assertEquals(bobLoadBefore, riderLoad(bobId));
                    assertEveryRiderLoadMatchesTheirOpenDeliveries();
                    DeliveryTimes deliveredByBob = deliveryTimes(deliveryId);
                    Assertions.assertEquals(timesBefore.delivered(deliveredByBob.updatedAt()), deliveredByBob);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }


    /**
     * The rider delivering a picked-up delivery while the dispatch consumer handles the cancellation of its order (the
     * customer cancelled the accepted order after the pick-up). Either the delivery was delivered first and stays
     * DELIVERED, or it was cancelled first and the rider is refused. The rider is released exactly once either way, and
     * the tombstone is set either way.
     */
    @Test
    void riderDeliveredRacingOrderCancelled_shouldReleaseTheRiderOnce() throws Exception {
        Long merchantId = customerIdOf(BURGER_KING_MERCHANT);
        Long aliceLogin = customerIdOf(ALICE_RIDER);
        Long aliceId = riderIdOf(ALICE_RIDER);
        // Another open delivery keeps Alice's load above one during the race, so a second release would show.
        dispatchTo(paidOrderAt("Burger King"), aliceId);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 6; round++) {
                PaidOrder order = paidOrderAt("Burger King");
                Long deliveryId = dispatchTo(order, aliceId);
                dispatchService.markPickedUp(deliveryId);
                // The order module does not know about the pick-up: an ACCEPTED order can still be cancelled.
                orderService.cancelOrder(order.customerId(), order.orderId());
                OrderEventEnvelope cancelled = latestOrderEvent(order.orderId(), "order.cancelled");
                List<String> eventsBefore = deliveryEventTypes(deliveryId);
                DeliveryTimes timesBefore = deliveryTimes(deliveryId);
                int aliceLoadBefore = riderLoad(aliceId);
                int totalLoadBefore = totalRiderLoad();
                Assertions.assertTrue(aliceLoadBefore >= 2, "Alice's load before the race: " + aliceLoadBefore);
                // The consumer marks the event processed and locks the dispatch row before it queues for the delivery.
                boolean riderFirst = round % 2 == 0;
                Callable<String> deliverCommand = () -> {
                    try {
                        return riderService.markDelivered(aliceLogin, deliveryId).status();
                    } catch (ConflictException ex) {
                        return conflictOutcome(ex);
                    }
                };
                Callable<Object> cancellationCommand = () -> {
                    dispatchService.handle(cancelled);
                    return null;
                };

                Future<String> delivered;
                Future<Object> cancellation;
                try (RowLockHolder deliveryRow = holdRow(executor, "deliveries", deliveryId)) {
                    if (riderFirst) {
                        delivered = deliveryRow.queue(deliverCommand);
                        cancellation = deliveryRow.queue(cancellationCommand);
                    } else {
                        cancellation = deliveryRow.queue(cancellationCommand);
                        delivered = deliveryRow.queue(deliverCommand);
                    }
                    deliveryRow.release();
                }

                String deliveredResult = delivered.get(20, TimeUnit.SECONDS);
                cancellation.get(20, TimeUnit.SECONDS);
                Assertions.assertEquals(aliceLoadBefore - 1, riderLoad(aliceId), "round " + round);
                Assertions.assertEquals(totalLoadBefore - 1, totalRiderLoad());
                assertEveryRiderLoadMatchesTheirOpenDeliveries();
                // The tombstone is set whoever won, so a late order.accepted could not dispatch the order again.
                Assertions.assertTrue(dispatchTombstoned(order.orderId()));
                List<String> events = new java.util.ArrayList<>(eventsBefore);
                DeliveryTimes times = deliveryTimes(deliveryId);
                if (riderFirst) {
                    // The cancellation found the delivery finished and left it as it was.
                    Assertions.assertEquals("DELIVERED", deliveredResult);
                    Assertions.assertEquals("DELIVERED|" + aliceId, deliveryState(deliveryId));
                    Assertions.assertEquals(timesBefore.delivered(times.updatedAt()), times);
                    events.add("delivery.delivered");
                    Assertions.assertEquals(List.of("delivery.delivered|ROLE_RIDER|" + aliceLogin), deliveryAudits(deliveryId));
                    // The known trade-off (I24): the delivery does not complete the cancelled order, but the customer is
                    // still told that it was delivered.
                    DeliveryEventEnvelope deliveredEvent = latestDeliveryEvent(deliveryId, "delivery.delivered");
                    deliveryCompletionService.handle(deliveredEvent);
                    deliveryNotificationService.handle(deliveredEvent);
                    Assertions.assertEquals(List.of("Order delivered"), jdbcTemplate.queryForList(
                            "SELECT title FROM order_notifications WHERE order_id = ? AND event_type = 'delivery.delivered'",
                            String.class,
                            order.orderId()
                    ));
                } else {
                    // The rider found the delivery cancelled.
                    Assertions.assertEquals(
                            "CONFLICT|DELIVERY_STATUS_CONFLICT|Delivery is CANCELLED; it cannot move to DELIVERED",
                            deliveredResult
                    );
                    Assertions.assertEquals("CANCELLED|" + aliceId, deliveryState(deliveryId));
                    Assertions.assertEquals(timesBefore.cancelled(times.updatedAt()), times);
                    events.add("delivery.cancelled");
                    // The refused delivery rolled back, its audit row with it.
                    Assertions.assertEquals(List.of(), deliveryAudits(deliveryId));
                }
                Assertions.assertEquals(events, deliveryEventTypes(deliveryId));
                Assertions.assertEquals(
                        LongStream.rangeClosed(1, events.size()).boxed().toList(),
                        aggregateSequences("DELIVERY", deliveryId),
                        "one sequence number per delivery event, without gaps"
                );
                // Neither side touches the order: it stays cancelled by the customer.
                Assertions.assertEquals("CANCELLED", orderStatus(order.orderId()));
                Assertions.assertEquals(OrderLifecycleCommands.CUSTOMER_CANCELLED, cancelReason(order.orderId()));
                Assertions.assertEquals(
                        List.of("order.paid", "order.accepted", "order.cancelled"), orderEventTypes(order.orderId()));
                Assertions.assertEquals(List.of(
                        "->PAID|CUSTOMER|" + order.customerId() + "|null",
                        "PAID->ACCEPTED|MERCHANT|" + merchantId + "|null",
                        "ACCEPTED->CANCELLED|CUSTOMER|" + order.customerId() + "|" + OrderLifecycleCommands.CUSTOMER_CANCELLED
                ), statusHistory(order.orderId()));
            }
        } finally {
            executor.shutdownNow();
        }
    }


    @Test
    void acceptTimeout_shouldCancelAPaidOrderTheMerchantNeverAccepted() {
        PaidOrder order = paidOrderAt("Fashion Wok");
        deliverOrderEvent(order.orderId(), "order.paid", orderDeadlineService::handle);
        Assertions.assertTrue(deadlineTypes(order.orderId()).isEmpty(), "order.paid alone sets no accept deadline");
        // Fashion Wok has an active merchant: the inbox announces the order and the timeout module sets the deadline.
        deliverOrderEvent(order.orderId(), "order.paid", merchantInboxService::handle);
        deliverMerchantOrderReceived(order.orderId());
        Assertions.assertEquals(List.of("ACCEPT"), deadlineTypes(order.orderId()));
        LocalDateTime dueAt = jdbcTemplate.queryForObject(
                "SELECT due_at FROM order_deadlines WHERE order_id = ?", LocalDateTime.class, order.orderId());
        LocalDateTime expectedDueAt = latestMerchantEvent(order.orderId()).occurredAt().plusMinutes(10);
        Assertions.assertTrue(
                java.time.Duration.between(expectedDueAt, dueAt).abs().toMillis() < 1,
                "due_at " + dueAt + " should be the inbox receipt time plus the accept timeout, " + expectedDueAt
        );

        orderDeadlineProcessor.fireDueDeadlines();
        Assertions.assertEquals("PAID", orderStatus(order.orderId()), "The deadline is not due yet");

        makeDeadlinesDue(order.orderId());
        orderDeadlineProcessor.fireDueDeadlines();

        Assertions.assertEquals("CANCELLED", orderStatus(order.orderId()));
        Assertions.assertEquals(OrderLifecycleCommands.MERCHANT_TIMEOUT, cancelReason(order.orderId()));
        Assertions.assertTrue(deadlineTypes(order.orderId()).isEmpty());
        Assertions.assertEquals(List.of(
                "->PAID|CUSTOMER|" + order.customerId() + "|null",
                "PAID->CANCELLED|SYSTEM|null|" + OrderLifecycleCommands.MERCHANT_TIMEOUT
        ), statusHistory(order.orderId()));
        OrderEventEnvelope cancelled = latestOrderEvent(order.orderId(), "order.cancelled");
        Assertions.assertEquals(OrderLifecycleCommands.MERCHANT_TIMEOUT, cancelled.data().reason());

        // The merchant inbox follows the cancel event; accepting now is a conflict.
        merchantInboxService.handle(cancelled);
        Assertions.assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "SELECT status FROM merchant_orders WHERE order_id = ?", String.class, order.orderId()));
        Assertions.assertThrows(
                ConflictException.class,
                () -> merchantOrderService.accept(customerIdOf("merchant.fashionwok@laifood.com"), order.orderId())
        );
    }


    @Test
    void acceptTimeout_whenTheInboxReceivedOrderPaidAfterTheTimeout_shouldStillGiveTheMerchantTheWholeWindow() {
        PaidOrder order = paidOrderAt("Burger King");
        // The merchant group handles order.paid 11 minutes after the payment (a backlog, or the dead-lettered event
        // replayed): measured from the payment, the 10-minute deadline would already be due.
        OrderEventEnvelope paid = latestOrderEvent(order.orderId(), "order.paid");
        LocalDateTime paidAt = LocalDateTime.now().minusMinutes(11);
        merchantInboxService.handle(new OrderEventEnvelope(
                paid.eventId(),
                paid.eventVersion(),
                paid.eventType(),
                paid.aggregateType(),
                paid.aggregateId(),
                paidAt,
                paid.correlationId(),
                paid.idempotencyKey(),
                paid.data()
        ));
        LocalDateTime receivedAt = latestMerchantEvent(order.orderId()).occurredAt();
        Assertions.assertEquals(paidAt, latestMerchantEvent(order.orderId()).data().paidAt());
        deliverMerchantOrderReceived(order.orderId());

        LocalDateTime dueAt = jdbcTemplate.queryForObject(
                "SELECT due_at FROM order_deadlines WHERE order_id = ?", LocalDateTime.class, order.orderId());
        Assertions.assertTrue(
                java.time.Duration.between(receivedAt.plusMinutes(10), dueAt).abs().toMillis() < 1,
                "due_at " + dueAt + " should be the receipt time " + receivedAt + " plus the accept timeout"
        );
        Assertions.assertTrue(dueAt.isAfter(LocalDateTime.now().plusMinutes(9)));

        // The poller leaves the order to the merchant, who can still accept it.
        orderDeadlineProcessor.fireDueDeadlines();
        Assertions.assertEquals("PAID", orderStatus(order.orderId()));
        Assertions.assertEquals(List.of("ACCEPT"), deadlineTypes(order.orderId()));
        merchantOrderService.accept(customerIdOf(BURGER_KING_MERCHANT), order.orderId());
        Assertions.assertEquals("ACCEPTED", orderStatus(order.orderId()));
    }


    @Test
    void acceptTimeout_afterTheMerchantAccepted_shouldLeaveTheOrderAlone() {
        PaidOrder order = paidOrderAt("Burger King");
        deliverOrderEvent(order.orderId(), "order.paid", orderDeadlineService::handle);
        deliverOrderEvent(order.orderId(), "order.paid", merchantInboxService::handle);
        deliverMerchantOrderReceived(order.orderId());
        Assertions.assertEquals(List.of("ACCEPT"), deadlineTypes(order.orderId()));
        merchantOrderService.accept(customerIdOf(BURGER_KING_MERCHANT), order.orderId());
        // The poller fires before the timeout consumer has seen order.accepted.
        makeDeadlinesDue(order.orderId());

        orderDeadlineProcessor.fireDueDeadlines();

        Assertions.assertEquals("ACCEPTED", orderStatus(order.orderId()));
        Assertions.assertTrue(deadlineTypes(order.orderId()).isEmpty());
        Assertions.assertEquals(List.of("order.paid", "order.accepted"), orderEventTypes(order.orderId()));
    }


    @Test
    void acceptTimeout_atARestaurantWithoutAnyMerchant_shouldNeverBeSet() {
        String restaurant = newRestaurantWithoutStaff();
        PaidOrder order = paidOrderAt(restaurant);
        deliverOrderEvent(order.orderId(), "order.paid", orderDeadlineService::handle);
        deliverOrderEvent(order.orderId(), "order.paid", merchantInboxService::handle);

        // The order is in the restaurant's inbox for a future merchant, but nothing announces it to the timeout module.
        Assertions.assertEquals("PAID", inboxStatus(order.orderId()));
        Assertions.assertEquals(0, merchantEventCount(order.orderId()));
        Assertions.assertTrue(deadlineTypes(order.orderId()).isEmpty());
        orderDeadlineProcessor.fireDueDeadlines();
        Assertions.assertEquals("PAID", orderStatus(order.orderId()));
    }


    @Test
    void acceptTimeout_whenTheOnlyMerchantWasDeactivatedBeforeTheOrder_shouldNotBeSet() throws Exception {
        String restaurant = newRestaurantWithoutStaff();
        Long restaurantId = jdbcTemplate.queryForObject("SELECT id FROM restaurants WHERE name = ?", Long.class, restaurant);
        MockHttpSession admin = login("demo@laifood.com", "demo123");
        String merchantEmail = "merchant-" + System.nanoTime() + "@example.com";
        String created = mockMvc.perform(post("/admin/merchants")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "secret-1", "first_name": "Late", "last_name": "Shift",
                                 "restaurant_id": %d}
                                """.formatted(merchantEmail, restaurantId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long merchantId = objectMapper.readTree(created).get("merchant_id").asLong();

        // Staffed: the order gets an accept deadline.
        PaidOrder staffed = paidOrderAt(restaurant);
        deliverOrderEvent(staffed.orderId(), "order.paid", merchantInboxService::handle);
        Assertions.assertEquals(1, merchantEventCount(staffed.orderId()));
        deliverMerchantOrderReceived(staffed.orderId());
        Assertions.assertEquals(List.of("ACCEPT"), deadlineTypes(staffed.orderId()));

        mockMvc.perform(patch("/admin/merchants/{id}", merchantId)
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        // Deactivated before the next order arrives: no announcement, no deadline.
        PaidOrder unstaffed = paidOrderAt(restaurant);
        deliverOrderEvent(unstaffed.orderId(), "order.paid", orderDeadlineService::handle);
        deliverOrderEvent(unstaffed.orderId(), "order.paid", merchantInboxService::handle);
        Assertions.assertEquals(0, merchantEventCount(unstaffed.orderId()));
        Assertions.assertTrue(deadlineTypes(unstaffed.orderId()).isEmpty());

        // The deadline set while the restaurant was staffed still fires; the unstaffed order is left alone.
        makeDeadlinesDue(staffed.orderId());
        orderDeadlineProcessor.fireDueDeadlines();
        Assertions.assertEquals("CANCELLED", orderStatus(staffed.orderId()));
        Assertions.assertEquals(OrderLifecycleCommands.MERCHANT_TIMEOUT, cancelReason(staffed.orderId()));
        Assertions.assertEquals("PAID", orderStatus(unstaffed.orderId()));
    }


    @Test
    void paymentTimeout_shouldCancelAPlacedOrderThatWasNeverPaid() {
        String email = "fulfilment-placed-" + System.nanoTime() + "@example.com";
        customerService.signUp(email, "demo123", "Placed", "Customer");
        Long customerId = jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
        cartService.addMenuItemToCart(customerId, menuItemAt("SGD Tofu House"));
        OrderDto placed = cartService.checkout(customerId);
        Assertions.assertEquals("PLACED", placed.status());

        deliverOrderEvent(placed.id(), "order.created", orderDeadlineService::handle);
        Assertions.assertEquals(List.of("PAYMENT"), deadlineTypes(placed.id()));
        makeDeadlinesDue(placed.id());
        orderDeadlineProcessor.fireDueDeadlines();

        Assertions.assertEquals("CANCELLED", orderStatus(placed.id()));
        Assertions.assertEquals(OrderLifecycleCommands.PAYMENT_TIMEOUT, cancelReason(placed.id()));
        Assertions.assertTrue(deadlineTypes(placed.id()).isEmpty());
        Assertions.assertEquals(List.of(
                "->PLACED|CUSTOMER|" + customerId + "|null",
                "PLACED->CANCELLED|SYSTEM|null|" + OrderLifecycleCommands.PAYMENT_TIMEOUT
        ), statusHistory(placed.id()));
    }


    @Test
    void concurrentPollers_shouldFireEveryDeadlineExactlyOnce() throws Exception {
        List<Long> orderIds = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            PaidOrder order = paidOrderAt(i % 2 == 0 ? "Burger King" : "Fashion Wok");
            deliverOrderEvent(order.orderId(), "order.paid", orderDeadlineService::handle);
            deliverOrderEvent(order.orderId(), "order.paid", merchantInboxService::handle);
            deliverMerchantOrderReceived(order.orderId());
            // A duplicate delivery adds no second deadline.
            deliverMerchantOrderReceived(order.orderId());
            Assertions.assertEquals(List.of("ACCEPT"), deadlineTypes(order.orderId()));
            makeDeadlinesDue(order.orderId());
            orderIds.add(order.orderId());
        }
        ExecutorService pollers = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> runs = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                runs.add(pollers.submit(awaiting(start, orderDeadlineProcessor::fireDueDeadlines)));
            }
            start.countDown();
            int fired = 0;
            for (Future<Integer> run : runs) {
                fired += run.get(30, TimeUnit.SECONDS);
            }
            Assertions.assertTrue(fired >= orderIds.size());
        } finally {
            pollers.shutdownNow();
        }

        for (Long orderId : orderIds) {
            Assertions.assertEquals("CANCELLED", orderStatus(orderId));
            Assertions.assertEquals(List.of("order.paid", "order.cancelled"), orderEventTypes(orderId));
            Assertions.assertTrue(deadlineTypes(orderId).isEmpty());
            Assertions.assertEquals(2, statusHistory(orderId).size(), "one history row per change, not per poller");
        }
    }


    @Test
    void acceptedOrder_shouldBeDispatchedPickedUpDeliveredAndCompleted() throws Exception {
        PaidOrder order = paidOrderAt("Burger King");
        deliverOrderEvent(order.orderId(), "order.paid", merchantInboxService::handle);
        merchantOrderService.accept(customerIdOf(BURGER_KING_MERCHANT), order.orderId());
        OrderEventEnvelope accepted = latestOrderEvent(order.orderId(), "order.accepted");
        int totalLoadBefore = totalRiderLoad();

        dispatchService.handle(accepted);
        dispatchService.handle(accepted);

        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM deliveries WHERE order_id = ?", Integer.class, order.orderId()));
        Assertions.assertEquals(totalLoadBefore + 1, totalRiderLoad());
        Long deliveryId = jdbcTemplate.queryForObject(
                "SELECT id FROM deliveries WHERE order_id = ?", Long.class, order.orderId());
        Assertions.assertEquals(List.of("delivery.assigned"), deliveryEventTypes(deliveryId));
        Assertions.assertEquals(List.of("delivery-events|" + order.orderId()), jdbcTemplate.queryForList(
                "SELECT DISTINCT topic || '|' || event_key FROM outbox_events WHERE aggregate_type = 'DELIVERY' AND aggregate_id = ?",
                String.class,
                deliveryId
        ));

        MockHttpSession admin = login("demo@laifood.com", "demo123");
        MockHttpSession customer = login(order.customerEmail(), "demo123");
        mockMvc.perform(post("/admin/deliveries/{id}/picked-up", deliveryId).session(customer))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/deliveries").param("order_id", order.orderId().toString()).session(customer))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/deliveries").param("order_id", order.orderId().toString()).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(deliveryId))
                .andExpect(jsonPath("$[0].status").value("ASSIGNED"))
                .andExpect(jsonPath("$[0].rider_name").exists());
        mockMvc.perform(post("/admin/deliveries/{id}/delivered", deliveryId).session(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_STATUS_CONFLICT"));
        mockMvc.perform(post("/admin/deliveries/{id}/picked-up", deliveryId).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKED_UP"));
        mockMvc.perform(post("/admin/deliveries/{id}/delivered", deliveryId).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        mockMvc.perform(post("/admin/deliveries/{id}/delivered", deliveryId).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        Assertions.assertEquals(totalLoadBefore, totalRiderLoad());
        Assertions.assertEquals(
                List.of("delivery.assigned", "delivery.picked_up", "delivery.delivered"),
                deliveryEventTypes(deliveryId)
        );

        // The merchant never marked the order PREPARING: completion passes through it in one transaction.
        DeliveryEventEnvelope delivered = latestDeliveryEvent(deliveryId, "delivery.delivered");
        deliveryCompletionService.handle(delivered);
        deliveryCompletionService.handle(delivered);
        Assertions.assertEquals("COMPLETED", orderStatus(order.orderId()));
        Assertions.assertEquals(
                List.of("order.paid", "order.accepted", "order.preparing", "order.completed"),
                orderEventTypes(order.orderId())
        );
        Long riderId = delivered.data().riderId();
        Assertions.assertEquals(List.of(
                "->PAID|CUSTOMER|" + order.customerId() + "|null",
                "PAID->ACCEPTED|MERCHANT|" + customerIdOf(BURGER_KING_MERCHANT) + "|null",
                "ACCEPTED->PREPARING|RIDER|" + riderId + "|DELIVERED",
                "PREPARING->COMPLETED|RIDER|" + riderId + "|DELIVERED"
        ), statusHistory(order.orderId()));
        assertUpdatedAtIsTheLastStatusChange(order.orderId());

        for (String eventType : List.of("delivery.assigned", "delivery.picked_up", "delivery.delivered")) {
            DeliveryEventEnvelope event = latestDeliveryEvent(deliveryId, eventType);
            deliveryNotificationService.handle(event);
            deliveryNotificationService.handle(event);
        }
        Assertions.assertEquals(
                List.of("delivery.assigned", "delivery.delivered", "delivery.picked_up"),
                jdbcTemplate.queryForList(
                        "SELECT event_type FROM order_notifications WHERE order_id = ? ORDER BY event_type",
                        String.class,
                        order.orderId()
                )
        );
    }


    @Test
    void customerCancellingAnAcceptedOrder_shouldCancelTheDeliveryAndReleaseTheRider() {
        PaidOrder order = paidOrderAt("Fashion Wok");
        orderLifecycleCommands.transitionRestaurantOrder(
                order.orderId(), order.restaurantId(), OrderStatus.PAID, OrderStatus.ACCEPTED, null, OrderActor.system());
        int totalLoadBefore = totalRiderLoad();
        deliverOrderEvent(order.orderId(), "order.accepted", dispatchService::handle);
        Assertions.assertEquals(totalLoadBefore + 1, totalRiderLoad());

        orderService.cancelOrder(order.customerId(), order.orderId());
        deliverOrderEvent(order.orderId(), "order.cancelled", dispatchService::handle);

        Assertions.assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "SELECT status FROM deliveries WHERE order_id = ?", String.class, order.orderId()));
        Assertions.assertEquals(totalLoadBefore, totalRiderLoad());
        Long deliveryId = jdbcTemplate.queryForObject(
                "SELECT id FROM deliveries WHERE order_id = ?", Long.class, order.orderId());
        Assertions.assertEquals(List.of("delivery.assigned", "delivery.cancelled"), deliveryEventTypes(deliveryId));
        Assertions.assertEquals(OrderLifecycleCommands.CUSTOMER_CANCELLED,
                latestOrderEvent(order.orderId(), "order.cancelled").data().reason());
    }


    @Test
    void concurrentAndDuplicateAssignments_shouldGiveEveryOrderExactlyOneRider() throws Exception {
        List<OrderEventEnvelope> acceptedEvents = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            PaidOrder order = paidOrderAt(i % 2 == 0 ? "SGD Tofu House" : "Burger King");
            orderLifecycleCommands.transitionRestaurantOrder(
                    order.orderId(), order.restaurantId(), OrderStatus.PAID, OrderStatus.ACCEPTED, null, OrderActor.system());
            acceptedEvents.add(latestOrderEvent(order.orderId(), "order.accepted"));
        }
        int totalLoadBefore = totalRiderLoad();
        ExecutorService dispatchers = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> runs = new java.util.ArrayList<>();
            for (OrderEventEnvelope event : acceptedEvents) {
                // Every event is delivered twice at the same time, as after a consumer rebalance.
                for (int copy = 0; copy < 2; copy++) {
                    runs.add(dispatchers.submit(awaiting(start, () -> {
                        dispatchService.handle(event);
                        return null;
                    })));
                }
            }
            start.countDown();
            for (Future<Object> run : runs) {
                run.get(30, TimeUnit.SECONDS);
            }
        } finally {
            dispatchers.shutdownNow();
        }

        for (OrderEventEnvelope event : acceptedEvents) {
            Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM deliveries WHERE order_id = ?", Integer.class, event.aggregateId()));
            Long deliveryId = jdbcTemplate.queryForObject(
                    "SELECT id FROM deliveries WHERE order_id = ?", Long.class, event.aggregateId());
            Assertions.assertEquals(List.of("delivery.assigned"), deliveryEventTypes(deliveryId));
        }
        Assertions.assertEquals(totalLoadBefore + acceptedEvents.size(), totalRiderLoad());
        assertEveryRiderLoadMatchesTheirOpenDeliveries();
    }


    @Test
    void merchantReject_ofAnOrderTheCustomerAlreadyCancelled_shouldConflictAndKeepTheCustomersReason() throws Exception {
        PaidOrder order = paidOrderAt("Burger King");
        deliverOrderEvent(order.orderId(), "order.paid", merchantInboxService::handle);
        MockHttpSession burgerKing = login(BURGER_KING_MERCHANT, MERCHANT_PASSWORD);
        double rejectedBefore = merchantDecisions("rejected");
        // The customer cancels while the merchant inbox still shows the order as PAID.
        orderService.cancelOrder(order.customerId(), order.orderId());

        mockMvc.perform(post("/merchant/orders/{id}/reject", order.orderId())
                        .session(burgerKing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"out of stock\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(OrderLifecycleCommands.ORDER_STATUS_CONFLICT));

        Assertions.assertEquals(OrderLifecycleCommands.CUSTOMER_CANCELLED, cancelReason(order.orderId()));
        Assertions.assertEquals(List.of("order.paid", "order.cancelled"), orderEventTypes(order.orderId()));
        Assertions.assertEquals(rejectedBefore, merchantDecisions("rejected"));

        // Once the inbox caught up, the merchant sees why the order was cancelled.
        deliverOrderEvent(order.orderId(), "order.cancelled", merchantInboxService::handle);
        mockMvc.perform(get("/merchant/orders").param("status", "CANCELLED").session(burgerKing))
                .andExpect(jsonPath("$[?(@.order_id == %d)].cancel_reason".formatted(order.orderId()))
                        .value(OrderLifecycleCommands.CUSTOMER_CANCELLED));
    }


    @Test
    void merchantAccept_ofAnOrderAnAdminAcceptedBeforeTheInboxCaughtUp_shouldNotCountADecision() throws Exception {
        PaidOrder order = paidOrderAt("Burger King");
        deliverOrderEvent(order.orderId(), "order.paid", merchantInboxService::handle);
        MockHttpSession burgerKing = login(BURGER_KING_MERCHANT, MERCHANT_PASSWORD);
        // PATCH /orders/{id}/status by an admin; the merchant consumer has not seen order.accepted yet.
        orderService.transitionOrderStatus(order.orderId(), "ACCEPTED");
        double acceptedBefore = merchantDecisions("accepted");

        mockMvc.perform(post("/merchant/orders/{id}/accept", order.orderId()).session(burgerKing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        Assertions.assertEquals(acceptedBefore, merchantDecisions("accepted"));
        Assertions.assertEquals(List.of("order.paid", "order.accepted"), orderEventTypes(order.orderId()));
        Assertions.assertEquals("ACCEPTED", inboxStatus(order.orderId()));

        // A decision the merchant does make is counted once.
        PaidOrder second = paidOrderAt("Burger King");
        deliverOrderEvent(second.orderId(), "order.paid", merchantInboxService::handle);
        mockMvc.perform(post("/merchant/orders/{id}/accept", second.orderId()).session(burgerKing))
                .andExpect(status().isOk());
        mockMvc.perform(post("/merchant/orders/{id}/accept", second.orderId()).session(burgerKing))
                .andExpect(status().isOk());
        Assertions.assertEquals(acceptedBefore + 1, merchantDecisions("accepted"));
    }


    @Test
    void orderPaidReplayedAfterTheOrderWasCancelled_shouldShowTheCancelledOrderNotAStalePaidOne() throws Exception {
        PaidOrder order = paidOrderAt("Burger King");
        OrderEventEnvelope paid = latestOrderEvent(order.orderId(), "order.paid");
        orderService.cancelOrder(order.customerId(), order.orderId());
        MockHttpSession burgerKing = login(BURGER_KING_MERCHANT, MERCHANT_PASSWORD);

        // order.paid was dead-lettered from the merchant group, so order.cancelled arrives there first: it leaves a
        // tombstone the merchant does not see yet (the order was never in their inbox).
        deliverOrderEvent(order.orderId(), "order.cancelled", merchantInboxService::handle);
        Assertions.assertEquals("CANCELLED", inboxStatus(order.orderId()));
        mockMvc.perform(get("/merchant/orders").session(burgerKing))
                .andExpect(jsonPath("$[?(@.order_id == %d)]".formatted(order.orderId())).isEmpty());
        mockMvc.perform(post("/merchant/orders/{id}/accept", order.orderId()).session(burgerKing))
                .andExpect(status().isNotFound());

        // The operator replays the dead-lettered order.paid.
        merchantInboxService.handle(paid);

        Assertions.assertEquals("CANCELLED", inboxStatus(order.orderId()));
        Assertions.assertNotNull(jdbcTemplate.queryForObject(
                "SELECT paid_at FROM merchant_orders WHERE order_id = ?", LocalDateTime.class, order.orderId()));
        mockMvc.perform(get("/merchant/orders").param("status", "PAID").session(burgerKing))
                .andExpect(jsonPath("$[?(@.order_id == %d)]".formatted(order.orderId())).isEmpty());
        mockMvc.perform(get("/merchant/orders").param("status", "CANCELLED").session(burgerKing))
                .andExpect(jsonPath("$[?(@.order_id == %d)].cancel_reason".formatted(order.orderId()))
                        .value(OrderLifecycleCommands.CUSTOMER_CANCELLED));
        mockMvc.perform(post("/merchant/orders/{id}/accept", order.orderId()).session(burgerKing))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(OrderLifecycleCommands.ORDER_STATUS_CONFLICT));
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM merchant_orders WHERE order_id = ?", Integer.class, order.orderId()));
    }


    @Test
    void orderPaidReplayedAfterTheOrderWasAccepted_shouldShowTheAcceptedOrder() throws Exception {
        PaidOrder order = paidOrderAt("SGD Tofu House");
        OrderEventEnvelope paid = latestOrderEvent(order.orderId(), "order.paid");
        orderService.transitionOrderStatus(order.orderId(), "ACCEPTED");
        MockHttpSession sgdTofu = login(SGD_TOFU_MERCHANT, MERCHANT_PASSWORD);

        deliverOrderEvent(order.orderId(), "order.accepted", merchantInboxService::handle);
        merchantInboxService.handle(paid);

        Assertions.assertEquals("ACCEPTED", inboxStatus(order.orderId()));
        mockMvc.perform(post("/merchant/orders/{id}/preparing", order.orderId()).session(sgdTofu))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREPARING"));
    }


    @Test
    void orderAcceptedReplayedAfterTheOrderWasCancelled_shouldAssignNoRider() {
        PaidOrder order = paidOrderAt("Burger King");
        orderLifecycleCommands.transitionRestaurantOrder(
                order.orderId(), order.restaurantId(), OrderStatus.PAID, OrderStatus.ACCEPTED, null, OrderActor.system());
        OrderEventEnvelope accepted = latestOrderEvent(order.orderId(), "order.accepted");
        orderService.cancelOrder(order.customerId(), order.orderId());
        int totalLoadBefore = totalRiderLoad();

        // order.accepted failed in the dispatch group (no active rider) and was dead-lettered; order.cancelled got
        // through first. Then the operator replays the dead letter.
        deliverOrderEvent(order.orderId(), "order.cancelled", dispatchService::handle);
        dispatchService.handle(accepted);

        Assertions.assertEquals("CANCELLED", orderStatus(order.orderId()));
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM deliveries WHERE order_id = ?", Integer.class, order.orderId()));
        Assertions.assertEquals(totalLoadBefore, totalRiderLoad());
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'DELIVERY' AND event_key = ?",
                Integer.class,
                String.valueOf(order.orderId())
        ));
        Assertions.assertNotNull(jdbcTemplate.queryForObject(
                "SELECT cancelled_at FROM dispatch_orders WHERE order_id = ?", LocalDateTime.class, order.orderId()));
    }


    @Test
    void acceptedAndCancelledHandledConcurrently_shouldNeverLeaveARiderOnACancelledOrder() throws Exception {
        ExecutorService dispatchers = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                PaidOrder order = paidOrderAt(round % 2 == 0 ? "Fashion Wok" : "SGD Tofu House");
                orderLifecycleCommands.transitionRestaurantOrder(
                        order.orderId(), order.restaurantId(), OrderStatus.PAID, OrderStatus.ACCEPTED, null, OrderActor.system());
                orderService.cancelOrder(order.customerId(), order.orderId());
                OrderEventEnvelope accepted = latestOrderEvent(order.orderId(), "order.accepted");
                OrderEventEnvelope cancelled = latestOrderEvent(order.orderId(), "order.cancelled");
                CountDownLatch start = new CountDownLatch(1);

                Future<Object> acceptedRun = dispatchers.submit(awaiting(start, () -> {
                    dispatchService.handle(accepted);
                    return null;
                }));
                Future<Object> cancelledRun = dispatchers.submit(awaiting(start, () -> {
                    dispatchService.handle(cancelled);
                    return null;
                }));
                start.countDown();
                acceptedRun.get(30, TimeUnit.SECONDS);
                cancelledRun.get(30, TimeUnit.SECONDS);

                // Either order: no delivery at all, or a delivery that was assigned and then cancelled.
                List<String> deliveries = jdbcTemplate.queryForList(
                        "SELECT status FROM deliveries WHERE order_id = ?", String.class, order.orderId());
                Assertions.assertTrue(
                        deliveries.isEmpty() || deliveries.equals(List.of("CANCELLED")),
                        "round " + round + ": deliveries " + deliveries
                );
            }
        } finally {
            dispatchers.shutdownNow();
        }
        assertEveryRiderLoadMatchesTheirOpenDeliveries();
    }


    // ---- helpers shared by the fulfilment tests ----

    /**
     * The order's history rows, oldest first, as "from->to|actor_type|actor_id|reason".
     */
    List<String> statusHistory(Long orderId) {
        return jdbcTemplate.query("""
                SELECT from_status, to_status, actor_type, actor_id, reason
                FROM order_status_history
                WHERE order_id = ?
                ORDER BY id
                """, (row, index) -> (row.getString("from_status") == null ? "" : row.getString("from_status"))
                + "->" + row.getString("to_status")
                + "|" + row.getString("actor_type")
                + "|" + row.getObject("actor_id")
                + "|" + row.getString("reason"), orderId);
    }


    /**
     * The order's admin status change audit rows, oldest first, as "actor_customer_id|from->to|changed|idempotency_key".
     */
    List<String> orderStatusAudits(Long orderId) {
        return jdbcTemplate.query("""
                SELECT actor_customer_id, details
                FROM audit_log
                WHERE action = 'order.status.update'
                  AND target_type = 'ORDER'
                  AND target_id = ?
                ORDER BY id
                """, (row, index) -> {
            JsonNode details = objectMapper.readTree(row.getString("details"));
            return row.getObject("actor_customer_id")
                    + "|" + details.get("from_status").asString() + "->" + details.get("to_status").asString()
                    + "|" + details.get("changed").asBoolean()
                    + "|" + details.get("idempotency_key").asString();
        }, String.valueOf(orderId));
    }


    /**
     * The idempotency rows of the order's admin status changes and customer cancels, oldest first, as
     * "scope|idempotency_key|status|order_id".
     */
    List<String> orderIdempotencyRequests(Long orderId) {
        return jdbcTemplate.query("""
                SELECT scope, idempotency_key, status, order_id
                FROM idempotency_requests
                WHERE scope IN (?, ?)
                ORDER BY id
                """, (row, index) -> row.getString("scope")
                + "|" + row.getString("idempotency_key")
                + "|" + row.getString("status")
                + "|" + row.getObject("order_id"), "order-status:v1:" + orderId, "order-cancel:v1:" + orderId);
    }


    /**
     * The aggregate sequences of an aggregate's outbox events, in insertion order.
     */
    List<Long> aggregateSequences(String aggregateType, Long aggregateId) {
        return jdbcTemplate.queryForList("""
                SELECT aggregate_sequence
                FROM outbox_events
                WHERE aggregate_type = ?
                  AND aggregate_id = ?
                ORDER BY id
                """, Long.class, aggregateType, aggregateId);
    }


    Long lastAggregateSequence(String aggregateType, Long aggregateId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_sequence FROM outbox_aggregate_sequences WHERE aggregate_type = ? AND aggregate_id = ?",
                Long.class,
                aggregateType,
                aggregateId
        );
    }


    void assertUpdatedAtIsTheLastStatusChange(Long orderId) {
        Assertions.assertEquals(
                jdbcTemplate.queryForObject("SELECT MAX(occurred_at) FROM order_status_history WHERE order_id = ?",
                        LocalDateTime.class, orderId),
                jdbcTemplate.queryForObject("SELECT updated_at FROM orders WHERE id = ?", LocalDateTime.class, orderId),
                "updated_at is the time of the last status change"
        );
    }


    /**
     * ADR 0002 I2 on the stored rows: the order's events and its history rows pair up one to one, in order. Each event
     * is of its row's target status, keyed by the order id on the order topic, carries the reason of a cancel row, and
     * happened at the row's time (to the database's microsecond: the event keeps every digit of the Java clock); the
     * order's status and updated_at are those of the last row.
     */
    void assertEveryOrderEventMatchesItsHistoryRow(Long orderId) {
        record HistoryRow(OrderStatus toStatus, String reason, LocalDateTime occurredAt) {
        }
        List<HistoryRow> history = jdbcTemplate.query("""
                SELECT to_status, reason, occurred_at
                FROM order_status_history
                WHERE order_id = ?
                ORDER BY id
                """, (row, index) -> new HistoryRow(
                OrderStatus.valueOf(row.getString("to_status")),
                row.getString("reason"),
                row.getObject("occurred_at", LocalDateTime.class)
        ), orderId);
        List<OrderEventEnvelope> events = jdbcTemplate.queryForList("""
                        SELECT payload
                        FROM outbox_events
                        WHERE aggregate_type = 'ORDER'
                          AND aggregate_id = ?
                        ORDER BY id
                        """, String.class, orderId).stream()
                .map(payload -> objectMapper.readValue(payload, OrderEventEnvelope.class))
                .toList();

        Assertions.assertEquals(history.size(), events.size(), "one event per history row of order " + orderId);
        for (int step = 0; step < history.size(); step++) {
            HistoryRow row = history.get(step);
            OrderEventEnvelope event = events.get(step);
            String where = "order " + orderId + ", step " + step + " to " + row.toStatus();
            Assertions.assertEquals(row.toStatus().eventType(), event.eventType(), where);
            Assertions.assertEquals(orderId, event.aggregateId(), where);
            Assertions.assertEquals(row.toStatus().name(), event.data().status(), where);
            if (row.toStatus() == OrderStatus.CANCELLED) {
                Assertions.assertEquals(row.reason(), event.data().reason(), where);
            }
            Assertions.assertTrue(
                    java.time.Duration.between(row.occurredAt(), event.occurredAt()).abs().toNanos() < 1_000,
                    where + ": the event happened at " + event.occurredAt() + ", the history row at " + row.occurredAt()
            );
        }
        Assertions.assertEquals(List.of("order-events|" + orderId), jdbcTemplate.queryForList(
                "SELECT DISTINCT topic || '|' || event_key FROM outbox_events WHERE aggregate_type = 'ORDER' AND aggregate_id = ?",
                String.class,
                orderId
        ), "every order event is keyed by the order id");
        HistoryRow last = history.get(history.size() - 1);
        Assertions.assertEquals(last.toStatus().name(), orderStatus(orderId));
        Assertions.assertEquals(
                last.occurredAt(),
                jdbcTemplate.queryForObject("SELECT updated_at FROM orders WHERE id = ?", LocalDateTime.class, orderId),
                "updated_at is the time of the last status change"
        );
    }


    static void assertUpdatedAtOnlyMovesForward(List<DeliveryTimes> changes) {
        for (int index = 1; index < changes.size(); index++) {
            Assertions.assertFalse(
                    changes.get(index).updatedAt().isBefore(changes.get(index - 1).updatedAt()),
                    "updated_at moved back: " + changes
            );
        }
    }


    /**
     * A command refused with a 409, as "CONFLICT|code|message".
     */
    static String conflictOutcome(ConflictException conflict) {
        return "CONFLICT|" + conflict.getCode() + "|" + conflict.getMessage();
    }


    /**
     * A second admin login (ROLE_ADMIN), besides the demo admin. There is no endpoint that grants ROLE_ADMIN, so the
     * authority is inserted directly; the login stays for the rest of the class, whose assertions are per order.
     */
    Long newAdmin() {
        String email = "fulfilment-admin-" + System.nanoTime() + "@example.com";
        customerService.signUp(email, "demo123", "Second", "Admin");
        jdbcTemplate.update("INSERT INTO authorities (email, authority) VALUES (?, 'ROLE_ADMIN')", email);
        return customerIdOf(email);
    }


    Long customerIdOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM customers WHERE LOWER(email) = LOWER(?)", Long.class, email);
    }


    double merchantDecisions(String decision) {
        Counter counter = meterRegistry.find("onlineorder.fulfilment.merchant.decisions")
                .tag("decision", decision)
                .counter();
        return counter == null ? 0 : counter.count();
    }


    String inboxStatus(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM merchant_orders WHERE order_id = ?", String.class, orderId);
    }


    int totalRiderLoad() {
        return jdbcTemplate.queryForObject("SELECT COALESCE(SUM(active_deliveries), 0) FROM riders", Integer.class);
    }


    void assertEveryRiderLoadMatchesTheirOpenDeliveries() {
        Assertions.assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM riders
                WHERE active_deliveries <> (
                    SELECT COUNT(*) FROM deliveries
                    WHERE deliveries.rider_id = riders.id AND deliveries.status IN ('ASSIGNED', 'PICKED_UP')
                )
                """, Integer.class), "Every rider's load matches their open deliveries");
    }


    int riderLoad(Long riderId) {
        return jdbcTemplate.queryForObject("SELECT active_deliveries FROM riders WHERE id = ?", Integer.class, riderId);
    }


    /**
     * The riders.id of a rider login.
     */
    Long riderIdOf(String email) {
        return jdbcTemplate.queryForObject("""
                SELECT riders.id
                FROM riders
                JOIN customers ON customers.id = riders.customer_id
                WHERE LOWER(customers.email) = LOWER(?)
                """, Long.class, email);
    }


    /**
     * Accepts the order (a Burger King one) as its merchant and dispatches it, then moves the delivery to
     * {@code riderId} when the dispatcher chose another rider (it takes the least-loaded one). Returns the id of the
     * ASSIGNED delivery.
     */
    Long dispatchTo(PaidOrder order, Long riderId) {
        deliverOrderEvent(order.orderId(), "order.paid", merchantInboxService::handle);
        merchantOrderService.accept(customerIdOf(BURGER_KING_MERCHANT), order.orderId());
        deliverOrderEvent(order.orderId(), "order.accepted", dispatchService::handle);
        Long deliveryId = jdbcTemplate.queryForObject(
                "SELECT id FROM deliveries WHERE order_id = ?", Long.class, order.orderId());
        if (!riderId.equals(jdbcTemplate.queryForObject(
                "SELECT rider_id FROM deliveries WHERE id = ?", Long.class, deliveryId))) {
            dispatchService.reassign(deliveryId, riderId);
        }
        Assertions.assertEquals("ASSIGNED|" + riderId, deliveryState(deliveryId));
        return deliveryId;
    }


    /**
     * The delivery's status and rider, as "status|rider_id".
     */
    String deliveryState(Long deliveryId) {
        return jdbcTemplate.queryForObject(
                "SELECT status || '|' || rider_id FROM deliveries WHERE id = ?", String.class, deliveryId);
    }


    DeliveryTimes deliveryTimes(Long deliveryId) {
        return jdbcTemplate.queryForObject("""
                SELECT assigned_at, picked_up_at, delivered_at, cancelled_at, updated_at
                FROM deliveries
                WHERE id = ?
                """, (row, index) -> new DeliveryTimes(
                row.getObject("assigned_at", LocalDateTime.class),
                row.getObject("picked_up_at", LocalDateTime.class),
                row.getObject("delivered_at", LocalDateTime.class),
                row.getObject("cancelled_at", LocalDateTime.class),
                row.getObject("updated_at", LocalDateTime.class)
        ), deliveryId);
    }


    /**
     * Holds row {@code id} of {@code table} FOR UPDATE, for commands to queue behind in a chosen order (see
     * {@link PostgresRowLocks}). The executor needs a thread for the holder and one per queued command.
     */
    RowLockHolder holdRow(ExecutorService executor, String table, Long id) throws Exception {
        return PostgresRowLocks.holdRowLock(executor, transactionManager, jdbcTemplate, table, id);
    }


    /**
     * The delivery's audit rows, oldest first, as "action|actor_role|actor_customer_id".
     */
    List<String> deliveryAudits(Long deliveryId) {
        return jdbcTemplate.query("""
                SELECT action, actor_role, actor_customer_id
                FROM audit_log
                WHERE target_type = 'DELIVERY'
                  AND target_id = ?
                ORDER BY id
                """, (row, index) -> row.getString("action")
                + "|" + row.getString("actor_role")
                + "|" + row.getObject("actor_customer_id"), String.valueOf(deliveryId));
    }


    boolean dispatchTombstoned(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT cancelled_at IS NOT NULL FROM dispatch_orders WHERE order_id = ?", Boolean.class, orderId);
    }


    List<String> deliveryEventTypes(Long deliveryId) {
        return jdbcTemplate.queryForList("""
                SELECT event_type
                FROM outbox_events
                WHERE aggregate_type = 'DELIVERY'
                  AND aggregate_id = ?
                ORDER BY id
                """, String.class, deliveryId);
    }


    DeliveryEventEnvelope latestDeliveryEvent(Long deliveryId, String eventType) {
        String payload = jdbcTemplate.queryForObject("""
                SELECT payload
                FROM outbox_events
                WHERE aggregate_type = 'DELIVERY'
                  AND aggregate_id = ?
                  AND event_type = ?
                ORDER BY id DESC
                LIMIT 1
                """, String.class, deliveryId, eventType);
        try {
            return objectMapper.readValue(payload, DeliveryEventEnvelope.class);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }



    Long menuItemAt(String restaurantName) {
        return jdbcTemplate.queryForObject("""
                SELECT MIN(menu_items.id)
                FROM menu_items
                JOIN restaurants ON restaurants.id = menu_items.restaurant_id
                WHERE restaurants.name = ?
                """, Long.class, restaurantName);
    }


    List<String> deadlineTypes(Long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT deadline_type FROM order_deadlines WHERE order_id = ? ORDER BY deadline_type",
                String.class,
                orderId
        );
    }


    void makeDeadlinesDue(Long orderId) {
        jdbcTemplate.update(
                "UPDATE order_deadlines SET due_at = ? WHERE order_id = ?",
                LocalDateTime.now().minusMinutes(1),
                orderId
        );
    }


    /**
     * A restaurant with one menu item and no merchant account, as an operator would add one before staffing it.
     */
    String newRestaurantWithoutStaff() {
        String name = "Unstaffed Kitchen " + System.nanoTime();
        jdbcTemplate.update("INSERT INTO restaurants (name, address, phone) VALUES (?, '1 Test St', '(408) 555-0000')", name);
        Long restaurantId = jdbcTemplate.queryForObject("SELECT id FROM restaurants WHERE name = ?", Long.class, name);
        jdbcTemplate.update(
                "INSERT INTO menu_items (restaurant_id, name, price, description) VALUES (?, 'House Noodles', 9.50, 'Test')",
                restaurantId
        );
        return name;
    }


    String cancelReason(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT cancel_reason FROM orders WHERE id = ?", String.class, orderId);
    }



    PaidOrder paidOrderAt(String restaurantName) {
        String email = "fulfilment-" + System.nanoTime() + "@example.com";
        customerService.signUp(email, "demo123", "Fulfilment", "Customer");
        Long customerId = jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
        cartService.addMenuItemToCart(customerId, menuItemAt(restaurantName));
        OrderDto order = cartService.checkout(customerId, "PAID");
        return new PaidOrder(order.id(), customerId, email, order.restaurantId());
    }


    /**
     * A PLACED order (checked out without payment), in the same record as a paid one.
     */
    PaidOrder placedOrderAt(String restaurantName) {
        String email = "fulfilment-placed-" + System.nanoTime() + "@example.com";
        customerService.signUp(email, "demo123", "Placed", "Customer");
        Long customerId = jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
        cartService.addMenuItemToCart(customerId, menuItemAt(restaurantName));
        OrderDto order = cartService.checkout(customerId);
        Assertions.assertEquals("PLACED", order.status());
        return new PaidOrder(order.id(), customerId, email, order.restaurantId());
    }


    void deliverOrderEvent(Long orderId, String eventType, java.util.function.Consumer<OrderEventEnvelope> handler) {
        handler.accept(latestOrderEvent(orderId, eventType));
    }


    /**
     * Feeds the timeout module the merchant.order_received the merchant inbox wrote for the order, as its merchant-topic
     * listener would.
     */
    void deliverMerchantOrderReceived(Long orderId) {
        orderDeadlineService.handle(latestMerchantEvent(orderId));
    }


    MerchantEventEnvelope latestMerchantEvent(Long orderId) {
        String payload = jdbcTemplate.queryForObject("""
                SELECT payload
                FROM outbox_events
                WHERE aggregate_type = 'MERCHANT_ORDER'
                  AND aggregate_id = ?
                  AND event_type = 'merchant.order_received'
                ORDER BY id DESC
                LIMIT 1
                """, String.class, orderId);
        return objectMapper.readValue(payload, MerchantEventEnvelope.class);
    }


    int merchantEventCount(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'MERCHANT_ORDER' AND aggregate_id = ?",
                Integer.class,
                orderId
        );
    }


    OrderEventEnvelope latestOrderEvent(Long orderId, String eventType) {
        String payload = jdbcTemplate.queryForObject("""
                SELECT payload
                FROM outbox_events
                WHERE aggregate_type = 'ORDER'
                  AND aggregate_id = ?
                  AND event_type = ?
                ORDER BY id DESC
                LIMIT 1
                """, String.class, orderId, eventType);
        try {
            return objectMapper.readValue(payload, OrderEventEnvelope.class);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }


    List<String> orderEventTypes(Long orderId) {
        return jdbcTemplate.queryForList("""
                SELECT event_type
                FROM outbox_events
                WHERE aggregate_type = 'ORDER'
                  AND aggregate_id = ?
                ORDER BY id
                """, String.class, orderId);
    }


    String orderStatus(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }


    MockHttpSession login(String email, String password) throws Exception {
        return (MockHttpSession) mockMvc.perform(post("/login")
                        .param("username", email)
                        .param("password", password))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
    }


    private static <T> Callable<T> awaiting(CountDownLatch start, Callable<T> task) {
        return () -> {
            start.await();
            return task.call();
        };
    }


    record PaidOrder(Long orderId, Long customerId, String customerEmail, Long restaurantId) {
    }


    /**
     * A delivery row's timestamps. ADR 0002 I19: a status change sets its own timestamp (and updated_at) to the time of
     * the change and keeps every other one; a reassignment only moves updated_at.
     */
    record DeliveryTimes(
            LocalDateTime assignedAt,
            LocalDateTime pickedUpAt,
            LocalDateTime deliveredAt,
            LocalDateTime cancelledAt,
            LocalDateTime updatedAt
    ) {

        DeliveryTimes pickedUp(LocalDateTime at) {
            return new DeliveryTimes(assignedAt, at, deliveredAt, cancelledAt, at);
        }


        DeliveryTimes delivered(LocalDateTime at) {
            return new DeliveryTimes(assignedAt, pickedUpAt, at, cancelledAt, at);
        }


        DeliveryTimes cancelled(LocalDateTime at) {
            return new DeliveryTimes(assignedAt, pickedUpAt, deliveredAt, at, at);
        }


        /**
         * Only updated_at moved (a reassignment).
         */
        DeliveryTimes touched(LocalDateTime at) {
            return new DeliveryTimes(assignedAt, pickedUpAt, deliveredAt, cancelledAt, at);
        }
    }
}
