package com.laioffer.onlineorder.fulfilment;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;


import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;


/**
 * Infrastructure and HTTP helpers for the fulfilment tests that run the whole app on PostgreSQL, Redis and Kafka.
 * Each subclass declares its own containers (so each gets its own broker and consumer groups) and its own
 * fulfilment settings.
 *
 * <p>The context is closed after each class ({@link DirtiesContext}): its containers stop with the class, and a cached
 * context would keep its Kafka consumers polling the stopped broker's address. When Docker hands that host port to the
 * next class's broker, those stale consumers join the new broker's consumer groups and take partitions away from the
 * next class's listeners.
 */
@AutoConfigureTestRestTemplate
@DirtiesContext
abstract class FulfilmentContainerTestSupport {

    static final Duration EVENT_WAIT = Duration.ofSeconds(30);


    static PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("onlineorder")
                .withUsername("postgres")
                .withPassword("secret");
    }


    static GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    }


    static KafkaContainer kafka() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));
    }


    static void registerInfrastructure(
            DynamicPropertyRegistry registry,
            PostgreSQLContainer postgres,
            GenericContainer<?> redis,
            KafkaContainer kafka
    ) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registerRedisKafkaAndApp(registry, redis, kafka);
    }


    /**
     * Everything {@link #registerInfrastructure} registers except the database, for a class that brings its own.
     */
    static void registerRedisKafkaAndApp(DynamicPropertyRegistry registry, GenericContainer<?> redis, KafkaContainer kafka) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("app.kafka.enabled", () -> "true");
        registry.add("app.outbox.publisher-enabled", () -> "true");
        registry.add("app.outbox.publish-interval", () -> "200ms");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
        registry.add("app.kafka.listener-backoff", () -> "200ms");
    }


    @LocalServerPort
    private int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;


    @BeforeEach
    void waitForConsumerGroups() {
        // Every module's consumer group must own its partitions before events flow, or the first events would only
        // be read after a rebalance and the timings of the tests would include it.
        await("all listener containers to be assigned their partitions", Duration.ofSeconds(60), () ->
                listenerRegistry.getListenerContainers().stream()
                        .allMatch(container -> assigned(container) >= 3));
    }


    Long payForItemOf(String session, String restaurantName, String idempotencyKey) {
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


    void addToCart(String session, Long menuItemId) {
        ResponseEntity<String> added = restTemplate.postForEntity(
                url("/cart"), new HttpEntity<>(Map.of("menu_id", menuItemId), headers(session)), String.class);
        Assertions.assertEquals(HttpStatus.OK, added.getStatusCode(), added.getBody());
    }


    Long menuItemOf(String restaurantName) {
        return jdbcTemplate.queryForObject("""
                SELECT MIN(menu_items.id)
                FROM menu_items
                JOIN restaurants ON restaurants.id = menu_items.restaurant_id
                WHERE restaurants.name = ?
                """, Long.class, restaurantName);
    }


    String signUpAndLogin() {
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


    String login(String email, String password) {
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


    ResponseEntity<Map> post(String path, String session) {
        return restTemplate.exchange(url(path), HttpMethod.POST, new HttpEntity<>(null, headers(session)), Map.class);
    }


    List<?> getList(String path, String session) {
        ResponseEntity<List> response = restTemplate.exchange(
                url(path), HttpMethod.GET, new HttpEntity<>(headers(session)), List.class);
        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode(), path);
        return response.getBody();
    }


    Map<?, ?> orderOf(String customer, Long orderId) {
        Map<?, ?> order = findById(getList("/orders", customer), "id", orderId);
        Assertions.assertNotNull(order, "order " + orderId);
        return order;
    }


    Object inboxStatus(String merchant, Long orderId) {
        Map<?, ?> inboxOrder = findById(getList("/merchant/orders", merchant), "order_id", orderId);
        return inboxOrder == null ? null : inboxOrder.get("status");
    }


    boolean hasNotification(String customer, Long orderId, String eventType) {
        return notificationOf(customer, orderId, eventType) != null;
    }


    Map<?, ?> notificationOf(String customer, Long orderId, String eventType) {
        for (Object item : getList("/notifications", customer)) {
            Map<?, ?> notification = (Map<?, ?>) item;
            if (eventType.equals(notification.get("event_type"))
                    && orderId.equals(((Number) notification.get("order_id")).longValue())) {
                return notification;
            }
        }
        return null;
    }


    double metricCount(String metric, String tag, String admin) {
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


    List<String> orderEventTypes(Long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM outbox_events WHERE aggregate_type = 'ORDER' AND aggregate_id = ? ORDER BY id",
                String.class,
                orderId
        );
    }


    static Map<?, ?> findById(List<?> items, String idField, Long id) {
        for (Object item : items) {
            Map<?, ?> map = (Map<?, ?>) item;
            Object value = map.get(idField);
            if (value instanceof Number number && number.longValue() == id) {
                return map;
            }
        }
        return null;
    }


    HttpHeaders headers(String session) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, session);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }


    String url(String path) {
        return "http://localhost:" + port + path;
    }


    private static int assigned(MessageListenerContainer container) {
        return container.getAssignedPartitions() == null ? 0 : container.getAssignedPartitions().size();
    }


    static void await(String what, Duration timeout, Supplier<Boolean> condition) {
        awaitValue(what, timeout, () -> Boolean.TRUE.equals(condition.get()) ? Boolean.TRUE : null);
    }


    static <T> T awaitValue(String what, Duration timeout, Supplier<T> value) {
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
