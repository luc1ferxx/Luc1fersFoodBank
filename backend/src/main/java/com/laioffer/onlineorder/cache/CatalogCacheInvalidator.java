package com.laioffer.onlineorder.cache;


import com.laioffer.onlineorder.datasource.ReplicaRoutingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;


import java.time.Duration;
import java.time.Instant;


/**
 * Cache-aside invalidation for catalog writes: the writer updates the database and deletes the cache entry
 * only after the transaction commits. Deleting earlier would let a concurrent reader reload the old row and
 * cache it again before the commit; on rollback nothing is deleted at all.
 * <p>
 * A reader that loaded the old row just before the commit can still write it back to Redis just after the
 * first delete. A second, delayed delete ({@code app.cache.delayed-double-delete}, default 1s) removes that
 * entry; set it to 0 to disable.
 * <p>
 * With read replicas (catalog loads read a replica) a reader can load the old row from a replica that has not replayed
 * the write yet, for as long as the replica may lag. The second delete then waits at least that long
 * ({@link ReplicaRoutingProperties#maxStaleness()}: max-lag plus one lag-check interval), so the stale entry is gone
 * once every routable replica has the write.
 */
@Component
public final class CatalogCacheInvalidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(CatalogCacheInvalidator.class);

    private final CacheManager cacheManager;
    private final ObjectProvider<TaskScheduler> taskScheduler;
    private final Duration secondDeleteDelay;


    @Autowired
    public CatalogCacheInvalidator(
            CacheManager cacheManager,
            ObjectProvider<TaskScheduler> taskScheduler,
            @Value("${app.cache.delayed-double-delete:1s}") Duration secondDeleteDelay,
            ObjectProvider<ReplicaRoutingProperties> replicaRouting
    ) {
        this.cacheManager = cacheManager;
        this.taskScheduler = taskScheduler;
        this.secondDeleteDelay = effectiveSecondDeleteDelay(secondDeleteDelay, replicaRouting.getIfAvailable());
    }


    public CatalogCacheInvalidator(
            CacheManager cacheManager,
            ObjectProvider<TaskScheduler> taskScheduler,
            Duration secondDeleteDelay
    ) {
        this.cacheManager = cacheManager;
        this.taskScheduler = taskScheduler;
        this.secondDeleteDelay = secondDeleteDelay;
    }


    /**
     * The configured delay, raised to the replicas' maximum staleness when read replicas are configured; a disabled
     * second delete (0) stays disabled.
     */
    static Duration effectiveSecondDeleteDelay(Duration configured, ReplicaRoutingProperties replicaRouting) {
        if (replicaRouting == null || configured.isZero() || configured.isNegative()) {
            return configured;
        }
        Duration replicaStaleness = replicaRouting.maxStaleness();
        return replicaStaleness.compareTo(configured) > 0 ? replicaStaleness : configured;
    }


    Duration secondDeleteDelay() {
        return secondDeleteDelay;
    }


    public void evictMenuAfterCommit(long restaurantId) {
        evictAfterCommit(CatalogCaches.MENU, restaurantId);
    }


    public void evictRestaurantsAfterCommit() {
        evictAfterCommit(CatalogCaches.RESTAURANTS, CatalogCaches.ALL_RESTAURANTS_KEY);
    }


    void evictAfterCommit(String cacheName, Object key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No surrounding transaction: the write has already been committed.
            evictNowAndAgainLater(cacheName, key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictNowAndAgainLater(cacheName, key);
            }
        });
    }


    private void evictNowAndAgainLater(String cacheName, Object key) {
        evict(cacheName, key);
        if (secondDeleteDelay.isZero() || secondDeleteDelay.isNegative()) {
            return;
        }
        TaskScheduler scheduler = taskScheduler.getIfUnique();
        if (scheduler == null) {
            return;
        }
        try {
            scheduler.schedule(() -> evict(cacheName, key), Instant.now().plus(secondDeleteDelay));
        } catch (RuntimeException ex) {
            LOGGER.warn("Could not schedule the delayed second delete for cache {} key {}: {}", cacheName, key, ex.toString());
        }
    }


    /**
     * Never throws: this runs after the commit, so a failure here must not turn a successful write into an
     * error response. {@link TwoLevelCache} already reports Redis failures; the entry then expires with its TTL.
     */
    private void evict(String cacheName, Object key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return;
        }
        try {
            cache.evict(key);
        } catch (RuntimeException ex) {
            LOGGER.warn("Could not evict cache {} key {} after commit: {}", cacheName, key, ex.toString());
        }
    }
}
