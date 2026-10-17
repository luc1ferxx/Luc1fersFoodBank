package com.laioffer.onlineorder.cache;


import com.laioffer.onlineorder.model.CatalogMenuItemDto;
import com.laioffer.onlineorder.model.CatalogRestaurantDto;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;


import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;


/**
 * Runs the Redis-backed pieces against a real Redis: typed serialization, versioned keys, jittered TTLs and
 * pub/sub L1 invalidation between two independent cache managers (two "instances").
 */
@Testcontainers(disabledWithoutDocker = true)
class TwoLevelCacheRedisIntegrationTests {

    private static final Duration REMOTE_TTL = Duration.ofMinutes(10);

    @Container
    static GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static final ObjectMapper OBJECT_MAPPER = TwoLevelCaches.objectMapper();

    private final List<CacheInvalidationSubscription> subscriptions = new ArrayList<>();
    private final List<String> errors = new CopyOnWriteArrayList<>();


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
    void flush() {
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }


    @AfterEach
    void stopSubscriptions() throws Exception {
        for (CacheInvalidationSubscription subscription : subscriptions) {
            subscription.destroy();
        }
    }


    @Test
    void menuEntries_shouldBeStoredAsPlainTypedJsonUnderAVersionedKeyWithJitteredTtl() {
        Instance writer = newInstance("writer");
        Instance reader = newInstance("reader");
        awaitSubscribed(writer, reader);
        List<CatalogMenuItemDto> menu = List.of(
                new CatalogMenuItemDto(10L, 5L, "Whopper", "Flame grilled", new BigDecimal("6.39"), "USD", "w.png"),
                new CatalogMenuItemDto(11L, 5L, "Fries", null, new BigDecimal("2.10"), "USD", null)
        );

        writer.menu().put(5L, menu);

        String raw = redis.opsForValue().get("onlineorder:cache:v2:menu::5");
        Assertions.assertNotNull(raw, "versioned key onlineorder:cache:v2:menu::5");
        Assertions.assertTrue(raw.startsWith("[{"), raw);
        Assertions.assertFalse(raw.contains("@class"), "no embedded type information: " + raw);
        Assertions.assertTrue(raw.contains("\"restaurantId\":5"), raw);
        Assertions.assertTrue(raw.contains("\"price\":6.39"), raw);

        Long ttlMillis = redis.getExpire("onlineorder:cache:v2:menu::5", java.util.concurrent.TimeUnit.MILLISECONDS);
        Assertions.assertTrue(ttlMillis >= REMOTE_TTL.toMillis() * 0.85 - 1000, "ttl " + ttlMillis);
        Assertions.assertTrue(ttlMillis <= REMOTE_TTL.toMillis() * 1.15, "ttl " + ttlMillis);

        // The second instance has an empty L1, so this read deserializes the Redis entry.
        List<CatalogMenuItemDto> read = reader.menu().get(5L, () -> Assertions.fail("must be served from Redis"));
        Assertions.assertEquals(menu, read);
        Assertions.assertInstanceOf(CatalogMenuItemDto.class, read.get(0));
        Assertions.assertEquals(new BigDecimal("2.10"), read.get(1).price(), "BigDecimal scale survives the round trip");
        Assertions.assertTrue(reader.menu().isCachedLocally(5L));
    }


    @Test
    void restaurantEntries_shouldRoundTripAsTheirOwnType() {
        Instance writer = newInstance("writer");
        Instance reader = newInstance("reader");
        awaitSubscribed(writer, reader);
        List<CatalogRestaurantDto> restaurants = List.of(new CatalogRestaurantDto(1L, "Burger King", "Main St", "123", "bk.png", "USD"));

        writer.restaurants().put(CatalogCaches.ALL_RESTAURANTS_KEY, restaurants);

        Assertions.assertNotNull(redis.opsForValue().get("onlineorder:cache:v2:restaurants::all"));
        Assertions.assertEquals(restaurants, reader.restaurants().get(CatalogCaches.ALL_RESTAURANTS_KEY).get());
    }


