package com.laioffer.onlineorder;


import org.junit.jupiter.api.Assertions;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;


import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ContainerizedOrderFlowTests {

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

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;


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
