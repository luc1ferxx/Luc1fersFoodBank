package com.laioffer.onlineorder.cache;


import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.boot.actuate.metrics.cache.CacheMeterBinderProvider;


/**
 * Publishes both levels under Micrometer's standard cache meter names, told apart by a {@code level} tag:
 * <ul>
 *     <li>{@code cache.gets{level=l1,result=hit|miss}}, {@code cache.puts}, {@code cache.evictions},
 *     {@code cache.size} and Caffeine's load meters for L1 (from Caffeine's own stats);</li>
 *     <li>{@code cache.gets{level=l2,result=hit|miss}}, {@code cache.puts{level=l2}} and
 *     {@code cache.evictions{level=l2}} (explicit deletes) for Redis;</li>
 *     <li>{@code onlineorder.cache.invalidations.received} for L1 drops requested by other instances.</li>
 * </ul>
 * An L2 miss is a database load. Redis errors are counted separately by {@code onlineorder.cache.errors}.
 */
public class TwoLevelCacheMeterBinder implements MeterBinder {

    private final TwoLevelCache cache;
    private final Tags tags;


    public TwoLevelCacheMeterBinder(TwoLevelCache cache, Iterable<Tag> tags) {
        this.cache = cache;
        this.tags = Tags.of(tags).and("cache", cache.getName());
    }


    @Override
    public void bindTo(MeterRegistry registry) {
        new CaffeineCacheMetrics<>(cache.getNativeCache(), cache.getName(), tags.and("level", "l1")).bindTo(registry);

        Tags remoteTags = tags.and("level", "l2");
        FunctionCounter.builder("cache.gets", cache, TwoLevelCache::remoteHitCount)
                .tags(remoteTags.and("result", "hit"))
                .description("Redis (L2) reads that found the entry")
                .register(registry);
        FunctionCounter.builder("cache.gets", cache, TwoLevelCache::remoteMissCount)
                .tags(remoteTags.and("result", "miss"))
                .description("Redis (L2) reads that missed and fell through to the database")
                .register(registry);
        FunctionCounter.builder("cache.puts", cache, TwoLevelCache::remotePutCount)
                .tags(remoteTags)
                .description("Entries written to Redis (L2)")
                .register(registry);
        FunctionCounter.builder("cache.evictions", cache, TwoLevelCache::remoteEvictionCount)
                .tags(remoteTags)
                .description("Entries explicitly deleted from Redis (L2)")
                .register(registry);
        FunctionCounter.builder("onlineorder.cache.invalidations.received", cache, TwoLevelCache::remoteInvalidationsReceivedCount)
                .tags(tags)
                .description("L1 entries or whole L1 caches dropped because another instance changed them")
                .register(registry);
    }


    /**
     * Lets Spring Boot's cache metrics auto-configuration bind every {@link TwoLevelCache} at startup, with
     * the same {@code cache} and {@code cache.manager} tags it uses for the built-in cache types.
     */
    public static class Provider implements CacheMeterBinderProvider<TwoLevelCache> {

        @Override
        public MeterBinder getMeterBinder(TwoLevelCache cache, Iterable<Tag> tags) {
            return new TwoLevelCacheMeterBinder(cache, tags);
        }
    }
}