    @Test
    void anEntryWithAnotherShape_shouldBeTreatedAsAMissAndReplaced() {
        Instance instance = newInstance("instance");
        awaitSubscribed(instance);
        redis.opsForValue().set(
                "onlineorder:cache:v2:menu::5",
                "[{\"@class\":\"java.util.HashMap\",\"id\":10,\"unexpected\":true}]"
        );

        List<CatalogMenuItemDto> loaded = instance.menu().get(5L, () -> List.of(
                new CatalogMenuItemDto(10L, 5L, "Whopper", null, new BigDecimal("6.39"), "USD", null)
        ));

        Assertions.assertEquals("Whopper", loaded.get(0).name());
        Assertions.assertEquals(List.of("get:5"), errors, "the unreadable entry is reported, not thrown");
        Assertions.assertTrue(redis.opsForValue().get("onlineorder:cache:v2:menu::5").contains("Whopper"));
    }


    @Test
    void evictOnOneInstance_shouldDropTheL1CopyOnTheOther() {
        Instance first = newInstance("first");
        Instance second = newInstance("second");
        awaitSubscribed(first, second);
        List<CatalogMenuItemDto> oldMenu = List.of(new CatalogMenuItemDto(10L, 7L, "Whopper", null, new BigDecimal("6.39"), "USD", null));
        List<CatalogMenuItemDto> newMenu = List.of(new CatalogMenuItemDto(10L, 7L, "Whopper", null, new BigDecimal("7.25"), "USD", null));

        first.menu().get(7L, () -> oldMenu);
        Assertions.assertEquals(oldMenu, second.menu().get(7L, () -> Assertions.fail("served from Redis")));
        Assertions.assertTrue(second.menu().isCachedLocally(7L));

        first.menu().evict(7L);

        Assertions.assertFalse(first.menu().isCachedLocally(7L));
        Assertions.assertFalse(redis.hasKey("onlineorder:cache:v2:menu::7"));
        await(() -> !second.menu().isCachedLocally(7L), "second instance's L1 copy is dropped");
        Assertions.assertEquals(newMenu, second.menu().get(7L, () -> newMenu));
        Assertions.assertEquals(1, second.menu().remoteInvalidationsReceivedCount());
        Assertions.assertEquals(0, first.menu().remoteInvalidationsReceivedCount(), "an instance ignores its own messages");
    }


    @Test
    void putOnOneInstance_shouldMakeTheOtherReadTheNewValue() {
        Instance first = newInstance("first");
        Instance second = newInstance("second");
        awaitSubscribed(first, second);
        List<CatalogRestaurantDto> before = List.of(new CatalogRestaurantDto(1L, "Old name", null, null, null, "USD"));
        List<CatalogRestaurantDto> after = List.of(new CatalogRestaurantDto(1L, "New name", null, null, null, "USD"));
        second.restaurants().get(CatalogCaches.ALL_RESTAURANTS_KEY, () -> before);

        first.restaurants().put(CatalogCaches.ALL_RESTAURANTS_KEY, after);

        await(() -> !second.restaurants().isCachedLocally(CatalogCaches.ALL_RESTAURANTS_KEY), "stale L1 copy dropped");
        Assertions.assertEquals(after, second.restaurants().get(CatalogCaches.ALL_RESTAURANTS_KEY).get());
    }


    @Test
    void clearOnOneInstance_shouldClearRedisAndTheOtherL1() {
        Instance first = newInstance("first");
        Instance second = newInstance("second");
        awaitSubscribed(first, second);
        for (long restaurantId = 1; restaurantId <= 3; restaurantId++) {
            long id = restaurantId;
            second.menu().get(id, () -> List.of(new CatalogMenuItemDto(id * 10, id, "Item", null, BigDecimal.ONE, "USD", null)));
        }

        first.menu().clear();

        Assertions.assertTrue(redis.keys("onlineorder:cache:v2:menu::*").isEmpty());
        await(() -> !second.menu().isCachedLocally(1L) && !second.menu().isCachedLocally(3L), "second L1 cleared");
    }


