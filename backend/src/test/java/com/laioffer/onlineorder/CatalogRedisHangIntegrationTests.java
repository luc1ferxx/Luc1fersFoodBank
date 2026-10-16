package com.laioffer.onlineorder;


import com.laioffer.onlineorder.cache.CatalogCaches;
import com.laioffer.onlineorder.cache.RemoteCacheCircuitBreaker;
import com.laioffer.onlineorder.cache.TwoLevelCache;
import com.laioffer.onlineorder.cache.TwoLevelCacheManager;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;


import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;


/**
 * The whole application (h2 profile, Spring Session in Redis as deployed) with Redis hung instead of refusing
 * connections: the Redis container is paused, so every command waits for {@code spring.data.redis.timeout}
 * (500ms) and then times out. Anonymous catalog reads never touch the session store, so they must stay within
 * a latency budget instead of paying the timeout for each of the 40+ cached entries a catalog response is
 * composed of, and L1 must warm up so the next request does not touch Redis at all.
 * <p>
 * Before the Redis circuit breaker, a cold {@code GET /restaurants/menu} here took about (restaurants + 1) x 1s
 * (a timed-out read and a timed-out write-back per entry), roughly 44s, longer than the 30s L1 TTL.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cache.type=redis",
                "spring.data.redis.timeout=500ms",
                "app.cache.l2.circuit-breaker.open-duration=2s"
        }
)
@ActiveProfiles("h2")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext
class CatalogRedisHangIntegrationTests {

    private static final int EXTRA_RESTAURANTS = 40;
    /** One 500ms command timeout plus database loads, with room for a busy CI machine. */
    private static final Duration COLD_CATALOG_BUDGET = Duration.ofMillis(2_500);
    private static final Duration COLD_MENU_BUDGET = Duration.ofMillis(1_500);

    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST = new ParameterizedTypeReference<>() {
    };

    @Container
    static GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);


    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }


    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TwoLevelCacheManager cacheManager;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MeterRegistry meterRegistry;


    @Test
    void anonymousCatalogReads_whenRedisHangs_shouldPayAtMostOneCommandTimeoutAndWarmL1() throws Exception {
        insertRestaurants();
        List<Long> restaurantIds = jdbcTemplate.queryForList("SELECT id FROM restaurants ORDER BY id", Long.class);
        long restaurantId = restaurantIds.get(restaurantIds.size() - 1); // one of ours, with two menu items
        String menuKey = "onlineorder:cache:v1:menu::" + restaurantId;
        Assertions.assertEquals(restaurantIds.size(), getCatalog().getBody().size(), "healthy Redis: both levels filled");
        Assertions.assertTrue(redis.hasKey(menuKey));
        Assertions.assertFalse(breaker().isOpen());

        pauseRedis();
        try {
            // As if the L1 TTL had passed: every entry now comes from the hung Redis or the database.
            cacheManager.clearLocal();
            double errorsBefore = l2Gets("error");
            double skippedBefore = l2Gets("skipped");

            Timed<ResponseEntity<List<Map<String, Object>>>> cold = timed(this::getCatalog);

            Assertions.assertEquals(HttpStatus.OK, cold.value().getStatusCode());
            Assertions.assertEquals(restaurantIds.size(), cold.value().getBody().size());
            assertWithin(COLD_CATALOG_BUDGET, cold.elapsed(), "cold GET /restaurants/menu with Redis hung");
            Assertions.assertTrue(breaker().isOpen());
            double timedOutReads = l2Gets("error") - errorsBefore;
            Assertions.assertTrue(timedOutReads >= 1 && timedOutReads <= 2, "timed-out Redis reads: " + timedOutReads);
            Assertions.assertTrue(l2Gets("skipped") - skippedBefore >= restaurantIds.size(), "the menu reads skip Redis");
            for (long id : restaurantIds) {
                Assertions.assertTrue(menuCache().isCachedLocally(id), "L1 warmed up for restaurant " + id);
            }
            assertWithin(Duration.ofMillis(500), timed(this::getCatalog).elapsed(), "warm GET /restaurants/menu");

            // Concurrent cold requests with the breaker closed: one waits for the timeout, the others wait for it
            // (single flight) and then skip Redis like everyone else.
            cacheManager.clearLocal();
            breaker().reset();
            for (Timed<ResponseEntity<List<Map<String, Object>>>> concurrent : concurrently(3, this::getCatalog)) {
                Assertions.assertEquals(HttpStatus.OK, concurrent.value().getStatusCode());
                Assertions.assertEquals(restaurantIds.size(), concurrent.value().getBody().size());
                assertWithin(COLD_CATALOG_BUDGET, concurrent.elapsed(), "concurrent cold GET /restaurants/menu");
            }

            cacheManager.clearLocal();
            breaker().reset();
            Timed<ResponseEntity<List<Map<String, Object>>>> menu = timed(() -> getJsonList("/restaurant/" + restaurantId + "/menu"));
            Assertions.assertEquals(HttpStatus.OK, menu.value().getStatusCode());
            Assertions.assertEquals(2, menu.value().getBody().size());
            assertWithin(COLD_MENU_BUDGET, menu.elapsed(), "cold GET /restaurant/{id}/menu with Redis hung");

            // Once the open period ends, one request probes Redis, pays one timeout and the breaker re-opens.
            Thread.sleep(2_100);
            cacheManager.clearLocal();
            double errorsBeforeProbe = l2Gets("error");
            assertWithin(COLD_CATALOG_BUDGET, timed(this::getCatalog).elapsed(), "cold GET /restaurants/menu after the open period");
            Assertions.assertEquals(1.0, l2Gets("error") - errorsBeforeProbe, "exactly one probe");
            Assertions.assertTrue(breaker().isOpen());
        } finally {
            unpauseRedis();
        }

        // Redis answers again: after the open period a probe succeeds and reads and write-backs use Redis again.
        await(() -> {
            cacheManager.clearLocal();
            getJsonList("/restaurant/" + restaurantId + "/menu");
            return !breaker().isOpen();
        }, "the breaker closes once Redis answers");
        redis.delete(menuKey);
        cacheManager.clearLocal();
        Assertions.assertEquals(HttpStatus.OK, getJsonList("/restaurant/" + restaurantId + "/menu").getStatusCode());
        Assertions.assertTrue(redis.hasKey(menuKey), "write-backs resumed");
    }


    private void insertRestaurants() {
        for (int i = 0; i < EXTRA_RESTAURANTS; i++) {
            String name = "Hung Redis Kitchen " + i;
            jdbcTemplate.update(
                    "INSERT INTO restaurants (name, address, image_url, phone) VALUES (?, ?, ?, ?)",
                    name, i + " Timeout Way", "https://example.com/kitchen.png", "(408) 555-0100"
            );
            long id = jdbcTemplate.queryForObject("SELECT id FROM restaurants WHERE name = ?", Long.class, name);
            for (String item : List.of("Burger", "Fries")) {
                jdbcTemplate.update(
                        "INSERT INTO menu_items (restaurant_id, name, description, price, image_url) VALUES (?, ?, ?, ?, ?)",
                        id, item, "Cached", new BigDecimal("4.50"), "https://example.com/item.png"
                );
            }
        }
        // Inserted behind the cache's back: drop anything cached during startup.
        cacheManager.getCaches().forEach(TwoLevelCache::clear);
    }


    private void pauseRedis() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }


    private void unpauseRedis() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }


    private RemoteCacheCircuitBreaker breaker() {
        return menuCache().getRemoteCircuitBreaker();
    }


    private TwoLevelCache menuCache() {
        return cacheManager.getTwoLevelCache(CatalogCaches.MENU);
    }


    private double l2Gets(String result) {
        return meterRegistry.find("cache.gets")
                .tags("level", "l2", "result", result)
                .functionCounters()
                .stream()
                .mapToDouble(FunctionCounter::count)
                .sum();
    }


    private ResponseEntity<List<Map<String, Object>>> getCatalog() {
        return getJsonList("/restaurants/menu");
    }


    private ResponseEntity<List<Map<String, Object>>> getJsonList(String path) {
        return restTemplate.exchange("http://localhost:" + port + path, HttpMethod.GET, null, JSON_LIST);
    }


    private static void assertWithin(Duration budget, Duration elapsed, String what) {
        Assertions.assertTrue(elapsed.compareTo(budget) < 0, what + " took " + elapsed.toMillis() + "ms, budget " + budget.toMillis() + "ms");
    }


    private static <T> Timed<T> timed(Callable<T> call) throws Exception {
        long start = System.nanoTime();
        T value = call.call();
        return new Timed<>(value, Duration.ofNanos(System.nanoTime() - start));
    }


    private static <T> List<Timed<T>> concurrently(int callers, Callable<T> call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Timed<T>>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return timed(call);
                }));
            }
            start.countDown();
            List<Timed<T>> results = new ArrayList<>();
            for (Future<Timed<T>> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }


    private static void await(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        Assertions.fail("Timed out waiting until " + description);
    }


    private record Timed<T>(T value, Duration elapsed) {
    }
}
