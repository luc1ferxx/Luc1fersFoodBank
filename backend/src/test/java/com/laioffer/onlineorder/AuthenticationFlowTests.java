package com.laioffer.onlineorder;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@Testcontainers(disabledWithoutDocker = true)
class AuthenticationFlowTests {

    @Container
    static GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Test
    void signup_shouldPersistUserLifecycleDefaults() {
        String email = "signup-" + System.nanoTime() + "@example.com";

        ResponseEntity<Void> response = restTemplate.postForEntity(
                url("/signup"),
                jsonRequest(Map.of(
                        "email", email,
                        "password", "demo123",
                        "first_name", "Ada",
                        "last_name", "Lovelace"
                )),
                Void.class
        );

        Assertions.assertEquals(HttpStatus.CREATED, response.getStatusCode());
        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                        SELECT account_status, failed_login_attempts, locked_until, last_login_at
                        FROM customers
                        WHERE email = ?
                        """,
                email
        );
        Assertions.assertEquals("ACTIVE", row.get("account_status"));
        Assertions.assertEquals(0, ((Number) row.get("failed_login_attempts")).intValue());
        Assertions.assertNull(row.get("locked_until"));
        Assertions.assertNull(row.get("last_login_at"));
    }

    @Test
    void mixedCaseLoginLookup_shouldRetainUserAuthorities() {
        String email = "case-" + System.nanoTime() + "@example.com";

        ResponseEntity<Void> signupResponse = restTemplate.postForEntity(
                url("/signup"),
                jsonRequest(Map.of(
                        "email", email,
                        "password", "demo123",
                        "first_name", "Case",
                        "last_name", "User"
                )),
                Void.class
        );
        Assertions.assertEquals(HttpStatus.CREATED, signupResponse.getStatusCode());

        var userDetails = userDetailsManager.loadUserByUsername(email.toUpperCase());

        Assertions.assertTrue(userDetails.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_USER".equals(authority.getAuthority())));
    }

    @Test
    void repeatedFailedLogins_shouldLockAccount_untilLockExpires_thenSuccessfulLoginShouldResetState() {
        String email = "login-" + System.nanoTime() + "@example.com";
        String password = "demo123";

        ResponseEntity<Void> signupResponse = restTemplate.postForEntity(
                url("/signup"),
                jsonRequest(Map.of(
                        "email", email,
                        "password", password,
                        "first_name", "Grace",
                        "last_name", "Hopper"
                )),
                Void.class
        );
        Assertions.assertEquals(HttpStatus.CREATED, signupResponse.getStatusCode());

        for (int attempt = 0; attempt < 5; attempt++) {
            ResponseEntity<String> loginResponse = login(email, "wrong-password");
            Assertions.assertEquals(HttpStatus.UNAUTHORIZED, loginResponse.getStatusCode());
        }

        Map<String, Object> lockedRow = jdbcTemplate.queryForMap(
                "SELECT failed_login_attempts, locked_until FROM customers WHERE email = ?",
                email
        );
        Assertions.assertEquals(5, ((Number) lockedRow.get("failed_login_attempts")).intValue());
        Timestamp lockedUntil = (Timestamp) lockedRow.get("locked_until");
        Assertions.assertNotNull(lockedUntil);

        ResponseEntity<String> lockedLoginResponse = login(email, password);
        Assertions.assertEquals(HttpStatus.UNAUTHORIZED, lockedLoginResponse.getStatusCode());

        Map<String, Object> stillLockedRow = jdbcTemplate.queryForMap(
                "SELECT failed_login_attempts, locked_until FROM customers WHERE email = ?",
                email
        );
        Assertions.assertEquals(5, ((Number) stillLockedRow.get("failed_login_attempts")).intValue());
        Assertions.assertEquals(lockedUntil, stillLockedRow.get("locked_until"));

        jdbcTemplate.update(
                "UPDATE customers SET locked_until = ? WHERE email = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)),
                email
        );

        ResponseEntity<String> successfulLoginResponse = login(email, password);
        Assertions.assertEquals(HttpStatus.OK, successfulLoginResponse.getStatusCode());

        Map<String, Object> resetRow = jdbcTemplate.queryForMap(
                "SELECT failed_login_attempts, locked_until, last_login_at FROM customers WHERE email = ?",
                email
        );
        Assertions.assertEquals(0, ((Number) resetRow.get("failed_login_attempts")).intValue());
        Assertions.assertNull(resetRow.get("locked_until"));
        Assertions.assertNotNull(resetRow.get("last_login_at"));
    }

    private ResponseEntity<String> login(String email, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", email);
        form.add("password", password);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return restTemplate.postForEntity(url("/login"), new HttpEntity<>(form, headers), String.class);
    }

    private HttpEntity<Map<String, Object>> jsonRequest(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
