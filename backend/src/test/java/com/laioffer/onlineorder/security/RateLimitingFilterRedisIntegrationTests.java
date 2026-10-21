package com.laioffer.onlineorder.security;


import com.laioffer.onlineorder.error.ApiErrorResponseFactory;
import com.laioffer.onlineorder.error.ApiErrorResponseWriter;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;


import java.security.Principal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * Runs {@link RateLimitingFilter} with its real token-bucket script against a real Redis, where the unit tests only
 * stub the script's answer: a bucket admits its capacity and then refuses with 429 and Retry-After, one token comes
 * back after one refill period, every login (or, on the IP-bound routes, every address) and every route has its own
 * bucket, and the bucket key expires.
 */
@Testcontainers(disabledWithoutDocker = true)
class RateLimitingFilterRedisIntegrationTests {

    private static final Duration BUCKET_TTL = Duration.ofMinutes(2);
    private static final String KEY_PREFIX = "onlineorder:rate-limit:";

    @Container
    static GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private SimpleMeterRegistry meterRegistry;
    private RateLimitingFilter filter;


    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getFirstMappedPort()));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
    }


    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }


    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        filter = newFilter(BUCKET_TTL);
        // Opens the connection and loads the script (FLUSHALL keeps it), so that draining a bucket below is a run of
        // quick round trips: the 1s rules refill a token per second while they drain.
        Assertions.assertTrue(send("POST", "/cart", "warm-up@example.com", "10.0.0.1").passed());
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }


    @Test
    void aBucket_shouldAdmitExactlyItsCapacityAndThenRefuseWith429AndRetryAfter() throws Exception {
        // signup: 5 tokens, one back every 12s, bound to the client address.
        drain("POST", "/signup", null, "10.0.0.5", 5);

        Outcome again = send("POST", "/signup", null, "10.0.0.5");
        Assertions.assertFalse(again.passed(), "a refusal takes no token but returns none either");
        Assertions.assertEquals(429, again.status());
        Assertions.assertEquals("12", again.retryAfter());
        Assertions.assertTrue(again.body().contains("\"code\":\"RATE_LIMITED\""), again.body());
        Assertions.assertTrue(again.body().contains("\"message\":\"Too many requests\""), again.body());

        Map<Object, Object> bucket = redis.opsForHash().entries(KEY_PREFIX + "signup:ip:10.0.0.5");
        double tokens = Double.parseDouble(String.valueOf(bucket.get("tokens")));
        Assertions.assertTrue(tokens >= 0 && tokens < 1, "tokens left: " + tokens);
        Assertions.assertNotNull(bucket.get("updated_at_ms"));
        Assertions.assertEquals(2.0, rejected("signup"));
        assertTheStoreNeverFailed();
    }


    @Test
    void aRefusedRequest_shouldBeAdmittedAgainWithinRetryAfterAndOnlyForTheOneTokenThatCameBack() throws Exception {
        // cart: 60 tokens, one back every second. The bucket starts empty instead of being drained, because 60 requests
        // need not finish within the second that returns a token (the slower buckets above cover the draining).
        redis.opsForHash().putAll(KEY_PREFIX + "cart:user:alice@example.com",
                Map.of("tokens", "0", "updated_at_ms", String.valueOf(System.currentTimeMillis())));
        Outcome refused = send("POST", "/cart", "alice@example.com", "10.0.0.5");
        Assertions.assertFalse(refused.passed(), "an empty bucket refuses");
        Assertions.assertEquals("1", refused.retryAfter());
        long retryAfterMillis = TimeUnit.SECONDS.toMillis(Long.parseLong(refused.retryAfter()));
        Assertions.assertFalse(send("PUT", "/cart/items/3", "alice@example.com", "10.0.0.5").passed(),
                "the cart edits share the bucket, which does not refill at once");

        Awaitility.await("a cart edit admitted again")
                .atMost(Duration.ofMillis(retryAfterMillis + 500))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> send("DELETE", "/cart/items/3", "alice@example.com", "10.0.0.5").passed());

        Outcome next = send("POST", "/cart", "alice@example.com", "10.0.0.5");
        Assertions.assertFalse(next.passed(), "one refill period returns one token, not the whole bucket");
        Assertions.assertEquals("1", next.retryAfter());
        assertTheStoreNeverFailed();
    }


    @Test
    void everyLoginAddressAndRoute_shouldHaveItsOwnBucket() throws Exception {
        // payment-checkout: 10 tokens, one back every 6s, per login.
        drain("POST", "/payments/checkout", "alice@example.com", "10.0.0.5", 10);
        Assertions.assertFalse(send("POST", "/payments/checkout", "Alice@Example.com", "10.0.0.9").passed(),
                "the login, not its spelling or the address, picks the bucket");

        Assertions.assertTrue(send("POST", "/payments/checkout", "bob@example.com", "10.0.0.5").passed(),
                "another login on the same address");
        Assertions.assertTrue(send("POST", "/cart/checkout", "alice@example.com", "10.0.0.5").passed(),
                "another route for the same login");

        // login: 10 tokens, one back every 6s, per address whoever signs in.
        drain("POST", "/login", null, "10.0.0.5", 10);
        Assertions.assertTrue(send("POST", "/login", null, "10.0.0.6").passed(), "another address");

        Assertions.assertTrue(redis.hasKey(KEY_PREFIX + "payment-checkout:user:alice@example.com"));
        Assertions.assertTrue(redis.hasKey(KEY_PREFIX + "payment-checkout:user:bob@example.com"));
        Assertions.assertTrue(redis.hasKey(KEY_PREFIX + "cart-checkout:user:alice@example.com"));
        Assertions.assertTrue(redis.hasKey(KEY_PREFIX + "login:ip:10.0.0.5"));
        Assertions.assertTrue(redis.hasKey(KEY_PREFIX + "login:ip:10.0.0.6"));
        Assertions.assertEquals(2.0, rejected("payment-checkout"));
        Assertions.assertEquals(1.0, rejected("login"));
        Assertions.assertEquals(0.0, rejected("cart-checkout"));
        assertTheStoreNeverFailed();
    }


    @Test
    void theBucketKey_shouldExpireButNotBeforeTheBucketHasRefilled() throws Exception {
        Assertions.assertTrue(send("POST", "/merchant/orders/42/accept", "merchant@example.com", "10.0.0.5").passed());

        String key = KEY_PREFIX + "merchant-orders:user:merchant@example.com";
        Assertions.assertEquals(59.0, Double.parseDouble(String.valueOf(redis.opsForHash().get(key, "tokens"))));
        long ttlMillis = redis.getExpire(key, TimeUnit.MILLISECONDS);
        Assertions.assertTrue(ttlMillis > 0 && ttlMillis <= BUCKET_TTL.toMillis(), "ttl " + ttlMillis);

        // A configured TTL shorter than a full refill (60 tokens at one a second) is raised to it plus one period, so
        // an emptied bucket cannot expire into a full one early.
        filter = newFilter(Duration.ofSeconds(1));
        Assertions.assertTrue(send("POST", "/merchant/orders/42/accept", "merchant@example.com", "10.0.0.5").passed());
        long raisedTtlMillis = redis.getExpire(key, TimeUnit.MILLISECONDS);
        Assertions.assertTrue(raisedTtlMillis > 60_000 && raisedTtlMillis <= 61_000, "ttl " + raisedTtlMillis);
        assertTheStoreNeverFailed();
    }


    /**
     * Sends {@code capacity} requests that must all be admitted, then one that must be refused, and returns it.
     */
    private Outcome drain(String method, String uri, String login, String ip, int capacity) throws Exception {
        for (int i = 0; i < capacity; i++) {
            Outcome admitted = send(method, uri, login, ip);
            Assertions.assertTrue(admitted.passed(), method + " " + uri + " request " + (i + 1) + " of " + capacity);
            Assertions.assertNull(admitted.retryAfter());
        }
        Outcome refused = send(method, uri, login, ip);
        Assertions.assertFalse(refused.passed(), method + " " + uri + " request " + (capacity + 1));
        Assertions.assertEquals(429, refused.status());
        Assertions.assertNotNull(refused.retryAfter(), "a 429 says when to retry");
        return refused;
    }


    private Outcome send(String method, String uri, String login, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(ip);
        if (login != null) {
            request.setUserPrincipal((Principal) () -> login);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Outcome(chain.getRequest() != null, response.getStatus(), response.getHeader("Retry-After"), response.getContentAsString());
    }


    private RateLimitingFilter newFilter(Duration bucketTtl) {
        return new RateLimitingFilter(
                true,
                bucketTtl,
                redis,
                new ApplicationMetricsService(meterRegistry),
                new ApiErrorResponseWriter(new ApiErrorResponseFactory(), JsonMapper.builder().build())
        );
    }


    private double rejected(String route) {
        Counter counter = meterRegistry.find("onlineorder.security.rate_limit.rejected").tag("route", route).counter();
        return counter == null ? 0 : counter.count();
    }


    private void assertTheStoreNeverFailed() {
        Assertions.assertTrue(
                meterRegistry.find("onlineorder.security.rate_limit.store_failure").counters().isEmpty(),
                "the script ran instead of failing open"
        );
    }


    private record Outcome(boolean passed, int status, String retryAfter, String body) {
    }
}
