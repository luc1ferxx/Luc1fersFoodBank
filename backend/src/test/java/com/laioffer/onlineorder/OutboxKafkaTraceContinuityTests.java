package com.laioffer.onlineorder;


import com.laioffer.onlineorder.messaging.OutboxEventPublisher;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;


/**
 * One trace from the checkout request to the Kafka consumer, across the outbox table: the HTTP request's trace
 * context is stored with the outbox row, the publisher's relay span continues it, the KafkaTemplate send span is
 * its child and puts the traceparent on the record, and the listener's consumer span continues that.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.tracing.sampling.probability=1.0",
                "management.tracing.opentelemetry.export.schedule-delay=100ms"
        }
)
// Spring Boot tests switch tracing propagation off unless asked for. OTLP export stays off
// (management.otlp.tracing.export.enabled defaults to false); spans go to the in-memory exporter below.
@AutoConfigureObservability(metrics = false)
@Testcontainers(disabledWithoutDocker = true)
class OutboxKafkaTraceContinuityTests {

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

    private static final String ORDER_TOPIC = "order-events";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private InMemorySpanExporter spanExporter;


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
        registry.add("app.kafka.order-topic", () -> ORDER_TOPIC);
        registry.add("app.outbox.publisher-enabled", () -> "true");
        registry.add("app.outbox.publish-interval", () -> "200ms");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
        registry.add("spring.kafka.consumer.group-id", () -> "onlineorder-trace-it");
    }


    @TestConfiguration
    static class SpanCapture {

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }


    @Test
    void checkoutTrace_shouldContinueThroughOutboxPublisherAndKafkaConsumer() {
        String sessionCookie = login();
        HttpHeaders cartHeaders = jsonHeaders(sessionCookie);
        ResponseEntity<String> cartResponse = restTemplate.postForEntity(
                url("/cart"),
                new HttpEntity<>(Map.of("menu_id", 1), cartHeaders),
                String.class
        );
        Assertions.assertTrue(cartResponse.getStatusCode().is2xxSuccessful(), cartResponse::toString);

        String traceId = HexFormat.of().formatHex(randomBytes(16));
        HttpHeaders checkoutHeaders = jsonHeaders(sessionCookie);
        checkoutHeaders.set("Idempotency-Key", "trace-it-" + traceId);
        checkoutHeaders.set("traceparent", "00-" + traceId + "-" + HexFormat.of().formatHex(randomBytes(8)) + "-01");
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
        // W3C traceparent in, the same OpenTelemetry trace id out.
        Assertions.assertEquals(traceId, checkoutResponse.getHeaders().getFirst("X-Trace-Id"));
        long orderId = ((Number) checkoutResponse.getBody().get("id")).longValue();

        List<String> storedTraceParents = jdbcTemplate.queryForList(
                "SELECT trace_parent FROM outbox_events WHERE aggregate_type = 'ORDER' AND aggregate_id = ?",
                String.class,
                orderId
        );
        Assertions.assertFalse(storedTraceParents.isEmpty());
        storedTraceParents.forEach(traceParent ->
                Assertions.assertTrue(
                        traceParent != null && traceParent.startsWith("00-" + traceId + "-"),
                        () -> "outbox row should carry the request trace, got " + traceParent
                ));

        SpanData consumerSpan = awaitSpan(Duration.ofSeconds(30), traceId, SpanKind.CONSUMER, ORDER_TOPIC + " receive");
        SpanData producerSpan = findSpan(traceId, SpanKind.PRODUCER, ORDER_TOPIC + " send")
                .orElseThrow(() -> new AssertionError("No Kafka send span in trace " + traceId + ": " + spans(traceId)));
        SpanData relaySpan = findSpan(traceId, SpanKind.CONSUMER, ORDER_TOPIC + " outbox relay")
                .orElseThrow(() -> new AssertionError("No outbox relay span in trace " + traceId + ": " + spans(traceId)));

        // The consumed record belongs to the originating request's trace.
        Assertions.assertEquals(traceId, consumerSpan.getTraceId());
        Assertions.assertEquals(producerSpan.getSpanId(), consumerSpan.getParentSpanId());
        Assertions.assertEquals(relaySpan.getSpanId(), producerSpan.getParentSpanId());
        Assertions.assertTrue(
                spans(traceId).stream().anyMatch(span -> span.getSpanId().equals(relaySpan.getParentSpanId())),
                () -> "The relay span's parent should be a span of the checkout request: " + spans(traceId)
        );

        // The publisher's scheduled runs (every 200ms here) are observed but their empty spans are not exported.
        String publisherClass = OutboxEventPublisher.class.getName();
        Assertions.assertTrue(
                spanExporter.getFinishedSpanItems().stream().noneMatch(span ->
                        publisherClass.equals(span.getAttributes().get(AttributeKey.stringKey("code.namespace")))),
                "scheduled outbox poller spans should not be exported"
        );
    }


    private SpanData awaitSpan(Duration timeout, String traceId, SpanKind kind, String name) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Optional<SpanData> span = findSpan(traceId, kind, name);
            if (span.isPresent()) {
                return span.get();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for span " + name, ex);
            }
        }
        throw new AssertionError("No " + kind + " span '" + name + "' in trace " + traceId + " within " + timeout
                + "; spans in trace: " + spans(traceId));
    }


    private Optional<SpanData> findSpan(String traceId, SpanKind kind, String name) {
        return spans(traceId).stream()
                .filter(span -> span.getKind() == kind && span.getName().equals(name))
                .findFirst();
    }


    private List<SpanData> spans(String traceId) {
        return spanExporter.getFinishedSpanItems().stream()
                .filter(span -> span.getTraceId().equals(traceId))
                .toList();
    }


    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        do {
            ThreadLocalRandom.current().nextBytes(bytes);
        } while (isAllZero(bytes));
        return bytes;
    }


    private static boolean isAllZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }


    private String login() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", "demo@laifood.com");
        form.add("password", "demo123");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> response = restTemplate.postForEntity(url("/login"), new HttpEntity<>(form, headers), String.class);
        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        String rawCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        Assertions.assertNotNull(rawCookie);
        return rawCookie.split(";", 2)[0];
    }


    private HttpHeaders jsonHeaders(String sessionCookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }


    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
