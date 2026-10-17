package com.laioffer.onlineorder;


import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;


import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestRestTemplate
// Closed after the class, so its Kafka consumers cannot join the groups of a later class's broker on a reused port.
@DirtiesContext
class ContainerizedOrderFlowTests {

    @Container
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("onlineorder")
            .withUsername("postgres")
            .withPassword("secret");

    @Container
    static GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @Container
    static KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private MeterRegistry meterRegistry;


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
        registry.add("app.outbox.publish-interval", () -> "500ms");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.kafka.listener-backoff", () -> "200ms");
        registry.add("app.kafka.listener-retries", () -> "1");
        registry.add("spring.kafka.consumer.group-id", () -> "onlineorder-it");
    }


    @Test
    void fullOrderFlow_shouldWorkAcrossPostgresRedisKafka() {
        ResponseEntity<Map> health = restTemplate.getForEntity(url("/actuator/health"), Map.class);
        Assertions.assertEquals(HttpStatus.OK, health.getStatusCode());
        Assertions.assertEquals("UP", health.getBody().get("status"));

        ResponseEntity<String> loginResponse = login();
        String sessionCookie = extractSessionCookie(loginResponse);
        Assertions.assertNotNull(sessionCookie);
        Assertions.assertNotNull(loginResponse.getHeaders().getFirst("X-Trace-Id"));

        HttpHeaders authHeaders = jsonHeaders(sessionCookie);
        restTemplate.postForEntity(
                url("/cart"),
                new HttpEntity<>(Map.of("menu_id", 1), authHeaders),
                Void.class
        );

        HttpHeaders checkoutHeaders = jsonHeaders(sessionCookie);
        checkoutHeaders.set("Idempotency-Key", "it-order-1");
        ResponseEntity<Map> checkoutResponse = restTemplate.postForEntity(
                url("/payments/checkout"),
                new HttpEntity<>(Map.of(
                        "cardholder_name", "Demo User",
                        "card_number", "4242 4242 4242 4242",
                        "expiry", "12/30",
                        "cvv", "123"
                ), checkoutHeaders),
                Map.class
        );
        Assertions.assertEquals(HttpStatus.OK, checkoutResponse.getStatusCode());
        Assertions.assertEquals("PAID", checkoutResponse.getBody().get("status"));
        Long orderId = ((Number) checkoutResponse.getBody().get("id")).longValue();

        ResponseEntity<String> metricsResponse = restTemplate.exchange(
                url("/actuator/metrics/jvm.memory.used"),
                HttpMethod.GET,
                new HttpEntity<>(headersWithCookie(sessionCookie)),
                String.class
        );
        Assertions.assertEquals(HttpStatus.OK, metricsResponse.getStatusCode());
        Assertions.assertTrue(metricsResponse.getBody().contains("measurements"));

        HttpHeaders statusHeaders = jsonHeaders(sessionCookie);
        statusHeaders.set("Idempotency-Key", "it-status-1");
        ResponseEntity<Map> transitionResponse = restTemplate.exchange(
                url("/orders/" + orderId + "/status"),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "ACCEPTED"), statusHeaders),
                Map.class
        );
        Assertions.assertEquals(HttpStatus.OK, transitionResponse.getStatusCode());
        Assertions.assertEquals("ACCEPTED", transitionResponse.getBody().get("status"));

        await(Duration.ofSeconds(15), () -> {
            ResponseEntity<List> notificationsResponse = restTemplate.exchange(
                    url("/notifications"),
                    HttpMethod.GET,
                    new HttpEntity<>(headersWithCookie(sessionCookie)),
                    List.class
            );
            if (notificationsResponse.getStatusCode() != HttpStatus.OK || notificationsResponse.getBody() == null) {
                return false;
            }
            for (Object notification : notificationsResponse.getBody()) {
                if (notification instanceof Map<?, ?> map
                        && "order.accepted".equals(map.get("event_type"))) {
                    return true;
                }
            }
            return false;
        });
    }


    /**
     * A record that breaks its contract fails the same way on every attempt, so each of the four consumer groups on the
     * order topic dead-letters it on its first attempt (listener-retries is 1 here) and the stored dead letter carries
     * the violations.
     */
    @Test
    void schemaInvalidEvent_shouldBeDeadLetteredByEveryGroupWithoutRetriesAndKeepTheViolation() throws Exception {
        String eventId = "invalid-" + System.nanoTime();
        String payload = """
                {"event_id":"%s","event_version":1,"event_type":"order.paid","aggregate_type":"ORDER",
                 "aggregate_id":424242,"sequence":1,"occurred_at":"2026-04-25T12:00:00",
                 "data":{"status":"PAID","total_price":10.59,"created_at":"2026-04-25T12:00:00","items":[]}}
                """.formatted(eventId);

        kafkaTemplate.send("order-events", "424242", payload).get(30, TimeUnit.SECONDS);

        await(Duration.ofSeconds(60), () -> consumerViolations() >= 4 && !jdbcTemplate.queryForList(
                "SELECT id FROM dead_letter_events WHERE event_id = ?", Long.class, eventId).isEmpty());
        String errorMessage = jdbcTemplate.queryForObject(
                "SELECT error_message FROM dead_letter_events WHERE event_id = ?", String.class, eventId);
        Assertions.assertTrue(errorMessage.contains("violates its event schema"), errorMessage);
        Assertions.assertTrue(errorMessage.contains("customer_id"), errorMessage);
        // A retried record would be read (and counted) again after the 200ms listener backoff.
        Thread.sleep(1500);
        Assertions.assertEquals(4.0, consumerViolations());
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM merchant_orders WHERE order_id = 424242", Integer.class));
    }


    private double consumerViolations() {
        return meterRegistry.find("onlineorder.events.schema_violations")
                .tags("side", "consumer", "topic", "order-events", "event_type", "order.paid")
                .counters()
                .stream()
                .mapToDouble(Counter::count)
                .sum();
    }


    /**
     * POST /dead-letters/{id}/replay exists only with Kafka on: the replay outcome is audited with the admin who is
     * signed in to the request, not with an actor a test hands to the service.
     */
    @Test
    void deadLetterReplay_shouldAuditTheOutcomeWithTheSignedInAdmin() throws Exception {
        String topic = "audit-replay-it";
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
        LocalDateTime now = LocalDateTime.now();
        String eventId = "audit-replay-" + System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO dead_letter_events (source_topic, dead_letter_topic, message_key, payload, error_message,
                                                replay_status, replay_attempts, event_id, created_at, updated_at)
                VALUES (?, ?, 'audit-key', '{"replayed": true}', 'listener failed', 'PENDING', 0, ?, ?, ?)
                """, topic, topic + ".dlt", eventId, now, now);
        long deadLetterId = jdbcTemplate.queryForObject(
                "SELECT id FROM dead_letter_events WHERE event_id = ?", Long.class, eventId);
        String sessionCookie = extractSessionCookie(login());

        ResponseEntity<Map> replay = restTemplate.exchange(
                url("/dead-letters/" + deadLetterId + "/replay"),
                HttpMethod.POST,
                new HttpEntity<>(headersWithCookie(sessionCookie)),
                Map.class
        );

        Assertions.assertEquals(HttpStatus.OK, replay.getStatusCode());
        Assertions.assertEquals("REPLAYED", replay.getBody().get("replay_status"));
        List<Map<String, Object>> entries = jdbcTemplate.queryForList("""
                SELECT actor_customer_id, actor_role, action, details
                FROM audit_log
                WHERE target_type = 'DEAD_LETTER_EVENT' AND target_id = ?
                """, String.valueOf(deadLetterId));
        Assertions.assertEquals(1, entries.size());
        Map<String, Object> entry = entries.get(0);
        Assertions.assertEquals(
                jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = 'demo@laifood.com'", Long.class),
                ((Number) entry.get("actor_customer_id")).longValue()
        );
        Assertions.assertEquals("ROLE_ADMIN", entry.get("actor_role"));
        Assertions.assertEquals("dead_letter.replay", entry.get("action"));
        JsonNode details = objectMapper.readTree((String) entry.get("details"));
        Assertions.assertEquals("REPLAYED", details.get("outcome").asString());
        Assertions.assertEquals(1, details.get("attempt").asInt());
        Assertions.assertEquals(topic, details.get("source_topic").asString());
    }


    private ResponseEntity<String> login() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", "demo@laifood.com");
        form.add("password", "demo123");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return restTemplate.postForEntity(url("/login"), new HttpEntity<>(form, headers), String.class);
    }


    private HttpHeaders jsonHeaders(String sessionCookie) {
        HttpHeaders headers = headersWithCookie(sessionCookie);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }


    private HttpHeaders headersWithCookie(String sessionCookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie);
        return headers;
    }


    private String extractSessionCookie(ResponseEntity<String> response) {
        String rawCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        if (rawCookie == null) {
            return null;
        }
        int separator = rawCookie.indexOf(';');
        return separator >= 0 ? rawCookie.substring(0, separator) : rawCookie;
    }


    private String url(String path) {
        return "http://localhost:" + port + path;
    }


    private void await(Duration timeout, Condition condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.matches()) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for condition", ex);
            }
        }
        Assertions.fail("Condition was not satisfied within " + timeout);
    }


    @FunctionalInterface
    private interface Condition {
        boolean matches();
    }
}
