package com.laioffer.onlineorder.datasource;


import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;


import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;


/**
 * Read/write splitting with the session store down: PostgreSQL primary and streaming replica, Spring Session in Redis
 * as deployed, and Redis paused (every command waits for its 500ms timeout). The public catalog never needed the
 * session store (README "What an outage still breaks"); routing, which consults the session for read-your-writes, must
 * not make it need it.
 *
 * <p>A request without a session cookie has no session to look up, so its reads go to the replica at full speed. A
 * request with one (a signed-in user's) fails during such an outage in Spring MVC itself, with or without replicas:
 * the DispatcherServlet looks the session up for flash attributes before any controller runs. Routing's own handling of
 * a failing lookup (the request's reads use the primary after one attempt; a committed write is never turned into an
 * error) is covered by {@link ReadYourWritesTrackerTests}. The catalog cache is off here, so every request loads from
 * the database and is routed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext
class ReadReplicaSessionStoreOutageTests {

    private static final Duration STICKY_WINDOW = Duration.ofSeconds(2);
    private static final Duration REDIS_TIMEOUT = Duration.ofMillis(500);
    private static final int EXTRA_RESTAURANTS = 12;
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST = new ParameterizedTypeReference<>() {
    };

    @Container
    static PostgresReplicationContainers DATABASES = new PostgresReplicationContainers();

    @Container
    static GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);


    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        DATABASES.register(registry);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        registry.add("spring.data.redis.timeout", () -> REDIS_TIMEOUT.toMillis() + "ms");
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.outbox.publisher-enabled", () -> "false");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
        registry.add("app.datasource.replica.sticky-window", () -> STICKY_WINDOW.toMillis() + "ms");
        registry.add("app.datasource.replica.max-lag", () -> "1s");
        registry.add("app.datasource.replica.lag-check-interval", () -> "200ms");
    }


    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ReplicaRouter router;


    @Test
    void withTheSessionStoreHung_catalogReadsWithoutASessionStillReadTheReplica_andNeverWaitForIt() throws Exception {
        insertRestaurants();
        long restaurants = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM restaurants", Long.class);
        await("the replica to have the restaurants", () -> replicaRestaurants() == restaurants);
        await("the replica to be routable", () -> router.replicas().get(0).isRoutable());

        // Healthy Redis: a signed-in user's catalog read finds no read-your-writes window in the session (the login's
        // has passed) and reads the replica.
        String cookie = login("demo@laifood.com", "demo123");
        Thread.sleep(STICKY_WINDOW.toMillis() + 100);
        double replicaReads = reads("replica-1", ReplicaRouter.REASON_REPLICA);
        double sticky = reads(ReplicaRouter.PRIMARY, ReplicaRouter.REASON_STICKY);
        Assertions.assertEquals(HttpStatus.OK, catalog(cookie).getStatusCode());
        Assertions.assertEquals(restaurants + 1.0, reads("replica-1", ReplicaRouter.REASON_REPLICA) - replicaReads,
                "the restaurant list and every menu");
        Assertions.assertEquals(sticky, reads(ReplicaRouter.PRIMARY, ReplicaRouter.REASON_STICKY));

        pauseRedis();
        try {
            replicaReads = reads("replica-1", ReplicaRouter.REASON_REPLICA);
            Instant start = Instant.now();
            ResponseEntity<List<Map<String, Object>>> anonymous = catalog(null);
            Duration elapsed = Duration.between(start, Instant.now());

            Assertions.assertEquals(HttpStatus.OK, anonymous.getStatusCode());
            Assertions.assertEquals(restaurants, anonymous.getBody().size());
            Assertions.assertEquals(restaurants + 1.0, reads("replica-1", ReplicaRouter.REASON_REPLICA) - replicaReads);
            Assertions.assertEquals(sticky, reads(ReplicaRouter.PRIMARY, ReplicaRouter.REASON_STICKY));
            // No Redis command at all: one timed-out lookup alone would take 500ms.
            Assertions.assertTrue(elapsed.compareTo(REDIS_TIMEOUT) < 0, "took " + elapsed.toMillis() + "ms");
        } finally {
            unpauseRedis();
        }

        // Redis answers again: the signed-in session reads the replica again.
        replicaReads = reads("replica-1", ReplicaRouter.REASON_REPLICA);
        Assertions.assertEquals(HttpStatus.OK, catalog(cookie).getStatusCode());
        Assertions.assertTrue(reads("replica-1", ReplicaRouter.REASON_REPLICA) > replicaReads);
    }


    private long replicaRestaurants() {
        try {
            return DATABASES.queryReplica("SELECT COUNT(*) FROM restaurants", Long.class);
        } catch (java.sql.SQLException ex) {
            return -1L;
        }
    }


    private ResponseEntity<List<Map<String, Object>>> catalog(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return restTemplate.exchange(url("/restaurants/menu"), HttpMethod.GET, new HttpEntity<>(headers), JSON_LIST);
    }


    private String login(String email, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", email);
        form.add("password", password);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> response = restTemplate.postForEntity(url("/login"), new HttpEntity<>(form, headers), String.class);
        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";", 2)[0];
        Assertions.assertTrue(cookie.startsWith("SESSION="), cookie);
        return cookie;
    }


    private void insertRestaurants() {
        for (int i = 0; i < EXTRA_RESTAURANTS; i++) {
            String name = "Session Outage Kitchen " + i;
            jdbcTemplate.update(
                    "INSERT INTO restaurants (name, address, image_url, phone) VALUES (?, ?, ?, ?)",
                    name, i + " Timeout Way", "https://example.com/kitchen.png", "(408) 555-0100"
            );
            long id = jdbcTemplate.queryForObject("SELECT id FROM restaurants WHERE name = ?", Long.class, name);
            jdbcTemplate.update(
                    "INSERT INTO menu_items (restaurant_id, name, description, price, image_url) VALUES (?, ?, ?, ?, ?)",
                    id, "Burger", "Routed", new BigDecimal("4.50"), "https://example.com/item.png"
            );
        }
    }


    private void pauseRedis() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }


    private void unpauseRedis() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }


    private double reads(String target, String reason) {
        Counter counter = meterRegistry.find(ReplicaRouter.READS_METRIC).tag("target", target).tag("reason", reason).counter();
        Assertions.assertNotNull(counter, target + "/" + reason);
        return counter.count();
    }


    private String url(String path) {
        return "http://localhost:" + port + path;
    }


    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                Assertions.fail("Timed out after " + WAIT + " waiting for " + what);
            }
            Thread.sleep(50);
        }
    }
}
