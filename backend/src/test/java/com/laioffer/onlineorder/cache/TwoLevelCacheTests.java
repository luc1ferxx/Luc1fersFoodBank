package com.laioffer.onlineorder.cache;


import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.data.redis.RedisConnectionFailureException;


import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


class TwoLevelCacheTests {

    private FakeRemoteStore remoteStore;
    private RecordingPublisher publisher;
    private RecordingErrorHandler errorHandler;
    private TwoLevelCache cache;


    @BeforeEach
    void setUp() {
        remoteStore = new FakeRemoteStore();
        publisher = new RecordingPublisher();
        errorHandler = new RecordingErrorHandler();
        cache = new TwoLevelCache(
                "menu",
                Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).maximumSize(100).recordStats().build(),
                remoteStore,
                publisher,
                errorHandler
        );
    }


    @Test
    void get_whenL1HasTheEntry_shouldNotReadRedisOrLoadAgain() {
        AtomicInteger loads = new AtomicInteger();

        Assertions.assertEquals("menu-5", cache.get(5L, () -> "menu-" + (5 + loads.getAndIncrement() * 100)));
        Assertions.assertEquals("menu-5", cache.get(5L, () -> "menu-" + (5 + loads.getAndIncrement() * 100)));
        Assertions.assertEquals("menu-5", cache.get(5L).get());

        Assertions.assertEquals(1, loads.get());
        Assertions.assertEquals(1, remoteStore.gets.get(), "only the first, missing read reaches Redis");
        Assertions.assertEquals(1, remoteStore.puts.get());
        Assertions.assertEquals("menu-5", remoteStore.values.get("5"));
        Assertions.assertEquals(2, cache.getNativeCache().stats().hitCount());
        Assertions.assertEquals(1, cache.getNativeCache().stats().missCount());
    }


    @Test
    void get_whenOnlyRedisHasTheEntry_shouldReturnItAndPopulateL1WithoutLoading() {
        remoteStore.values.put("5", "from-redis");

        Assertions.assertEquals("from-redis", cache.get(5L, () -> Assertions.fail("must not load on an L2 hit")));
        Assertions.assertTrue(cache.isCachedLocally(5L));
        Assertions.assertEquals("from-redis", cache.get(5L, () -> Assertions.fail("must not load on an L1 hit")));

        Assertions.assertEquals(1, remoteStore.gets.get());
        Assertions.assertEquals(0, remoteStore.puts.get(), "an L2 hit is not written back to L2");
        Assertions.assertEquals(1, cache.remoteHitCount());
    }


    @Test
    void lookup_whenOnlyRedisHasTheEntry_shouldPopulateL1() {
        remoteStore.values.put("all", "restaurants");

        Assertions.assertEquals("restaurants", cache.get("all").get());
        Assertions.assertTrue(cache.isCachedLocally("all"));
        Assertions.assertNull(cache.get("missing"));
        Assertions.assertFalse(cache.isCachedLocally("missing"));
    }


    @Test
    void get_whenManyThreadsMissTheSameKeyAtOnce_shouldLoadOnce() throws Exception {
        int threads = 32;
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return cache.get(7L, () -> {
                        loads.incrementAndGet();
                        Thread.sleep(200);
                        return "menu-7";
                    });
                }));
            }
            start.countDown();
            for (Future<String> result : results) {
                Assertions.assertEquals("menu-7", result.get(5, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        Assertions.assertEquals(1, loads.get(), "concurrent misses must share one database load");
        Assertions.assertEquals(1, remoteStore.gets.get(), "and one Redis read");
        Assertions.assertEquals(1, remoteStore.puts.get());
    }


    @Test
    void get_whenRedisIsDown_shouldLoadFromSourceReportErrorsAndKeepServingFromL1() {
        remoteStore.failing = true;
        AtomicInteger loads = new AtomicInteger();

        Assertions.assertEquals("menu-5", cache.get(5L, () -> "menu-" + (5 + loads.getAndIncrement())));
        Assertions.assertEquals("menu-5", cache.get(5L, () -> "menu-" + (5 + loads.getAndIncrement())));

        Assertions.assertEquals(1, loads.get(), "L1 keeps working while Redis is down");
        Assertions.assertEquals(List.of("get:5", "put:5"), errorHandler.errors);
    }


    @Test
    void get_whenLoaderFails_shouldPropagateAsValueRetrievalExceptionAndCacheNothing() {
        IllegalStateException failure = new IllegalStateException("database down");

        Cache.ValueRetrievalException thrown = Assertions.assertThrows(
                Cache.ValueRetrievalException.class,
                () -> cache.get(5L, () -> {
                    throw failure;
                })
        );

        Assertions.assertSame(failure, thrown.getCause());
        Assertions.assertFalse(cache.isCachedLocally(5L));
        Assertions.assertTrue(remoteStore.values.isEmpty());
    }


    @Test
    void get_whenLoaderReturnsNull_shouldNotCacheIt() {
        Assertions.assertNull(cache.get(5L, () -> null));
        Assertions.assertFalse(cache.isCachedLocally(5L));
        Assertions.assertTrue(remoteStore.values.isEmpty());
    }


    @Test
    void evict_shouldDeleteFromRedisThenL1AndBroadcast() {
        cache.get(5L, () -> "menu-5");

        cache.evict(5L);

        Assertions.assertFalse(remoteStore.values.containsKey("5"));
        Assertions.assertFalse(cache.isCachedLocally(5L));
        Assertions.assertEquals(List.of("evict:menu:5"), publisher.messages);
    }


    @Test
    void evict_whenRedisIsDown_shouldStillDropL1AndBroadcast() {
        cache.get(5L, () -> "menu-5");
        remoteStore.failing = true;

        cache.evict(5L);

        Assertions.assertFalse(cache.isCachedLocally(5L));
        Assertions.assertEquals(List.of("evict:menu:5"), publisher.messages);
        Assertions.assertEquals(List.of("evict:5"), errorHandler.errors);
    }


    @Test
    void put_shouldWriteBothLevelsAndTellOtherInstancesToDropTheirCopy() {
        cache.put(5L, "menu-5-v2");

        Assertions.assertEquals("menu-5-v2", remoteStore.values.get("5"));
        Assertions.assertTrue(cache.isCachedLocally(5L));
        Assertions.assertEquals(List.of("evict:menu:5"), publisher.messages);
    }


    @Test
    void invalidateLocal_shouldOnlyDropL1() {
        cache.get(5L, () -> "menu-5");
        cache.get(6L, () -> "menu-6");

        cache.invalidateLocal("5");

        Assertions.assertFalse(cache.isCachedLocally(5L));
        Assertions.assertEquals("menu-5", remoteStore.values.get("5"));
        Assertions.assertTrue(publisher.messages.isEmpty(), "a remote invalidation is not re-broadcast");
        Assertions.assertEquals(1, cache.remoteInvalidationsReceivedCount());
        Assertions.assertTrue(cache.isCachedLocally(6L));

        cache.invalidateLocal(null);

        Assertions.assertFalse(cache.isCachedLocally(6L), "a null key is a remote clear");
        Assertions.assertEquals(2, remoteStore.values.size(), "Redis is left alone");
    }


    @Test
    void clear_shouldClearBothLevelsAndBroadcast() {
        cache.get(5L, () -> "menu-5");
        cache.get(6L, () -> "menu-6");

        cache.clear();

        Assertions.assertTrue(remoteStore.values.isEmpty());
        Assertions.assertFalse(cache.isCachedLocally(5L));
        Assertions.assertEquals(List.of("clear:menu"), publisher.messages);
    }


    @Test
    void keys_shouldRenderTheSameOnEveryInstanceAndRejectObjectKeys() {
        cache.get(5L, () -> "menu-5");

        Assertions.assertTrue(cache.isCachedLocally(5), "Integer and Long ids map to the same entry");
        Assertions.assertThrows(IllegalArgumentException.class, () -> cache.get(SimpleKey.EMPTY, () -> "x"));
    }


    @Test
    void meterBinder_shouldExposeL1AndL2HitsAndMissesAsCacheGets() {
        MeterRegistry registry = new SimpleMeterRegistry();
        new TwoLevelCacheMeterBinder(cache, Tags.of("cache.manager", "cacheManager")).bindTo(registry);
        remoteStore.values.put("6", "from-redis");

        cache.get(5L, () -> "menu-5"); // L1 miss, L2 miss, load
        cache.get(5L, () -> "menu-5"); // L1 hit
        cache.get(6L, () -> "menu-6"); // L1 miss, L2 hit

        Assertions.assertEquals(1.0, gets(registry, "l1", "hit"));
        Assertions.assertEquals(2.0, gets(registry, "l1", "miss"));
        Assertions.assertEquals(1.0, gets(registry, "l2", "hit"));
        Assertions.assertEquals(1.0, gets(registry, "l2", "miss"));
        Assertions.assertEquals(1.0, registry.get("cache.puts").tags("cache", "menu", "level", "l2").functionCounter().count());
        Assertions.assertNotNull(registry.find("cache.size").tags("cache", "menu", "level", "l1", "cache.manager", "cacheManager").gauge());
    }


    private double gets(MeterRegistry registry, String level, String result) {
        FunctionCounter counter = registry.get("cache.gets")
                .tags("cache", "menu", "level", level, "result", result)
                .functionCounter();
        return counter.count();
    }


    static class FakeRemoteStore implements RemoteCacheStore {

        final Map<String, Object> values = new ConcurrentHashMap<>();
        final AtomicInteger gets = new AtomicInteger();
        final AtomicInteger puts = new AtomicInteger();
        volatile boolean failing;


        @Override
        public Object get(String key) {
            gets.incrementAndGet();
            failIfDown();
            return values.get(key);
        }


        @Override
        public void put(String key, Object value) {
            puts.incrementAndGet();
            failIfDown();
            values.put(key, value);
        }


        @Override
        public void evict(String key) {
            failIfDown();
            values.remove(key);
        }


        @Override
        public void clear() {
            failIfDown();
            values.clear();
        }


        private void failIfDown() {
            if (failing) {
                throw new RedisConnectionFailureException("Redis is down");
            }
        }
    }


    static class RecordingPublisher implements CacheInvalidationPublisher {

        final List<String> messages = new CopyOnWriteArrayList<>();


        @Override
        public void publishEvict(String cacheName, String key) {
            messages.add("evict:" + cacheName + ":" + key);
        }


        @Override
        public void publishClear(String cacheName) {
            messages.add("clear:" + cacheName);
        }
    }


    static class RecordingErrorHandler implements CacheErrorHandler {

        final List<String> errors = new CopyOnWriteArrayList<>();


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
