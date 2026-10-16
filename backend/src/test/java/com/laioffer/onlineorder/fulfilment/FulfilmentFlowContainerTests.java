package com.laioffer.onlineorder.fulfilment;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;


import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;


/**
 * The event-driven fulfilment flow end to end on PostgreSQL, Redis and Kafka, driven only through the HTTP API:
 * pay -> merchant inbox -> accept -> rider assigned -> picked up -> delivered -> COMPLETED, and the timeout path
 * pay -> no accept -> auto-cancelled (plus an unpaid order cancelled by the payment timeout).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class FulfilmentFlowContainerTests {

    private static final Duration ACCEPT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration EVENT_WAIT = Duration.ofSeconds(30);

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("onlineorder")
            .withUsername("postgres")
            .withPassword("secret");

    @Container
    static GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @Container
    static KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));


    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("app.kafka.enabled", () -> "true");
        registry.add("app.outbox.publisher-enabled", () -> "true");
        registry.add("app.outbox.publish-interval", () -> "200ms");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
        registry.add("app.kafka.listener-backoff", () -> "200ms");
        registry.add("app.fulfilment.accept-timeout", () -> ACCEPT_TIMEOUT.toSeconds() + "s");
        registry.add("app.fulfilment.payment-timeout", () -> ACCEPT_TIMEOUT.toSeconds() + "s");
        registry.add("app.fulfilment.deadline-poller.interval", () -> "250ms");
    }


    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;


    @BeforeEach
    void waitForConsumerGroups() {
        // Every module's consumer group must own its partitions before events flow, or the first events would only
        // be read after a rebalance and the timings below would include it.
        await("all listener containers to be assigned their partitions", Duration.ofSeconds(60), () ->
                listenerRegistry.getListenerContainers().stream()
                        .allMatch(container -> assigned(container) >= 3));
    }


    @Test
    void paidOrder_shouldGoThroughMerchantRiderAndDeliveryToCompleted() {
        String customer = signUpAndLogin();
        String burgerKing = login("merchant.burgerking@laifood.com", "merchant123");
        String sgdTofu = login("merchant.sgdtofu@laifood.com", "merchant123");
        String admin = login("demo@laifood.com", "demo123");

        Long orderId = payForItemOf(customer, "Burger King", "e2e-happy-" + System.nanoTime());

        // order.paid -> merchant inbox (merchant consumer group).
        await("the order to reach the Burger King inbox", EVENT_WAIT, () ->
                findById(getList("/merchant/orders?status=PAID", burgerKing), "order_id", orderId) != null);
        Assertions.assertNull(findById(getList("/merchant/orders", sgdTofu), "order_id", orderId));
        Assertions.assertEquals(HttpStatus.NOT_FOUND, post("/merchant/orders/" + orderId + "/accept", sgdTofu).getStatusCode());
        Assertions.assertEquals(HttpStatus.FORBIDDEN, post("/merchant/orders/" + orderId + "/accept", customer).getStatusCode());

        ResponseEntity<Map> accepted = post("/merchant/orders/" + orderId + "/accept", burgerKing);
        Assertions.assertEquals(HttpStatus.OK, accepted.getStatusCode());
        Assertions.assertEquals("ACCEPTED", accepted.getBody().get("status"));
        ResponseEntity<Map> preparing = post("/merchant/orders/" + orderId + "/preparing", burgerKing);
        Assertions.assertEquals("PREPARING", preparing.getBody().get("status"));

        // order.accepted -> rider assigned (dispatch consumer group) -> delivery.assigned notification.
        Map<?, ?> delivery = awaitValue("a rider to be assigned", EVENT_WAIT, () -> {
            List<?> deliveries = getList("/admin/deliveries?order_id=" + orderId, admin);
            return deliveries.isEmpty() ? null : (Map<?, ?>) deliveries.get(0);
        });
        Assertions.assertEquals("ASSIGNED", delivery.get("status"));
        Assertions.assertNotNull(delivery.get("rider_name"));
        Long deliveryId = ((Number) delivery.get("id")).longValue();
        await("the rider-assigned notification", EVENT_WAIT, () -> hasNotification(customer, orderId, "delivery.assigned"));

        Assertions.assertEquals(HttpStatus.FORBIDDEN,
                post("/admin/deliveries/" + deliveryId + "/picked-up", customer).getStatusCode());
        Assertions.assertEquals("PICKED_UP",
                post("/admin/deliveries/" + deliveryId + "/picked-up", admin).getBody().get("status"));
        Assertions.assertEquals("DELIVERED",
                post("/admin/deliveries/" + deliveryId + "/delivered", admin).getBody().get("status"));

        // delivery.delivered -> order COMPLETED (order-delivery consumer group) -> notifications.
        await("the order to be completed", EVENT_WAIT, () -> "COMPLETED".equals(orderOf(customer, orderId).get("status")));
        await("the delivered and completed notifications", EVENT_WAIT, () ->
                hasNotification(customer, orderId, "delivery.delivered")
                        && hasNotification(customer, orderId, "order.completed")
                        && hasNotification(customer, orderId, "order.accepted"));
        await("the merchant inbox to show the completed order", EVENT_WAIT, () ->
                "COMPLETED".equals(inboxStatus(burgerKing, orderId)));

        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_deadlines WHERE order_id = ?", Integer.class, orderId));
        Assertions.assertEquals(
                List.of("order.paid", "order.accepted", "order.preparing", "order.completed"),
                jdbcTemplate.queryForList(
                        "SELECT event_type FROM outbox_events WHERE aggregate_type = 'ORDER' AND aggregate_id = ? ORDER BY id",
                        String.class,
                        orderId
                )
        );
        Assertions.assertTrue(metricCount("onlineorder.fulfilment.merchant.decisions", "decision:accepted", admin) >= 1);
        Assertions.assertTrue(metricCount("onlineorder.fulfilment.dispatch.assignments", "result:assigned", admin) >= 1);
    }


    @Test
    void ordersNobodyActsOn_shouldBeCancelledByTheAcceptAndPaymentTimeouts() {
        String customer = signUpAndLogin();
        String fashionWok = login("merchant.fashionwok@laifood.com", "merchant123");
        String admin = login("demo@laifood.com", "demo123");

        Long paidOrderId = payForItemOf(customer, "Fashion Wok", "e2e-timeout-" + System.nanoTime());
        addToCart(customer, menuItemOf("SGD Tofu House"));
        HttpHeaders checkoutHeaders = headers(customer);
        checkoutHeaders.set("Idempotency-Key", "e2e-placed-" + System.nanoTime());
        ResponseEntity<Map> placed = restTemplate.exchange(
                url("/cart/checkout"), HttpMethod.POST, new HttpEntity<>(null, checkoutHeaders), Map.class);
        Assertions.assertEquals("PLACED", placed.getBody().get("status"));
        Long placedOrderId = ((Number) placed.getBody().get("id")).longValue();

        await("the paid order to reach the Fashion Wok inbox", EVENT_WAIT, () ->
                findById(getList("/merchant/orders", fashionWok), "order_id", paidOrderId) != null);

        Duration timeoutWait = ACCEPT_TIMEOUT.plus(EVENT_WAIT);
        await("the accept timeout to cancel the paid order", timeoutWait, () ->
                "CANCELLED".equals(orderOf(customer, paidOrderId).get("status")));
        Assertions.assertEquals("MERCHANT_TIMEOUT", orderOf(customer, paidOrderId).get("cancel_reason"));
        await("the payment timeout to cancel the placed order", timeoutWait, () ->
                "CANCELLED".equals(orderOf(customer, placedOrderId).get("status")));
        Assertions.assertEquals("PAYMENT_TIMEOUT", orderOf(customer, placedOrderId).get("cancel_reason"));

        await("the timeout notification", EVENT_WAIT, () -> {
            Map<?, ?> notification = notificationOf(customer, paidOrderId, "order.cancelled");
            return notification != null
                    && "Order cancelled: the restaurant did not respond".equals(notification.get("title"));
        });
        await("the merchant inbox to show the cancelled order", EVENT_WAIT, () ->
                "CANCELLED".equals(inboxStatus(fashionWok, paidOrderId)));
        ResponseEntity<Map> lateAccept = post("/merchant/orders/" + paidOrderId + "/accept", fashionWok);
        Assertions.assertEquals(HttpStatus.CONFLICT, lateAccept.getStatusCode());
        Assertions.assertEquals("ORDER_STATUS_CONFLICT", lateAccept.getBody().get("code"));
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM deliveries WHERE order_id IN (?, ?)", Integer.class, paidOrderId, placedOrderId));
        Assertions.assertTrue(metricCount("onlineorder.fulfilment.merchant.decisions", "decision:timeout", admin) >= 1);
    }


    // ---- HTTP helpers ----

    private Long payForItemOf(String session, String restaurantName, String idempotencyKey) {
        addToCart(session, menuItemOf(restaurantName));
        HttpHeaders headers = headers(session);
        headers.set("Idempotency-Key", idempotencyKey);
        ResponseEntity<Map> paid = restTemplate.postForEntity(
                url("/payments/checkout"),
                new HttpEntity<>(Map.of(
                        "cardholder_name", "Fulfilment Customer",
                        "card_number", "4242 4242 4242 4242",
                        "expiry", "12/30",
                        "cvv", "123"
                ), headers),
                Map.class
        );
        Assertions.assertEquals(HttpStatus.OK, paid.getStatusCode());
        Assertions.assertEquals("PAID", paid.getBody().get("status"));
        return ((Number) paid.getBody().get("id")).longValue();
    }


    private void addToCart(String session, Long menuItemId) {
        ResponseEntity<String> added = restTemplate.postForEntity(
                url("/cart"), new HttpEntity<>(Map.of("menu_id", menuItemId), headers(session)), String.class);
        Assertions.assertEquals(HttpStatus.OK, added.getStatusCode(), added.getBody());
    }


    private Long menuItemOf(String restaurantName) {
        return jdbcTemplate.queryForObject("""
                SELECT MIN(menu_items.id)
                FROM menu_items
                JOIN restaurants ON restaurants.id = menu_items.restaurant_id
                WHERE restaurants.name = ?
                """, Long.class, restaurantName);
    }


    private String signUpAndLogin() {
        String email = "e2e-" + System.nanoTime() + "@example.com";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> signup = restTemplate.postForEntity(
                url("/signup"),
                new HttpEntity<>(Map.of("email", email, "password", "demo123", "first_name", "E2E", "last_name", "Customer"), headers),
                String.class
        );
        Assertions.assertEquals(HttpStatus.CREATED, signup.getStatusCode());
        return login(email, "demo123");
    }


    private String login(String email, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", email);
        form.add("password", password);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> response = restTemplate.postForEntity(url("/login"), new HttpEntity<>(form, headers), String.class);
        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode(), "login of " + email);
        String rawCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        Assertions.assertNotNull(rawCookie, "session cookie for " + email);
        int separator = rawCookie.indexOf(';');
        return separator >= 0 ? rawCookie.substring(0, separator) : rawCookie;
    }


    private ResponseEntity<Map> post(String path, String session) {
        return restTemplate.exchange(url(path), HttpMethod.POST, new HttpEntity<>(null, headers(session)), Map.class);
    }


    private List<?> getList(String path, String session) {
        ResponseEntity<List> response = restTemplate.exchange(
                url(path), HttpMethod.GET, new HttpEntity<>(headers(session)), List.class);
        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode(), path);
        return response.getBody();
    }


    private Map<?, ?> orderOf(String customer, Long orderId) {
        Map<?, ?> order = findById(getList("/orders", customer), "id", orderId);
        Assertions.assertNotNull(order, "order " + orderId);
        return order;
    }


    private Object inboxStatus(String merchant, Long orderId) {
        Map<?, ?> inboxOrder = findById(getList("/merchant/orders", merchant), "order_id", orderId);
        return inboxOrder == null ? null : inboxOrder.get("status");
    }


    private boolean hasNotification(String customer, Long orderId, String eventType) {
        return notificationOf(customer, orderId, eventType) != null;
    }


    private Map<?, ?> notificationOf(String customer, Long orderId, String eventType) {
        for (Object item : getList("/notifications", customer)) {
            Map<?, ?> notification = (Map<?, ?>) item;
            if (eventType.equals(notification.get("event_type"))
                    && orderId.equals(((Number) notification.get("order_id")).longValue())) {
                return notification;
            }
        }
        return null;
    }


    private double metricCount(String metric, String tag, String admin) {
        ResponseEntity<Map> response = restTemplate.exchange(
                url("/actuator/metrics/" + metric + "?tag=" + tag),
                HttpMethod.GET,
                new HttpEntity<>(headers(admin)),
                Map.class
        );
        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode(), metric + " " + tag);
        List<?> measurements = (List<?>) response.getBody().get("measurements");
        return ((Number) ((Map<?, ?>) measurements.get(0)).get("value")).doubleValue();
    }


    private static Map<?, ?> findById(List<?> items, String idField, Long id) {
        for (Object item : items) {
            Map<?, ?> map = (Map<?, ?>) item;
            Object value = map.get(idField);
            if (value instanceof Number number && number.longValue() == id) {
                return map;
            }
        }
        return null;
    }


    private HttpHeaders headers(String session) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, session);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }


    private String url(String path) {
        return "http://localhost:" + port + path;
    }


    private static int assigned(MessageListenerContainer container) {
        return container.getAssignedPartitions() == null ? 0 : container.getAssignedPartitions().size();
    }


    private static void await(String what, Duration timeout, Supplier<Boolean> condition) {
        awaitValue(what, timeout, () -> Boolean.TRUE.equals(condition.get()) ? Boolean.TRUE : null);
    }


    private static <T> T awaitValue(String what, Duration timeout, Supplier<T> value) {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            T current = value.get();
            if (current != null) {
                return current;
            }
            if (Instant.now().isAfter(deadline)) {
                Assertions.fail("Timed out after " + timeout + " waiting for " + what);
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + what, ex);
            }
        }
    }
}
