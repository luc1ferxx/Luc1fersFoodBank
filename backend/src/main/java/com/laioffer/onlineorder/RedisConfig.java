package com.laioffer.onlineorder;


import com.laioffer.onlineorder.cache.CacheInvalidationListener;
import com.laioffer.onlineorder.cache.CacheInvalidationSubscription;
import com.laioffer.onlineorder.cache.CatalogCaches;
import com.laioffer.onlineorder.cache.RedisCacheInvalidationPublisher;
import com.laioffer.onlineorder.cache.TwoLevelCacheManager;
import com.laioffer.onlineorder.cache.TwoLevelCacheMeterBinder;
import com.laioffer.onlineorder.cache.TwoLevelCacheSettings;
import com.laioffer.onlineorder.cache.TwoLevelCaches;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;


import java.time.Duration;
import java.util.UUID;


/**
 * Two-level catalog cache (Caffeine L1 per instance, Redis L2 shared, Redis pub/sub for L1 invalidation).
 * Active for {@code spring.cache.type=redis} (the default); any other type, e.g. {@code none} in tests, falls
 * back to Spring Boot's cache auto-configuration.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
public class RedisConfig {

    /**
     * Identifies this JVM in invalidation messages so it can skip the ones it published itself.
     */
    private final String instanceId = UUID.randomUUID().toString();
    private final ObjectMapper cacheObjectMapper = TwoLevelCaches.objectMapper();


    @Bean
    TwoLevelCacheSettings twoLevelCacheSettings(
            @Value("${app.cache.key-namespace:onlineorder:cache}") String keyNamespace,
            @Value("${app.cache.l1.ttl:30s}") Duration localTtl,
            @Value("${spring.cache.redis.time-to-live:10m}") Duration remoteTtl,
            @Value("${app.cache.l2.ttl-jitter:0.15}") double remoteTtlJitter,
            @Value("${app.cache.l2.circuit-breaker.open-duration:5s}") Duration remoteCircuitOpenDuration,
            @Value("${app.cache.l2.circuit-breaker.slow-call-threshold:200ms}") Duration remoteSlowCallThreshold
    ) {
        return new TwoLevelCacheSettings(
                keyNamespace,
                localTtl,
                remoteTtl,
                remoteTtlJitter,
                remoteCircuitOpenDuration,
                remoteSlowCallThreshold
        );
    }


    @Bean
    TwoLevelCacheManager cacheManager(
            RedisConnectionFactory redisConnectionFactory,
            StringRedisTemplate stringRedisTemplate,
            TwoLevelCacheSettings settings,
            ObjectProvider<ApplicationMetricsService> metricsService,
            @Value("${app.cache.l1.menu-maximum-size:10000}") long menuLocalMaximumSize
    ) {
        return TwoLevelCaches.redisBacked(
                redisConnectionFactory,
                cacheObjectMapper,
                settings,
                CatalogCaches.specs(cacheObjectMapper, menuLocalMaximumSize),
                new RedisCacheInvalidationPublisher(
                        stringRedisTemplate,
                        cacheObjectMapper,
                        settings.invalidationChannel(),
                        instanceId
                ),
                new CacheConfig.FallbackCacheErrorHandler(metricsService)
        );
    }


    /**
     * Subscribes to L1 invalidations from other instances. Redis being unreachable does not fail startup: the
     * subscription is retried in the background, and meanwhile L1 entries expire after {@code app.cache.l1.ttl}.
     */
    @Bean
    CacheInvalidationSubscription cacheInvalidationSubscription(
            RedisConnectionFactory redisConnectionFactory,
            TwoLevelCacheManager cacheManager,
            TwoLevelCacheSettings settings,
            @Value("${app.cache.invalidation.retry-initial-delay:1s}") Duration retryInitialDelay,
            @Value("${app.cache.invalidation.retry-max-delay:30s}") Duration retryMaxDelay
    ) {
        return new CacheInvalidationSubscription(
                redisConnectionFactory,
                new CacheInvalidationListener(cacheManager, cacheObjectMapper, instanceId),
                settings.invalidationChannel(),
                retryInitialDelay,
                retryMaxDelay
        );
    }


    @Bean
    TwoLevelCacheMeterBinder.Provider twoLevelCacheMeterBinderProvider() {
        return new TwoLevelCacheMeterBinder.Provider();
    }
}
