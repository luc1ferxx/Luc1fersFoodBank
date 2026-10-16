package com.laioffer.onlineorder.cache;


import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;


import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;


/**
 * A fixed set of {@link TwoLevelCache}s. Unknown cache names resolve to null (Spring then fails the
 * annotated call) instead of creating an untyped cache on the fly: every cache needs a declared value type
 * for its Redis serializer and a key version.
 */
public class TwoLevelCacheManager implements CacheManager {

    private final Map<String, TwoLevelCache> caches;


    public TwoLevelCacheManager(Collection<TwoLevelCache> caches) {
        Map<String, TwoLevelCache> byName = new LinkedHashMap<>();
        for (TwoLevelCache cache : caches) {
            if (byName.putIfAbsent(cache.getName(), cache) != null) {
                throw new IllegalArgumentException("Duplicate cache name " + cache.getName());
            }
        }
        this.caches = Collections.unmodifiableMap(byName);
    }


    @Override
    public Cache getCache(String name) {
        return caches.get(name);
    }


    public TwoLevelCache getTwoLevelCache(String name) {
        return caches.get(name);
    }


    @Override
    public Collection<String> getCacheNames() {
        return caches.keySet();
    }


    public Collection<TwoLevelCache> getCaches() {
        return caches.values();
    }


    /**
     * Drops every L1 entry of this instance without touching Redis.
     */
    public void clearLocal() {
        caches.values().forEach(TwoLevelCache::clearLocal);
    }
}