    @Test
    void subscription_whenRedisDropsTheConnection_shouldResubscribeAndClearL1() throws Exception {
        Instance instance = newInstance("instance");
        Instance other = newInstance("other");
        awaitSubscribed(instance, other);
        instance.menu().get(5L, () -> List.of());
        long subscriptionsBefore = instance.listener().subscriptionCount();

        REDIS.execInContainer("redis-cli", "CLIENT", "KILL", "TYPE", "pubsub");

        // The listener counts a subscription only after it has cleared L1 for it.
        await(() -> instance.listener().subscriptionCount() > subscriptionsBefore, "re-subscribed after the connection was killed");
        Assertions.assertFalse(instance.menu().isCachedLocally(5L), "L1 is cleared because messages may have been missed");

        // The new subscription delivers invalidations from other instances again.
        instance.menu().get(5L, () -> Assertions.fail("served from Redis"));
        other.menu().evict(5L);
        await(() -> instance.menu().remoteInvalidationsReceivedCount() == 1, "the other instance's eviction arrives after the reconnect");
        Assertions.assertFalse(instance.menu().isCachedLocally(5L));
    }


    @Test
    void subscription_whenRedisIsUnreachableAtStartup_shouldKeepRetryingInTheBackground() throws Exception {
        int port = unusedPort();
        LettuceConnectionFactory unreachable = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port));
        unreachable.afterPropertiesSet();
        unreachable.start();
        try {
            TwoLevelCacheManager manager = manager(unreachable, "unreachable");
            CacheInvalidationListener listener = new CacheInvalidationListener(manager, OBJECT_MAPPER, "unreachable");
            CacheInvalidationSubscription subscription = new CacheInvalidationSubscription(
                    unreachable, listener, "onlineorder:cache:invalidation", Duration.ofMillis(100), Duration.ofMillis(200)
            );
            subscriptions.add(subscription);

            Assertions.assertDoesNotThrow(subscription::start, "startup must not depend on Redis");
            Thread.sleep(600);
            Assertions.assertTrue(subscription.isRunning());
            Assertions.assertFalse(subscription.isSubscribed());
            Assertions.assertEquals(0, listener.subscriptionCount());
        } finally {
            unreachable.destroy();
        }
    }


    private Instance newInstance(String instanceId) {
        TwoLevelCacheManager manager = manager(connectionFactory, instanceId);
        CacheInvalidationListener listener = new CacheInvalidationListener(manager, OBJECT_MAPPER, instanceId);
        CacheInvalidationSubscription subscription = new CacheInvalidationSubscription(
                connectionFactory, listener, "onlineorder:cache:invalidation", Duration.ofMillis(100), Duration.ofSeconds(1)
        );
        subscriptions.add(subscription);
        subscription.start();
        return new Instance(manager, listener, subscription);
    }


    private TwoLevelCacheManager manager(LettuceConnectionFactory factory, String instanceId) {
        TwoLevelCacheSettings settings = new TwoLevelCacheSettings(
                "onlineorder:cache", Duration.ofMinutes(1), REMOTE_TTL, 0.15, Duration.ofSeconds(5), Duration.ofMillis(200)
        );
        return TwoLevelCaches.redisBacked(
                factory,
                OBJECT_MAPPER,
                settings,
                CatalogCaches.specs(OBJECT_MAPPER, 1000),
                new RedisCacheInvalidationPublisher(new StringRedisTemplate(factory), OBJECT_MAPPER, settings.invalidationChannel(), instanceId),
                new RecordingErrorHandler(errors)
        );
    }


    private void awaitSubscribed(Instance... instances) {
        for (Instance instance : instances) {
            await(() -> instance.listener().subscriptionCount() > 0, "subscribed to the invalidation channel");
        }
    }


    private static void await(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
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


    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }


    private record Instance(TwoLevelCacheManager manager, CacheInvalidationListener listener, CacheInvalidationSubscription subscription) {

        TwoLevelCache menu() {
            return manager.getTwoLevelCache(CatalogCaches.MENU);
        }


        TwoLevelCache restaurants() {
            return manager.getTwoLevelCache(CatalogCaches.RESTAURANTS);
        }
    }


    private record RecordingErrorHandler(List<String> errors) implements CacheErrorHandler {

        @Override
        public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
            errors.add("get:" + key);
        }


        @Override
        public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
            errors.add("put:" + key);
        }


        @Override
        public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
            errors.add("evict:" + key);
        }


        @Override
        public void handleCacheClearError(RuntimeException exception, Cache cache) {
            errors.add("clear");
        }
    }
}
