package com.laioffer.onlineorder;


import com.laioffer.onlineorder.cache.CatalogCaches;
import com.laioffer.onlineorder.cache.TwoLevelCache;
import com.laioffer.onlineorder.cache.TwoLevelCacheManager;
import com.laioffer.onlineorder.model.CatalogMenuItemDto;
import com.laioffer.onlineorder.model.UpdateMenuItemBody;
import com.laioffer.onlineorder.service.MenuItemService;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;


import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;


/**
 * End-to-end catalog caching on PostgreSQL and Redis: cached reads keep their JSON shape, admin writes evict
 * after commit (and not on rollback), HTTP caching headers, admin authorization and cache metrics.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                        "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration",
                "app.cache.delayed-double-delete=300ms",
                "app.catalog.http-max-age=30s",
                // Every unknown id is rechecked, so the 404 test cannot use up the interval of the next test.
                "app.catalog.unknown-restaurant-recheck-interval=0s"
        }
)
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestRestTemplate
class CatalogCacheIntegrationTests {

    @Container
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("onlineorder")
            .withUsername("postgres")
            .withPassword("secret");

    @Container
    static GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST = new ParameterizedTypeReference<>() {
    };


    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.outbox.publisher-enabled", () -> "false");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
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
    private MenuItemService menuItemService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    private long restaurantId;
    private long whopperId;


    @BeforeEach
    void createRestaurantAndResetCaches() {
        String name = "Cache Test Kitchen " + System.nanoTime();
        jdbcTemplate.update(
                "INSERT INTO restaurants (name, address, image_url, phone) VALUES (?, ?, ?, ?)",
                name, "1 Cache Way", "https://example.com/kitchen.png", "(408) 555-0100"
        );
        restaurantId = jdbcTemplate.queryForObject("SELECT id FROM restaurants WHERE name = ?", Long.class, name);
        jdbcTemplate.update(
                "INSERT INTO menu_items (restaurant_id, name, description, price, image_url) VALUES (?, ?, ?, ?, ?)",
                restaurantId, "Whopper", "Flame grilled", new BigDecimal("5.00"), "https://example.com/whopper.png"
        );
        jdbcTemplate.update(
                "INSERT INTO menu_items (restaurant_id, name, description, price, image_url) VALUES (?, ?, ?, ?, ?)",
                restaurantId, "Fries", "Salted", new BigDecimal("2.50"), "https://example.com/fries.png"
        );
        whopperId = jdbcTemplate.queryForObject(
                "SELECT id FROM menu_items WHERE restaurant_id = ? AND name = 'Whopper'", Long.class, restaurantId
        );
        // The restaurant was inserted behind the cache's back; start every test from empty caches.
        cacheManager.getCaches().forEach(TwoLevelCache::clear);
    }


    @Test
    void menuEndpoints_shouldKeepTheirJsonShapeAndFillTheGranularCaches() {
        ResponseEntity<List<Map<String, Object>>> menu = getJsonList("/restaurant/" + restaurantId + "/menu");

        Assertions.assertEquals(HttpStatus.OK, menu.getStatusCode());
        Assertions.assertEquals(2, menu.getBody().size());
        Map<String, Object> whopper = menu.getBody().get(0);
        Assertions.assertEquals(List.of("id", "restaurant_id", "name", "description", "price", "currency", "image_url"), new ArrayList<>(whopper.keySet()));
        Assertions.assertEquals("USD", whopper.get("currency"));
        Assertions.assertEquals(whopperId, ((Number) whopper.get("id")).longValue());
        Assertions.assertEquals(restaurantId, ((Number) whopper.get("restaurant_id")).longValue());
        Assertions.assertEquals(0, new BigDecimal("5.00").compareTo(new BigDecimal(whopper.get("price").toString())));
        Assertions.assertTrue(redis.hasKey(menuKey()), "menu::<restaurantId> is written to Redis");
        Assertions.assertTrue(menuCache().isCachedLocally(restaurantId), "and kept in L1");

        ResponseEntity<List<Map<String, Object>>> restaurants = getJsonList("/restaurants/menu");

        Assertions.assertEquals(HttpStatus.OK, restaurants.getStatusCode());
        Map<String, Object> restaurant = restaurants.getBody().stream()
                .filter(entry -> ((Number) entry.get("id")).longValue() == restaurantId)
                .findFirst()
                .orElseThrow();
        Assertions.assertEquals(List.of("id", "name", "address", "phone", "image_url", "currency", "menu_items"), new ArrayList<>(restaurant.keySet()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> menuItems = (List<Map<String, Object>>) restaurant.get("menu_items");
        Assertions.assertEquals(List.of("id", "name", "description", "price", "currency", "image_url"), new ArrayList<>(menuItems.get(0).keySet()));
        Assertions.assertTrue(redis.hasKey("onlineorder:cache:v2:restaurants::all"));
        Assertions.assertFalse(redis.hasKey("restaurants::SimpleKey []"), "the old single all-in-one key is gone");
    }


    @Test
    void unknownRestaurantMenu_shouldBeA404AnsweredWithoutCachingAnEmptyMenu() {
        ResponseEntity<Map> response = restTemplate.getForEntity(url("/restaurant/987654321/menu"), Map.class);

        Assertions.assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        Assertions.assertEquals("NOT_FOUND", response.getBody().get("code"));
        Assertions.assertEquals("Restaurant not found", response.getBody().get("message"));
        Assertions.assertFalse(menuCache().isCachedLocally(987654321L));
        Assertions.assertFalse(redis.hasKey("onlineorder:cache:v2:menu::987654321"));
    }


    @Test
    void restaurantInsertedBehindTheCache_shouldBeServedRightAwayAndRefreshTheCachedList() {
        getJsonList("/restaurants/menu");
        Assertions.assertTrue(redis.hasKey("onlineorder:cache:v2:restaurants::all"));
        String name = "Seeded Later " + System.nanoTime();
        jdbcTemplate.update("INSERT INTO restaurants (name, address, image_url, phone) VALUES (?, ?, ?, ?)", name, "2 Seed St", "", "");
        long newRestaurantId = jdbcTemplate.queryForObject("SELECT id FROM restaurants WHERE name = ?", Long.class, name);
        jdbcTemplate.update(
                "INSERT INTO menu_items (restaurant_id, name, description, price, image_url) VALUES (?, ?, ?, ?, ?)",
                newRestaurantId, "Soup", "Hot", new BigDecimal("4.00"), ""
        );

        ResponseEntity<List<Map<String, Object>>> menu = getJsonList("/restaurant/" + newRestaurantId + "/menu");

        Assertions.assertEquals(HttpStatus.OK, menu.getStatusCode(), "not a 404 until the cached list expires");
        Assertions.assertEquals("Soup", menu.getBody().get(0).get("name"));
        Assertions.assertTrue(getJsonList("/restaurants/menu").getBody().stream()
                .anyMatch(entry -> ((Number) entry.get("id")).longValue() == newRestaurantId), "the stale list was evicted");
        // Let the delayed second delete of restaurants::all run now rather than during another test.
        await(() -> !redis.hasKey("onlineorder:cache:v2:restaurants::all"), "the delayed second delete ran");
    }


    @Test
    void adminMenuItemUpdate_shouldEvictTheMenuAfterCommitAndServeTheNewValues() {
        getJsonList("/restaurant/" + restaurantId + "/menu");
        Assertions.assertTrue(redis.hasKey(menuKey()));
        String adminCookie = login("demo@laifood.com", "demo123");

        ResponseEntity<Map> update = patch(
                "/admin/menu-items/" + whopperId,
                Map.of("name", "Double Whopper", "price", new BigDecimal("7.25")),
                adminCookie
        );

        Assertions.assertEquals(HttpStatus.OK, update.getStatusCode());
        Assertions.assertEquals("Double Whopper", update.getBody().get("name"));
        Assertions.assertEquals("Flame grilled", update.getBody().get("description"), "omitted fields are unchanged");
        Assertions.assertEquals(restaurantId, ((Number) update.getBody().get("restaurant_id")).longValue());
        Assertions.assertFalse(redis.hasKey(menuKey()), "evicted from Redis by the time the response is sent");
        Assertions.assertFalse(menuCache().isCachedLocally(restaurantId), "and from L1");

        Map<String, Object> whopper = getJsonList("/restaurant/" + restaurantId + "/menu").getBody().get(0);
        Assertions.assertEquals("Double Whopper", whopper.get("name"));
        Assertions.assertEquals(0, new BigDecimal("7.25").compareTo(new BigDecimal(whopper.get("price").toString())));
    }


    @Test
    void menuItemUpdate_shouldEvictOnlyAfterCommitAndNothingOnRollback() {
        getJsonList("/restaurant/" + restaurantId + "/menu");
        UpdateMenuItemBody newPrice = new UpdateMenuItemBody(null, null, new BigDecimal("9.99"));

        transactionTemplate.executeWithoutResult(status -> {
            menuItemService.updateMenuItem(whopperId, newPrice);
            Assertions.assertTrue(redis.hasKey(menuKey()), "nothing is evicted before the commit");
            status.setRollbackOnly();
        });

        Assertions.assertTrue(redis.hasKey(menuKey()), "rollback: Redis entry kept");
        Assertions.assertTrue(menuCache().isCachedLocally(restaurantId), "rollback: L1 entry kept");
        Assertions.assertEquals(0, new BigDecimal("5.00").compareTo(priceInDatabase(whopperId)));
        Assertions.assertEquals(0, new BigDecimal("5.00").compareTo(cachedWhopperPrice()));

        transactionTemplate.executeWithoutResult(status -> {
            menuItemService.updateMenuItem(whopperId, newPrice);
            Assertions.assertTrue(redis.hasKey(menuKey()), "still cached inside the transaction");
        });

        Assertions.assertFalse(redis.hasKey(menuKey()), "commit: evicted");
        Assertions.assertFalse(menuCache().isCachedLocally(restaurantId));
        Assertions.assertEquals(0, new BigDecimal("9.99").compareTo(cachedWhopperPrice()));
    }


    @Test
    void delayedSecondDelete_shouldRemoveAStaleMenuCachedRightAfterTheCommit() {
        List<CatalogMenuItemDto> staleMenu = menuItemService.getMenu(restaurantId);
        menuItemService.updateMenuItem(whopperId, new UpdateMenuItemBody(null, null, new BigDecimal("8.50")));

        // A reader that loaded the old rows before the commit writes them back after the first delete.
        menuCache().put(restaurantId, staleMenu);
        Assertions.assertTrue(redis.hasKey(menuKey()));

        await(() -> !redis.hasKey(menuKey()) && !menuCache().isCachedLocally(restaurantId), "the delayed delete removes the stale entry");
        Assertions.assertEquals(0, new BigDecimal("8.50").compareTo(cachedWhopperPrice()));
    }


    @Test
    void adminRestaurantUpdate_shouldEvictTheRestaurantList() {
        getJsonList("/restaurants/menu");
        Assertions.assertTrue(redis.hasKey("onlineorder:cache:v2:restaurants::all"));
        String adminCookie = login("demo@laifood.com", "demo123");

        ResponseEntity<Map> update = patch(
                "/admin/restaurants/" + restaurantId,
                Map.of("name", "Renamed Kitchen " + restaurantId, "phone", "(408) 555-0199"),
                adminCookie
        );

        Assertions.assertEquals(HttpStatus.OK, update.getStatusCode());
        Assertions.assertEquals("1 Cache Way", update.getBody().get("address"));
        Assertions.assertFalse(redis.hasKey("onlineorder:cache:v2:restaurants::all"));
        Map<String, Object> restaurant = getJsonList("/restaurants/menu").getBody().stream()
                .filter(entry -> ((Number) entry.get("id")).longValue() == restaurantId)
                .findFirst()
                .orElseThrow();
        Assertions.assertEquals("Renamed Kitchen " + restaurantId, restaurant.get("name"));
        Assertions.assertEquals("(408) 555-0199", restaurant.get("phone"));
        // Let the delayed second delete of restaurants::all run now rather than during another test.
        await(() -> !redis.hasKey("onlineorder:cache:v2:restaurants::all"), "the delayed second delete ran");
    }


    @Test
    void adminCatalogEndpoints_shouldRequireTheAdminRoleAndValidateInput() {
        Map<String, Object> body = Map.of("price", new BigDecimal("1.00"));
        Assertions.assertEquals(HttpStatus.UNAUTHORIZED, patch("/admin/menu-items/" + whopperId, body, null).getStatusCode());
        Assertions.assertEquals(HttpStatus.UNAUTHORIZED, patch("/admin/restaurants/" + restaurantId, Map.of("name", "x"), null).getStatusCode());

        String userCookie = signupAndLoginUser();
        ResponseEntity<Map> userResponse = patch("/admin/menu-items/" + whopperId, body, userCookie);
        Assertions.assertEquals(HttpStatus.FORBIDDEN, userResponse.getStatusCode());
        Assertions.assertEquals("FORBIDDEN", userResponse.getBody().get("code"));
        Assertions.assertEquals(HttpStatus.FORBIDDEN, patch("/admin/restaurants/" + restaurantId, Map.of("name", "x"), userCookie).getStatusCode());
        Assertions.assertEquals(0, new BigDecimal("5.00").compareTo(priceInDatabase(whopperId)), "rejected writes change nothing");

        String adminCookie = login("demo@laifood.com", "demo123");
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, patch("/admin/menu-items/" + whopperId, Map.of("price", -1), adminCookie).getStatusCode());
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, patch("/admin/menu-items/" + whopperId, Map.of("name", "   "), adminCookie).getStatusCode());
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, patch("/admin/menu-items/" + whopperId, Map.of(), adminCookie).getStatusCode());
        Assertions.assertEquals(HttpStatus.NOT_FOUND, patch("/admin/menu-items/987654321", body, adminCookie).getStatusCode());
        Assertions.assertEquals(HttpStatus.NOT_FOUND, patch("/admin/restaurants/987654321", Map.of("name", "x"), adminCookie).getStatusCode());
        Assertions.assertEquals(HttpStatus.OK, patch("/admin/menu-items/" + whopperId, body, adminCookie).getStatusCode());
        Assertions.assertEquals(0, new BigDecimal("1.00").compareTo(priceInDatabase(whopperId)));

        // Prices are set in the restaurant's currency only (the test restaurant prices in USD).
        ResponseEntity<Map> otherCurrency = patch(
                "/admin/menu-items/" + whopperId,
                Map.of("price", new BigDecimal("2.00"), "currency", "EUR"),
                adminCookie
        );
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, otherCurrency.getStatusCode());
        Assertions.assertEquals("CURRENCY_MISMATCH", otherCurrency.getBody().get("code"));
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, patch("/admin/menu-items/" + whopperId,
                Map.of("price", new BigDecimal("2.00"), "currency", "usd"), adminCookie).getStatusCode());
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, patch("/admin/menu-items/" + whopperId,
                Map.of("currency", "USD"), adminCookie).getStatusCode(), "a currency without a price");
        Assertions.assertEquals(0, new BigDecimal("1.00").compareTo(priceInDatabase(whopperId)), "rejected writes change nothing");
        ResponseEntity<Map> sameCurrency = patch(
                "/admin/menu-items/" + whopperId,
                Map.of("price", new BigDecimal("2.00"), "currency", "USD"),
                adminCookie
        );
        Assertions.assertEquals(HttpStatus.OK, sameCurrency.getStatusCode());
        Assertions.assertEquals("USD", sameCurrency.getBody().get("currency"));
        Assertions.assertEquals(0, new BigDecimal("2.00").compareTo(priceInDatabase(whopperId)));

        // A restaurant priced in yen takes whole yen only.
        jdbcTemplate.update("INSERT INTO restaurants (name, currency) VALUES ('Yen Ramen', 'JPY')");
        Long yenRestaurant = jdbcTemplate.queryForObject("SELECT id FROM restaurants WHERE name = 'Yen Ramen'", Long.class);
        jdbcTemplate.update("INSERT INTO menu_items (restaurant_id, name, price, currency) VALUES (?, 'Ramen', 900, 'JPY')",
                yenRestaurant);
        Long ramen = jdbcTemplate.queryForObject("SELECT id FROM menu_items WHERE restaurant_id = ?", Long.class, yenRestaurant);
        ResponseEntity<Map> fractionalYen = patch("/admin/menu-items/" + ramen, Map.of("price", new BigDecimal("950.50")), adminCookie);
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, fractionalYen.getStatusCode());
        Assertions.assertEquals("INVALID_AMOUNT", fractionalYen.getBody().get("code"));
        ResponseEntity<Map> wholeYen = patch("/admin/menu-items/" + ramen, Map.of("price", 950), adminCookie);
        Assertions.assertEquals(HttpStatus.OK, wholeYen.getStatusCode());
        Assertions.assertEquals("JPY", wholeYen.getBody().get("currency"));
        jdbcTemplate.update("DELETE FROM restaurants WHERE id = ?", yenRestaurant);
    }


    @Test
    void catalogGets_shouldBePubliclyCacheableWithEtagsAndAnswer304() {
        for (String path : List.of("/restaurants/menu", "/restaurant/" + restaurantId + "/menu")) {
            ResponseEntity<String> first = restTemplate.getForEntity(url(path), String.class);
            Assertions.assertEquals(HttpStatus.OK, first.getStatusCode());
            String etag = first.getHeaders().getETag();
            Assertions.assertNotNull(etag, path);
            Assertions.assertTrue(etag.matches("\"[0-9a-f]{32}\""), etag);
            Assertions.assertEquals("max-age=30, public", first.getHeaders().getCacheControl(), path);
            Assertions.assertNull(first.getHeaders().getFirst(HttpHeaders.PRAGMA), "Spring Security's no-cache headers are not added");
            Assertions.assertNull(first.getHeaders().getFirst(HttpHeaders.EXPIRES));
            assertSecurityHeaders(first.getHeaders());

            ResponseEntity<String> second = restTemplate.getForEntity(url(path), String.class);
            Assertions.assertEquals(etag, second.getHeaders().getETag(), "same catalog, same ETag");

            HttpHeaders conditional = new HttpHeaders();
            conditional.setIfNoneMatch(etag);
            ResponseEntity<String> notModified = restTemplate.exchange(url(path), HttpMethod.GET, new HttpEntity<>(conditional), String.class);
            Assertions.assertEquals(HttpStatus.NOT_MODIFIED, notModified.getStatusCode(), path);
            Assertions.assertNull(notModified.getBody());
            Assertions.assertEquals(etag, notModified.getHeaders().getETag());
            Assertions.assertEquals("max-age=30, public", notModified.getHeaders().getCacheControl());
            assertSecurityHeaders(notModified.getHeaders());
        }

        String menuPath = "/restaurant/" + restaurantId + "/menu";
        String menuEtag = restTemplate.getForEntity(url(menuPath), String.class).getHeaders().getETag();
        String allEtag = restTemplate.getForEntity(url("/restaurants/menu"), String.class).getHeaders().getETag();
        ResponseEntity<Map> update = patch(
                "/admin/menu-items/" + whopperId,
                Map.of("price", new BigDecimal("6.75")),
                login("demo@laifood.com", "demo123")
        );
        Assertions.assertTrue(update.getHeaders().getCacheControl().contains("no-store"), "other responses keep no-store");

        for (Map.Entry<String, String> previous : Map.of(menuPath, menuEtag, "/restaurants/menu", allEtag).entrySet()) {
            HttpHeaders conditional = new HttpHeaders();
            conditional.setIfNoneMatch(previous.getValue());
            ResponseEntity<String> changed = restTemplate.exchange(url(previous.getKey()), HttpMethod.GET, new HttpEntity<>(conditional), String.class);
            Assertions.assertEquals(HttpStatus.OK, changed.getStatusCode(), previous.getKey());
            Assertions.assertNotEquals(previous.getValue(), changed.getHeaders().getETag());
            Assertions.assertTrue(changed.getBody().contains("6.75"), changed.getBody());
        }
    }


    @Test
    void cacheMetrics_shouldReportL1AndL2HitsAndMisses() {
        getJsonList("/restaurant/" + restaurantId + "/menu");
        getJsonList("/restaurant/" + restaurantId + "/menu");

        Assertions.assertTrue(gets("menu", "l1", "hit") >= 1);
        Assertions.assertTrue(gets("menu", "l1", "miss") >= 1);
        Assertions.assertTrue(gets("menu", "l2", "miss") >= 1);
        FunctionCounter databaseLoads = meterRegistry.find("cache.load")
                .tags("cache", "menu", "level", "l2", "result", "success")
                .functionCounter();
        Assertions.assertNotNull(databaseLoads);
        Assertions.assertTrue(databaseLoads.count() >= 1);
        Assertions.assertNotNull(meterRegistry.find("cache.gets").tags("cache", "menu", "level", "l2", "result", "error").functionCounter());
        Assertions.assertNotNull(meterRegistry.find("cache.gets").tags("cache", "menu", "level", "l2", "result", "skipped").functionCounter());
        Assertions.assertNotNull(meterRegistry.find("cache.gets").tags("cache", "menu", "level", "l2", "result", "hit").functionCounter());
        Assertions.assertNotNull(meterRegistry.find("cache.gets").tags("cache.manager", "cacheManager").functionCounter(),
                "bound by Spring Boot's cache metrics registrar");
    }


    private void assertSecurityHeaders(HttpHeaders headers) {
        Assertions.assertTrue(headers.getFirst("Content-Security-Policy").contains("default-src 'self'"));
        Assertions.assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"));
        Assertions.assertEquals("SAMEORIGIN", headers.getFirst("X-Frame-Options"));
        Assertions.assertNotNull(headers.getFirst("Referrer-Policy"));
    }


    private double gets(String cache, String level, String result) {
        FunctionCounter counter = meterRegistry.find("cache.gets")
                .tags("cache", cache, "level", level, "result", result)
                .functionCounter();
        return counter == null ? 0 : counter.count();
    }


    private BigDecimal cachedWhopperPrice() {
        return menuItemService.getMenu(restaurantId).stream()
                .filter(item -> item.id() == whopperId)
                .findFirst()
                .orElseThrow()
                .price();
    }


    private BigDecimal priceInDatabase(long menuItemId) {
        return jdbcTemplate.queryForObject("SELECT price FROM menu_items WHERE id = ?", BigDecimal.class, menuItemId);
    }


    private TwoLevelCache menuCache() {
        return cacheManager.getTwoLevelCache(CatalogCaches.MENU);
    }


    private String menuKey() {
        return "onlineorder:cache:v2:menu::" + restaurantId;
    }


    private ResponseEntity<List<Map<String, Object>>> getJsonList(String path) {
        return restTemplate.exchange(url(path), HttpMethod.GET, null, JSON_LIST);
    }


    private ResponseEntity<Map> patch(String path, Map<String, ?> body, String sessionCookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (sessionCookie != null) {
            headers.add(HttpHeaders.COOKIE, sessionCookie);
        }
        return restTemplate.exchange(url(path), HttpMethod.PATCH, new HttpEntity<>(body, headers), Map.class);
    }


    private String signupAndLoginUser() {
        String email = "catalog-user-" + System.nanoTime() + "@example.com";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Void> signupResponse = restTemplate.postForEntity(
                url("/signup"),
                new HttpEntity<>(Map.of(
                        "email", email,
                        "password", "demo123",
                        "first_name", "Catalog",
                        "last_name", "User"
                ), headers),
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
        ResponseEntity<String> response = restTemplate.postForEntity(url("/login"), new HttpEntity<>(form, headers), String.class);
        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        String sessionCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        Assertions.assertNotNull(sessionCookie);
        return sessionCookie.split(";", 2)[0];
    }


    private static void await(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        Assertions.fail("Timed out waiting until " + description);
    }


    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
