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
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.serializer.SerializationException;


import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


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
    void get_whenRedisHangs_shouldPayOneTimeoutThenSkipRedisUntilTheOpenPeriodEnds() {
        AtomicLong now = new AtomicLong();
        TwoLevelCache guarded = cacheWithBreaker(new RemoteCacheCircuitBreaker(Duration.ofSeconds(5), Duration.ZERO, now::get));
        remoteStore.hangMillis = 200;
        AtomicInteger loads = new AtomicInteger();

        long start = System.nanoTime();
        for (long key = 0; key < 40; key++) {
            long id = key;
            Assertions.assertEquals("menu-" + id, guarded.get(id, () -> {
                loads.incrementAndGet();
                return "menu-" + id;
            }));
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // Without the breaker every key pays a timed-out read and a timed-out write-back: 40 x 2 x 200ms = 16s.
        Assertions.assertTrue(elapsed.compareTo(Duration.ofSeconds(2)) < 0, "took " + elapsed);
        Assertions.assertEquals(1, remoteStore.gets.get(), "only the first read waits for Redis");
        Assertions.assertEquals(0, remoteStore.puts.get(), "write-backs are skipped while the breaker is open");
        Assertions.assertEquals(40, loads.get());
        Assertions.assertEquals(List.of("get:0"), errorHandler.errors);
        Assertions.assertEquals(1, guarded.remoteErrorCount());
        Assertions.assertEquals(39, guarded.remoteSkipCount());
        Assertions.assertEquals(40, guarded.sourceLoadCount());
        Assertions.assertTrue(guarded.isCachedLocally(0L) && guarded.isCachedLocally(39L), "L1 keeps working");

        now.addAndGet(Duration.ofSeconds(5).toNanos());
        guarded.get(100L, () -> "menu-100");
        guarded.get(101L, () -> "menu-101");
        Assertions.assertEquals(2, remoteStore.gets.get(), "one probe per open period; it fails and re-opens");

        remoteStore.hangMillis = 0;
        now.addAndGet(Duration.ofSeconds(5).toNanos());
        guarded.get(102L, () -> "menu-102");
        guarded.get(103L, () -> "menu-103");
        Assertions.assertEquals(4, remoteStore.gets.get(), "a successful probe closes the breaker");
        Assertions.assertEquals("menu-103", remoteStore.values.get("103"), "and write-backs resume");
    }


    @Test
    void evict_whenTheBreakerIsOpen_shouldStillDeleteFromRedisAndBroadcast() {
        TwoLevelCache guarded = cacheWithBreaker(new RemoteCacheCircuitBreaker(Duration.ofMinutes(1), Duration.ZERO));
        remoteStore.values.put("5", "stale");
        remoteStore.failing = true;
        guarded.get(6L, () -> "menu-6");
        Assertions.assertTrue(guarded.getRemoteCircuitBreaker().isOpen());
        remoteStore.failing = false;

        guarded.evict(5L);

        Assertions.assertFalse(remoteStore.values.containsKey("5"), "deletes keep Redis consistent with the database");
        Assertions.assertEquals(List.of("evict:menu:5"), publisher.messages);
        Assertions.assertFalse(guarded.getRemoteCircuitBreaker().isOpen(), "and their success closes the breaker");
    }


    @Test
    void get_whenTheRedisEntryCannotBeRead_shouldTreatItAsAMissWithoutOpeningTheBreaker() {
        TwoLevelCache guarded = cacheWithBreaker(new RemoteCacheCircuitBreaker(Duration.ofMinutes(1), Duration.ZERO));
        remoteStore.unreadable = true;

        Assertions.assertEquals("menu-5", guarded.get(5L, () -> "menu-5"));

        Assertions.assertFalse(guarded.getRemoteCircuitBreaker().isOpen());
        Assertions.assertEquals(List.of("get:5"), errorHandler.errors);
        Assertions.assertEquals("menu-5", remoteStore.values.get("5"), "the loaded value replaces the unreadable entry");
    }


    @Test
    void get_shouldReleaseWaitersBeforeTheLoaderFinishesTheRedisWriteBack() throws Exception {
        remoteStore.putDelayMillis = 1_500;
        CountDownLatch loading = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> loader = executor.submit(() -> timed(() -> cache.get(9L, () -> {
                loading.countDown();
                Thread.sleep(200);
                return "menu-9";
            })));
            Assertions.assertTrue(loading.await(5, TimeUnit.SECONDS));
            Future<Long> waiter = executor.submit(() -> timed(() -> cache.get(9L, () -> Assertions.fail("single flight"))));

            Assertions.assertTrue(waiter.get(5, TimeUnit.SECONDS) < Duration.ofMillis(1_000).toNanos(),
                    "the waiter gets the loaded value without waiting for the 1.5s Redis write");
            Assertions.assertTrue(loader.get(5, TimeUnit.SECONDS) >= Duration.ofMillis(1_500).toNanos(),
                    "the loading caller still writes the value back");
        } finally {
            executor.shutdownNow();
        }
        Assertions.assertEquals("menu-9", remoteStore.values.get("9"));
    }


    @Test
    void meterBinder_shouldExposeL1AndL2HitsAndMissesAsCacheGets() {
        MeterRegistry registry = new SimpleMeterRegistry();
        new TwoLevelCacheMeterBinder(cache, Tags.of("cache.manager", "cacheManager")).bindTo(registry);
        remoteStore.values.put("6", "from-redis");

        cache.get(5L, () -> "menu-5"); // L1 miss, L2 miss, load
        cache.get(5L, () -> "menu-5"); // L1 hit
        cache.get(6L, () -> "menu-6"); // L1 miss, L2 hit
        remoteStore.failing = true;
        cache.get(7L, () -> "menu-7"); // L1 miss, L2 error, load
        Assertions.assertThrows(Cache.ValueRetrievalException.class, () -> cache.get(8L, () -> {
            throw new IllegalStateException("database down"); // L1 miss, L2 error, failed load
        }));

        Assertions.assertEquals(1.0, gets(registry, "l1", "hit"));
        Assertions.assertEquals(4.0, gets(registry, "l1", "miss"));
        Assertions.assertEquals(1.0, gets(registry, "l2", "hit"));
        Assertions.assertEquals(1.0, gets(registry, "l2", "miss"));
        Assertions.assertEquals(2.0, gets(registry, "l2", "error"));
        Assertions.assertEquals(0.0, gets(registry, "l2", "skipped"));
        Assertions.assertEquals(2.0, loads(registry, "l2", "success"), "database loads, including those behind a Redis error");
        Assertions.assertEquals(1.0, loads(registry, "l2", "failure"));
        Assertions.assertEquals(1.0, registry.get("cache.puts").tags("cache", "menu", "level", "l2").functionCounter().count());
        Assertions.assertNotNull(registry.find("cache.size").tags("cache", "menu", "level", "l1", "cache.manager", "cacheManager").gauge());
    }


    @Test
    void meterBinder_shouldCountReadsSkippedByAnOpenBreaker() {
        TwoLevelCache guarded = cacheWithBreaker(new RemoteCacheCircuitBreaker(Duration.ofMinutes(1), Duration.ZERO));
        MeterRegistry registry = new SimpleMeterRegistry();
        new TwoLevelCacheMeterBinder(guarded, Tags.of("cache.manager", "cacheManager")).bindTo(registry);
        remoteStore.failing = true;

        guarded.get(5L, () -> "menu-5");
        guarded.get(6L, () -> "menu-6");

        Assertions.assertEquals(1.0, gets(registry, "l2", "error"));
        Assertions.assertEquals(1.0, gets(registry, "l2", "skipped"));
        Assertions.assertEquals(2.0, loads(registry, "l2", "success"));
    }


    private TwoLevelCache cacheWithBreaker(RemoteCacheCircuitBreaker breaker) {
        return new TwoLevelCache(
                "menu",
                Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).maximumSize(100).recordStats().build(),
                remoteStore,
                publisher,
                errorHandler,
                breaker
        );
    }


    private static long timed(Callable<?> call) throws Exception {
        long start = System.nanoTime();
        call.call();
        return System.nanoTime() - start;
    }


    private double loads(MeterRegistry registry, String level, String result) {
        return registry.get("cache.load")
                .tags("cache", "menu", "level", level, "result", result)
                .functionCounter()
                .count();
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
        /** Like a paused Redis: every call waits this long, then times out. */
        volatile long hangMillis;
        volatile long putDelayMillis;
        volatile boolean unreadable;


        @Override
        public Object get(String key) {
            gets.incrementAndGet();
            failIfDown();
            if (unreadable) {
                throw new SerializationException("Could not read JSON");
            }
            return values.get(key);
        }


        @Override
        public void put(String key, Object value) {
            puts.incrementAndGet();
            failIfDown();
            sleep(putDelayMillis);
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
            if (hangMillis > 0) {
                sleep(hangMillis);
                throw new QueryTimeoutException("Redis command timed out after " + hangMillis + "ms");
            }
        }


        private static void sleep(long millis) {
            if (millis <= 0) {
                return;
            }
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
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
