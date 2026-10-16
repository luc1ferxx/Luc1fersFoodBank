package com.laioffer.onlineorder.fulfilment;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.fulfilment.dispatch.DispatchService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;


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
                "org.springframework.boot.autoconfigure.session.SessionAutoConfiguration," +
                "org.springframework.boot.autoconfigure.session.RedisSessionAutoConfiguration"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class FulfilmentPostgresIntegrationTests {

    static final String BURGER_KING_MERCHANT = "merchant.burgerking@laifood.com";
    static final String SGD_TOFU_MERCHANT = "merchant.sgdtofu@laifood.com";
    static final String MERCHANT_PASSWORD = "merchant123";

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
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
    private OrderService orderService;

    @Autowired
    private DeliveryCompletionService deliveryCompletionService;

    @Autowired
    private DeliveryNotificationService deliveryNotificationService;

    @Autowired
    private MeterRegistry meterRegistry;


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
        Assertions.assertEquals(
                jdbcTemplate.queryForObject("SELECT MAX(occurred_at) FROM order_status_history WHERE order_id = ?",
                        LocalDateTime.class, order.orderId()),
                jdbcTemplate.queryForObject("SELECT updated_at FROM orders WHERE id = ?", LocalDateTime.class, order.orderId()),
                "updated_at is the time of the last status change"
        );

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
        Assertions.assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM riders
                WHERE active_deliveries <> (
                    SELECT COUNT(*) FROM deliveries
                    WHERE deliveries.rider_id = riders.id AND deliveries.status IN ('ASSIGNED', 'PICKED_UP')
                )
                """, Integer.class), "Every rider's load matches their open deliveries");
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
        Assertions.assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM riders
                WHERE active_deliveries <> (
                    SELECT COUNT(*) FROM deliveries
                    WHERE deliveries.rider_id = riders.id AND deliveries.status IN ('ASSIGNED', 'PICKED_UP')
                )
                """, Integer.class), "Every rider's load matches their open deliveries");
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
        try {
            return objectMapper.readValue(payload, MerchantEventEnvelope.class);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
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
}
