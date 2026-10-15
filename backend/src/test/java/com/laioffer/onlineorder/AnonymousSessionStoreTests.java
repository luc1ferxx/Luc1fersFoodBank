package com.laioffer.onlineorder;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;


import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Runs the real servlet container and Spring Session filter over an in-memory session store instead of
 * Redis, so a session created by an anonymous request, including one created during the /error dispatch,
 * shows up as a stored session and a SESSION cookie. MockMvc never writes a session cookie, so this class
 * is where the no-Set-Cookie checks can actually fail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class AnonymousSessionStoreTests {

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.outbox.publisher-enabled", () -> "false");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;


    @BeforeEach
    void clearSessions() {
        SESSIONS.clear();
    }


    @ParameterizedTest
    @ValueSource(strings = {"/cart", "/orders", "/me", "/notifications", "/actuator/metrics", "/unknown-spa-route"})
    void anonymousGet_toProtectedPath_shouldNotStoreSessionOrIssueSessionCookie(String path) {
        ResponseEntity<String> response = restTemplate.exchange(
                url(path),
                HttpMethod.GET,
                new HttpEntity<>(browserHeaders()),
                String.class
        );

        Assertions.assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNoSessionCreated(response);
    }


    @Test
    void anonymousPost_toProtectedEndpoint_shouldNotStoreSessionOrIssueSessionCookie() {
        HttpHeaders headers = browserHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/cart"),
                HttpMethod.POST,
                new HttpEntity<>("{\"menu_id\":1}", headers),
                String.class
        );

        Assertions.assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNoSessionCreated(response);
    }


    @Test
    void anonymousGet_toPublicEndpoint_shouldNotStoreSessionOrIssueSessionCookie() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/restaurants/menu"),
                HttpMethod.GET,
                new HttpEntity<>(browserHeaders()),
                String.class
        );

        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNoSessionCreated(response);
    }


    @Test
    void anonymousLogout_shouldNotStoreSessionOrIssueSessionCookie() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/logout"),
                HttpMethod.POST,
                new HttpEntity<>(browserHeaders()),
                String.class
        );

        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNoSessionCreated(response);
    }


    @Test
    void anonymousRequest_thatEndsInErrorDispatch_shouldNotStoreSessionOrIssueSessionCookie() {
        HttpHeaders headers = browserHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/signup"),
                HttpMethod.POST,
                new HttpEntity<>("not json", headers),
                String.class
        );

        Assertions.assertTrue(response.getStatusCode().is4xxClientError());
        assertNoSessionCreated(response);
    }


    @Test
    void failedLogin_shouldNotStoreSessionOrIssueSessionCookie() {
        ResponseEntity<String> response = login("missing-" + System.nanoTime() + "@example.com", "wrong-password");

        Assertions.assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNoSessionCreated(response);
    }


    @Test
    void successfulLogin_shouldStoreOneSessionThatAuthorizesTheNextRequest() {
        ResponseEntity<String> loginResponse = login("demo@laifood.com", "demo123");

        Assertions.assertEquals(HttpStatus.OK, loginResponse.getStatusCode());
        Assertions.assertEquals(1, SESSIONS.size());
        String sessionCookie = extractSessionCookie(loginResponse);
        Assertions.assertNotNull(sessionCookie);

        HttpHeaders headers = browserHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie);
        ResponseEntity<String> cartResponse = restTemplate.exchange(
                url("/cart"),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        Assertions.assertEquals(HttpStatus.OK, cartResponse.getStatusCode());
        Assertions.assertEquals(1, SESSIONS.size());
    }


    private ResponseEntity<String> login(String email, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", email);
        form.add("password", password);

        HttpHeaders headers = browserHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return restTemplate.postForEntity(url("/login"), new HttpEntity<>(form, headers), String.class);
    }


    /**
     * Sends the Accept header a browser fetch() sends by default; an Accept of application/json alone
     * would hide the saved-request session because the request cache skips JSON-only requests.
     */
    private HttpHeaders browserHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.ALL));
        return headers;
    }


    /**
     * A cookie-clearing Set-Cookie with an empty value, as logout sends, is not a session cookie.
     */
    private void assertNoSessionCreated(ResponseEntity<String> response) {
        Assertions.assertTrue(SESSIONS.isEmpty(), "Unexpected stored sessions: " + SESSIONS.keySet());
        Assertions.assertNull(extractSessionCookie(response));
    }


    private String extractSessionCookie(ResponseEntity<String> response) {
        List<String> setCookies = response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);
        return setCookies.stream()
                .map(setCookie -> setCookie.split(";", 2)[0])
                .filter(cookie -> cookie.matches("^(SESSION|JSESSIONID)=.+"))
                .findFirst()
                .orElse(null);
    }


    private String url(String path) {
        return "http://localhost:" + port + path;
    }


    @TestConfiguration
    @EnableSpringHttpSession
    static class InMemorySessionStoreConfig {

        @Bean
        MapSessionRepository sessionRepository() {
            return new MapSessionRepository(SESSIONS);
        }
    }
}
