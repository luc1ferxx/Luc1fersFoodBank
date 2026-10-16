package com.laioffer.onlineorder.fulfilment;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.fulfilment.dispatch.DispatchService;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantInboxService;
import com.laioffer.onlineorder.fulfilment.notification.DeliveryNotificationService;
import com.laioffer.onlineorder.fulfilment.order.DeliveryCompletionService;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantOrderService;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands;
import com.laioffer.onlineorder.fulfilment.timeout.OrderDeadlineProcessor;
import com.laioffer.onlineorder.fulfilment.timeout.OrderDeadlineService;
import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.CustomerService;
import com.laioffer.onlineorder.service.OrderService;
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
        OrderEventEnvelope cancelled = latestOrderEvent(order.orderId(), "order.cancelled");
        Assertions.assertEquals("MERCHANT_REJECTED: Out of buns", cancelled.data().reason());
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
                        return merchantOrderService.accept(BURGER_KING_MERCHANT, order.orderId()).status();
                    } catch (ConflictException ex) {
                        return "CONFLICT";
                    }
                }));
                Future<Boolean> timeout = executor.submit(awaiting(start, () -> orderLifecycleCommands.cancelIfStillIn(
                        order.orderId(), OrderStatus.PAID, OrderLifecycleCommands.MERCHANT_TIMEOUT).isPresent()));
                start.countDown();

                String acceptResult = accept.get(20, TimeUnit.SECONDS);
                boolean timedOut = timeout.get(20, TimeUnit.SECONDS);
                List<String> events = orderEventTypes(order.orderId());
                if (timedOut) {
                    Assertions.assertEquals("CONFLICT", acceptResult);
                    Assertions.assertEquals("CANCELLED", orderStatus(order.orderId()));
                    Assertions.assertEquals(List.of("order.paid", "order.cancelled"), events);
                } else {
                    Assertions.assertEquals("ACCEPTED", acceptResult);
                    Assertions.assertEquals("ACCEPTED", orderStatus(order.orderId()));
                    Assertions.assertEquals(List.of("order.paid", "order.accepted"), events);
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
        deliverOrderEvent(order.orderId(), "order.paid", merchantInboxService::handle);
        Assertions.assertEquals(List.of("ACCEPT"), deadlineTypes(order.orderId()));
        LocalDateTime dueAt = jdbcTemplate.queryForObject(
                "SELECT due_at FROM order_deadlines WHERE order_id = ?", LocalDateTime.class, order.orderId());
        LocalDateTime expectedDueAt = latestOrderEvent(order.orderId(), "order.paid").occurredAt().plusMinutes(10);
        Assertions.assertTrue(
                java.time.Duration.between(expectedDueAt, dueAt).abs().toMillis() < 1,
                "due_at " + dueAt + " should be the payment time plus the accept timeout, " + expectedDueAt
        );

        orderDeadlineProcessor.fireDueDeadlines();
        Assertions.assertEquals("PAID", orderStatus(order.orderId()), "The deadline is not due yet");

        makeDeadlinesDue(order.orderId());
        orderDeadlineProcessor.fireDueDeadlines();

        Assertions.assertEquals("CANCELLED", orderStatus(order.orderId()));
        Assertions.assertEquals(OrderLifecycleCommands.MERCHANT_TIMEOUT, cancelReason(order.orderId()));
        Assertions.assertTrue(deadlineTypes(order.orderId()).isEmpty());
        OrderEventEnvelope cancelled = latestOrderEvent(order.orderId(), "order.cancelled");
        Assertions.assertEquals(OrderLifecycleCommands.MERCHANT_TIMEOUT, cancelled.data().reason());

        // The merchant inbox follows the cancel event; accepting now is a conflict.
        merchantInboxService.handle(cancelled);
        Assertions.assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "SELECT status FROM merchant_orders WHERE order_id = ?", String.class, order.orderId()));
        Assertions.assertThrows(
                ConflictException.class,
                () -> merchantOrderService.accept("merchant.fashionwok@laifood.com", order.orderId())
        );
    }


    @Test
    void acceptTimeout_afterTheMerchantAccepted_shouldLeaveTheOrderAlone() {
        PaidOrder order = paidOrderAt("Burger King");
        deliverOrderEvent(order.orderId(), "order.paid", orderDeadlineService::handle);
        deliverOrderEvent(order.orderId(), "order.paid", merchantInboxService::handle);
        merchantOrderService.accept(BURGER_KING_MERCHANT, order.orderId());
        // The poller fires before the timeout consumer has seen order.accepted.
        makeDeadlinesDue(order.orderId());

        orderDeadlineProcessor.fireDueDeadlines();

        Assertions.assertEquals("ACCEPTED", orderStatus(order.orderId()));
        Assertions.assertTrue(deadlineTypes(order.orderId()).isEmpty());
        Assertions.assertEquals(List.of("order.paid", "order.accepted"), orderEventTypes(order.orderId()));
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
    }


    @Test
    void concurrentPollers_shouldFireEveryDeadlineExactlyOnce() throws Exception {
        List<Long> orderIds = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            PaidOrder order = paidOrderAt(i % 2 == 0 ? "Burger King" : "Fashion Wok");
            deliverOrderEvent(order.orderId(), "order.paid", orderDeadlineService::handle);
            // A duplicate delivery adds no second deadline.
            deliverOrderEvent(order.orderId(), "order.paid", orderDeadlineService::handle);
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
        }
    }


    @Test
    void acceptedOrder_shouldBeDispatchedPickedUpDeliveredAndCompleted() throws Exception {
        PaidOrder order = paidOrderAt("Burger King");
        deliverOrderEvent(order.orderId(), "order.paid", merchantInboxService::handle);
        merchantOrderService.accept(BURGER_KING_MERCHANT, order.orderId());
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
                order.orderId(), order.restaurantId(), OrderStatus.PAID, OrderStatus.ACCEPTED, null);
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
                    order.orderId(), order.restaurantId(), OrderStatus.PAID, OrderStatus.ACCEPTED, null);
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


    // ---- helpers shared by the fulfilment tests ----

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
