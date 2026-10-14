package com.laioffer.onlineorder;


import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Configuration;


@Configuration
public class CacheConfig implements CachingConfigurer {

    private final ObjectProvider<ApplicationMetricsService> metricsService;


    public CacheConfig(ObjectProvider<ApplicationMetricsService> metricsService) {
        this.metricsService = metricsService;
    }


    @Override
    public CacheErrorHandler errorHandler() {
        return new FallbackCacheErrorHandler(metricsService);
    }


    /**
     * Treats the cache as an optimization: a failed read is a cache miss, so the annotated method runs
     * against the database, and failed writes or evictions are skipped. Entries expire after the cache
     * TTL, which bounds how long a skipped eviction can serve stale data.
     */
    static class FallbackCacheErrorHandler implements CacheErrorHandler {

        private static final Logger LOGGER = LoggerFactory.getLogger(FallbackCacheErrorHandler.class);

        private final ObjectProvider<ApplicationMetricsService> metricsService;


        FallbackCacheErrorHandler(ObjectProvider<ApplicationMetricsService> metricsService) {
            this.metricsService = metricsService;
        }


        @Override
        public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
            record("get", cache, key, exception);
        }


        @Override
        public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
            record("put", cache, key, exception);
        }


        @Override
        public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
            record("evict", cache, key, exception);
        }


        @Override
        public void handleCacheClearError(RuntimeException exception, Cache cache) {
            record("clear", cache, null, exception);
        }


        private void record(String operation, Cache cache, Object key, RuntimeException exception) {
            String cacheName = cache == null ? null : cache.getName();
            LOGGER.warn("Cache {} failed for cache {}; falling back to the data source: {}", operation, cacheName, exception.toString());
            LOGGER.debug("Cache {} failure details for cache {} and key {}", operation, cacheName, key, exception);
            metricsService.ifAvailable(metrics -> metrics.recordCacheError(cacheName, operation));
        }
    }
}
