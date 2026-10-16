package com.laioffer.onlineorder.cache;


import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.util.Assert;


import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.LongAdder;


/**
 * A Spring {@link org.springframework.cache.Cache} with a per-instance Caffeine L1 in front of a shared
 * L2 (Redis).
 * <ul>
 *     <li>Reads check L1, then L2, then run the loader; an L2 hit or a loaded value is copied into L1.</li>
 *     <li>{@link #get(Object, Callable)} (used by {@code @Cacheable(sync = true)}) is single-flight per key
 *     and instance: concurrent misses for the same key wait for one L2 read / database load.</li>
 *     <li>Writes and evictions go to L2 first, then L1, then are broadcast so other instances drop their
 *     L1 copy.</li>
 *     <li>L2 or broadcast failures never fail the caller: they are reported to the {@link CacheErrorHandler}
 *     (logged and counted), a failed read is a miss, and L1 keeps working while Redis is down.</li>
 * </ul>
 * Null values are not cached; callers that need negative caching must cache an explicit empty value.
 */
public class TwoLevelCache extends AbstractValueAdaptingCache {

    private final String name;
    private final Cache<String, Object> localCache;
    private final RemoteCacheStore remoteStore;
    private final CacheInvalidationPublisher invalidationPublisher;
    private final CacheErrorHandler errorHandler;

    private final LongAdder remoteHits = new LongAdder();
    private final LongAdder remoteMisses = new LongAdder();
    private final LongAdder remotePuts = new LongAdder();
    private final LongAdder remoteEvictions = new LongAdder();
    private final LongAdder remoteInvalidationsReceived = new LongAdder();


    public TwoLevelCache(
            String name,
            Cache<String, Object> localCache,
            RemoteCacheStore remoteStore,
            CacheInvalidationPublisher invalidationPublisher,
            CacheErrorHandler errorHandler
    ) {
        super(false);
        Assert.hasText(name, "Cache name must not be empty");
        this.name = name;
        this.localCache = localCache;
        this.remoteStore = remoteStore;
        this.invalidationPublisher = invalidationPublisher;
        this.errorHandler = errorHandler;
    }


    @Override
    public String getName() {
        return name;
    }


    @Override
    public Cache<String, Object> getNativeCache() {
        return localCache;
    }


    @Override
    protected Object lookup(Object key) {
        String cacheKey = cacheKey(key);
        Object value = localCache.getIfPresent(cacheKey);
        if (value != null) {
            return value;
        }
        value = getRemote(key, cacheKey);
        if (value != null) {
            localCache.put(cacheKey, value);
        }
        return value;
    }


    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        String cacheKey = cacheKey(key);
        // Caffeine runs the mapping function at most once per key at a time; other callers for the same key
        // block until it returns, so a burst of misses costs one Redis read and at most one database load.
        Object value = localCache.get(cacheKey, ignored -> loadThroughRemote(key, cacheKey, valueLoader));
        return (T) fromStoreValue(value);
    }


    @Override
    public void put(Object key, Object value) {
        Object storeValue = toStoreValue(value);
        String cacheKey = cacheKey(key);
        putRemote(key, cacheKey, storeValue);
        localCache.put(cacheKey, storeValue);
        publishEvict(key, cacheKey);
    }


    /**
     * Deletes the entry from L2, then from this instance's L1, then tells the other instances to drop
     * theirs. L2 goes first so a concurrent reader on this instance cannot refill L1 from the old L2 entry.
     */
    @Override
    public void evict(Object key) {
        String cacheKey = cacheKey(key);
        evictRemote(key, cacheKey);
        localCache.invalidate(cacheKey);
        publishEvict(key, cacheKey);
    }


    @Override
    public void clear() {
        try {
            remoteStore.clear();
        } catch (RuntimeException ex) {
            errorHandler.handleCacheClearError(ex, this);
        }
        localCache.invalidateAll();
        try {
            invalidationPublisher.publishClear(name);
        } catch (RuntimeException ex) {
            errorHandler.handleCacheClearError(ex, this);
        }
    }


    /**
     * Applies an invalidation published by another instance: drops one L1 entry, or the whole L1 copy when
     * {@code cacheKey} is null (a remote clear). Redis is not touched; the publisher already updated it.
     */
    public void invalidateLocal(String cacheKey) {
        remoteInvalidationsReceived.increment();
        if (cacheKey == null) {
            localCache.invalidateAll();
        } else {
            localCache.invalidate(cacheKey);
        }
    }


    /**
     * Drops this instance's whole L1 copy without touching Redis, e.g. after the invalidation channel
     * (re)subscribes, since messages may have been missed while it was down.
     */
    public void clearLocal() {
        localCache.invalidateAll();
    }


    /**
     * Whether this instance's L1 currently holds the key. Does not count as a cache access in the stats.
     */
    public boolean isCachedLocally(Object key) {
        return localCache.asMap().containsKey(cacheKey(key));
    }


    long remoteHitCount() {
        return remoteHits.sum();
    }


    long remoteMissCount() {
        return remoteMisses.sum();
    }


    long remotePutCount() {
        return remotePuts.sum();
    }


    long remoteEvictionCount() {
        return remoteEvictions.sum();
    }


    long remoteInvalidationsReceivedCount() {
        return remoteInvalidationsReceived.sum();
    }


    /**
     * Keys become part of the Redis key and of the invalidation message, so they must render the same way
     * on every instance. Strings and numbers do; arbitrary objects (including {@code SimpleKey}) are
     * rejected instead of silently producing keys such as {@code SimpleKey []}.
     */
    static String cacheKey(Object key) {
        Assert.notNull(key, "Cache key must not be null");
        if (key instanceof CharSequence || key instanceof Number || key instanceof Enum<?> || key instanceof UUID) {
            String rendered = key.toString();
            Assert.hasText(rendered, "Cache key must not be empty");
            return rendered;
        }
        throw new IllegalArgumentException(
                "Two-level cache keys must be strings, numbers, enums or UUIDs, got " + key.getClass().getName()
        );
    }


    private Object loadThroughRemote(Object key, String cacheKey, Callable<?> valueLoader) {
        Object value = getRemote(key, cacheKey);
        if (value != null) {
            return value;
        }
        Object loaded;
        try {
            loaded = valueLoader.call();
        } catch (Exception ex) {
            throw new ValueRetrievalException(key, valueLoader, ex);
        }
        if (loaded == null) {
            return null;
        }
        putRemote(key, cacheKey, loaded);
        return loaded;
    }


    private Object getRemote(Object key, String cacheKey) {
        Object value;
        try {
            value = remoteStore.get(cacheKey);
        } catch (RuntimeException ex) {
            errorHandler.handleCacheGetError(ex, this, key);
            return null;
        }
        if (value == null) {
            remoteMisses.increment();
        } else {
            remoteHits.increment();
        }
        return value;
    }


    private void putRemote(Object key, String cacheKey, Object value) {
        try {
            remoteStore.put(cacheKey, value);
            remotePuts.increment();
        } catch (RuntimeException ex) {
            errorHandler.handleCachePutError(ex, this, key, value);
        }
    }


    private void evictRemote(Object key, String cacheKey) {
        try {
            remoteStore.evict(cacheKey);
            remoteEvictions.increment();
        } catch (RuntimeException ex) {
            errorHandler.handleCacheEvictError(ex, this, key);
        }
    }


    private void publishEvict(Object key, String cacheKey) {
        try {
            invalidationPublisher.publishEvict(name, cacheKey);
        } catch (RuntimeException ex) {
            errorHandler.handleCacheEvictError(ex, this, key);
        }
    }
}
