package com.laioffer.onlineorder;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
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
                "spring.autoconfigure.exclude=org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                        "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration",
                "spring.cache.type=none"
        }
)
@ActiveProfiles("h2")
@AutoConfigureTestRestTemplate
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


    @Test
    void abandonFailedEvent_shouldRequireAdminAReasonAndAFailedEventAndBeAudited() {
        Long failedEventId = insertOutboxEvent("FAILED", 10, "RecordTooLargeException", null);
        HttpHeaders userHeaders = jsonHeadersWithCookie(signupAndLoginUser());
        ResponseEntity<String> userResponse = restTemplate.exchange(
                url("/admin/outbox/events/" + failedEventId + "/abandon"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "too large"), userHeaders),
                String.class
        );
        Assertions.assertEquals(HttpStatus.FORBIDDEN, userResponse.getStatusCode());

        HttpHeaders adminHeaders = jsonHeadersWithCookie(login("demo@laifood.com", "demo123"));
        ResponseEntity<String> withoutReason = restTemplate.exchange(
                url("/admin/outbox/events/" + failedEventId + "/abandon"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", " "), adminHeaders),
                String.class
        );
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, withoutReason.getStatusCode());
        Assertions.assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?", String.class, failedEventId));

        ResponseEntity<Map> abandonResponse = restTemplate.exchange(
                url("/admin/outbox/events/" + failedEventId + "/abandon"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "  Record over the broker limit; order cancelled by hand  "), adminHeaders),
                Map.class
        );
        Assertions.assertEquals(HttpStatus.OK, abandonResponse.getStatusCode());
        Assertions.assertEquals("ABANDONED", abandonResponse.getBody().get("status"));
        Assertions.assertEquals("RecordTooLargeException", abandonResponse.getBody().get("last_error"));
        Assertions.assertNull(abandonResponse.getBody().get("published_at"));
        Assertions.assertEquals(
                "outbox_event.abandon|ROLE_ADMIN",
                jdbcTemplate.queryForObject("""
                        SELECT action || '|' || actor_role FROM audit_log
                        WHERE target_type = 'OUTBOX_EVENT' AND target_id = ?
                        """, String.class, String.valueOf(failedEventId))
        );
        Assertions.assertTrue(jdbcTemplate.queryForObject("""
                SELECT details FROM audit_log WHERE target_type = 'OUTBOX_EVENT' AND target_id = ?
                """, String.class, String.valueOf(failedEventId))
                .contains("\"reason\":\"Record over the broker limit; order cancelled by hand\""));

        ResponseEntity<String> again = restTemplate.exchange(
                url("/admin/outbox/events/" + failedEventId + "/abandon"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "twice"), adminHeaders),
                String.class
        );
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, again.getStatusCode());
        ResponseEntity<String> missing = restTemplate.exchange(
                url("/admin/outbox/events/999999999/abandon"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "gone"), adminHeaders),
                String.class
        );
        Assertions.assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE target_type = 'OUTBOX_EVENT' AND target_id = ?",
                Integer.class, String.valueOf(failedEventId)), "refused attempts are not audited");
    }


    private HttpHeaders jsonHeadersWithCookie(String sessionCookie) {
        HttpHeaders headers = headersWithCookie(sessionCookie);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
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
