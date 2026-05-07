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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.LocalDateTime;
import java.util.Map;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.security.rate-limit.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.session.SessionAutoConfiguration",
                "spring.cache.type=none"
        }
)
@ActiveProfiles("h2")
class OutboxAdminControllerIntegrationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    @Test
    void failedOutboxEndpoint_shouldRequireAdminRole() {
        ResponseEntity<String> anonymousResponse = restTemplate.getForEntity(
                url("/admin/outbox/events/failed"),
                String.class
        );
        Assertions.assertEquals(HttpStatus.UNAUTHORIZED, anonymousResponse.getStatusCode());

        String userCookie = signupAndLoginUser();
        ResponseEntity<String> userResponse = restTemplate.exchange(
                url("/admin/outbox/events/failed"),
                HttpMethod.GET,
                new HttpEntity<>(headersWithCookie(userCookie)),
                String.class
        );
        Assertions.assertEquals(HttpStatus.FORBIDDEN, userResponse.getStatusCode());

        String adminCookie = login("demo@laifood.com", "demo123");
        ResponseEntity<String> adminResponse = restTemplate.exchange(
                url("/admin/outbox/events/failed"),
                HttpMethod.GET,
                new HttpEntity<>(headersWithCookie(adminCookie)),
                String.class
        );
        Assertions.assertEquals(HttpStatus.OK, adminResponse.getStatusCode());
    }


    @Test
    void retryFailedEvent_shouldOnlyRetryFailedEventsAndClearRetryState() {
        String adminCookie = login("demo@laifood.com", "demo123");
        Long failedEventId = insertOutboxEvent("FAILED", 10, "Kafka unavailable", LocalDateTime.now());

        ResponseEntity<Map> retryResponse = restTemplate.exchange(
                url("/admin/outbox/events/" + failedEventId + "/retry"),
                HttpMethod.POST,
                new HttpEntity<>(headersWithCookie(adminCookie)),
                Map.class
        );

        Assertions.assertEquals(HttpStatus.OK, retryResponse.getStatusCode());
        Assertions.assertEquals("PENDING", retryResponse.getBody().get("status"));
        Assertions.assertEquals(0, ((Number) retryResponse.getBody().get("attempts")).intValue());
        Assertions.assertNull(retryResponse.getBody().get("last_error"));
        Assertions.assertNull(retryResponse.getBody().get("published_at"));

        Map<String, Object> retriedRow = jdbcTemplate.queryForMap(
                "SELECT status, attempts, last_error, published_at FROM outbox_events WHERE id = ?",
                failedEventId
        );
        Assertions.assertEquals("PENDING", retriedRow.get("status"));
        Assertions.assertEquals(0, ((Number) retriedRow.get("attempts")).intValue());
        Assertions.assertNull(retriedRow.get("last_error"));
        Assertions.assertNull(retriedRow.get("published_at"));

        Long processingEventId = insertOutboxEvent("PROCESSING", 2, null, null);
        ResponseEntity<String> processingRetryResponse = restTemplate.exchange(
                url("/admin/outbox/events/" + processingEventId + "/retry"),
                HttpMethod.POST,
                new HttpEntity<>(headersWithCookie(adminCookie)),
                String.class
        );

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, processingRetryResponse.getStatusCode());
        Map<String, Object> processingRow = jdbcTemplate.queryForMap(
                "SELECT status, attempts FROM outbox_events WHERE id = ?",
                processingEventId
        );
        Assertions.assertEquals("PROCESSING", processingRow.get("status"));
        Assertions.assertEquals(2, ((Number) processingRow.get("attempts")).intValue());
    }


    private Long insertOutboxEvent(String status, int attempts, String lastError, LocalDateTime publishedAt) {
        String uniqueId = "test-outbox-" + status.toLowerCase() + "-" + System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                        INSERT INTO outbox_events (
                            aggregate_type, aggregate_id, event_id, topic, event_key, event_type, payload,
                            status, attempts, last_error, created_at, updated_at, published_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                "ORDER",
                100L,
                uniqueId,
                "order-events",
                "100",
                "order.created",
                "{}",
                status,
                attempts,
                lastError,
                now,
                now,
                publishedAt
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM outbox_events WHERE event_id = ?",
                Long.class,
                uniqueId
        );
    }


    private String signupAndLoginUser() {
        String email = "outbox-user-" + System.nanoTime() + "@example.com";
        ResponseEntity<Void> signupResponse = restTemplate.postForEntity(
                url("/signup"),
                jsonRequest(Map.of(
                        "email", email,
                        "password", "demo123",
                        "first_name", "Outbox",
                        "last_name", "User"
                )),
                Void.class
        );
        Assertions.assertEquals(HttpStatus.CREATED, signupResponse.getStatusCode());
        return login(email, "demo123");
    }


    private String login(String email, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", email);
        form.add("password", password);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/login"),
                new HttpEntity<>(form, headers),
                String.class
        );
        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        String sessionCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        Assertions.assertNotNull(sessionCookie);
        return sessionCookie.split(";", 2)[0];
    }


    private HttpEntity<Map<String, Object>> jsonRequest(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }


    private HttpHeaders headersWithCookie(String sessionCookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie);
        return headers;
    }


    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
