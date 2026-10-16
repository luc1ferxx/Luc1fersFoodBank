package com.laioffer.onlineorder;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;


import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;


/**
 * The whole application with the two-level cache enabled and Redis refusing connections: startup succeeds,
 * catalog reads are served from the database (then L1), and the cache layer does not fail an admin write.
 * <p>
 * Spring Session is excluded, so sessions live in the servlet container's memory and the admin can log in.
 * In the deployed configuration sessions are stored in Redis, and during a real outage every authenticated
 * request, admin writes included, fails in the session filter before it reaches a controller. This test only
 * shows that the after-commit Redis delete and broadcast failing does not turn a committed write into an
 * error, and that the local L1 copy is still evicted. A Redis that hangs instead of refusing connections is
 * covered by {@link CatalogRedisHangIntegrationTests}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                        "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration",
                "spring.cache.type=redis",
                "spring.data.redis.timeout=300ms",
                "spring.data.redis.connect-timeout=200ms",
                "app.cache.delayed-double-delete=0"
        }
)
@ActiveProfiles("h2")
@AutoConfigureTestRestTemplate
class CatalogRedisOutageIntegrationTests {

    private static final int UNREACHABLE_REDIS_PORT = unusedPort();

    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST = new ParameterizedTypeReference<>() {
    };


    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> UNREACHABLE_REDIS_PORT);
    }


    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;


    @Test
    void catalogReadsAndTheCacheSideOfAnAdminWrite_whenRedisRefusesConnections_shouldFallBackToTheDatabase() {
        ResponseEntity<List<Map<String, Object>>> restaurants = getJsonList("/restaurants/menu");
        Assertions.assertEquals(HttpStatus.OK, restaurants.getStatusCode());
        Assertions.assertFalse(restaurants.getBody().isEmpty(), "demo restaurants are loaded from H2");
        long restaurantId = ((Number) restaurants.getBody().get(0).get("id")).longValue();

        ResponseEntity<List<Map<String, Object>>> menu = getJsonList("/restaurant/" + restaurantId + "/menu");
        Assertions.assertEquals(HttpStatus.OK, menu.getStatusCode());
        Assertions.assertNotNull(menu.getHeaders().getETag());
        Map<String, Object> item = menu.getBody().get(0);
        long menuItemId = ((Number) item.get("id")).longValue();
        BigDecimal newPrice = new BigDecimal(item.get("price").toString()).add(new BigDecimal("1.11"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, login());
        ResponseEntity<Map> update = restTemplate.exchange(
                url("/admin/menu-items/" + menuItemId),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("price", newPrice), headers),
                Map.class
        );
        Assertions.assertEquals(HttpStatus.OK, update.getStatusCode(),
                "with sessions held in memory, failing to evict Redis after the commit must not fail the write");

        // L1 was evicted locally even though the Redis delete and the broadcast failed.
        Map<String, Object> updated = getJsonList("/restaurant/" + restaurantId + "/menu").getBody().stream()
                .filter(entry -> ((Number) entry.get("id")).longValue() == menuItemId)
                .findFirst()
                .orElseThrow();
        Assertions.assertEquals(0, newPrice.compareTo(new BigDecimal(updated.get("price").toString())));
    }


    private ResponseEntity<List<Map<String, Object>>> getJsonList(String path) {
        return restTemplate.exchange(url(path), HttpMethod.GET, null, JSON_LIST);
    }


    private String login() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", "demo@laifood.com");
        form.add("password", "demo123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> response = restTemplate.postForEntity(url("/login"), new HttpEntity<>(form, headers), String.class);
        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        return response.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";", 2)[0];
    }


    private String url(String path) {
        return "http://localhost:" + port + path;
    }


    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
