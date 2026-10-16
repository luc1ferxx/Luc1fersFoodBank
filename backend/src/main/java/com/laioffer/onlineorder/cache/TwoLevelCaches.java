package com.laioffer.onlineorder.cache;


import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;


import java.util.Collection;
import java.util.List;


/**
 * Builds Redis-backed two-level caches.
 */
public final class TwoLevelCaches {

    private TwoLevelCaches() {
    }


    /**
     * The JSON mapper for cached values and invalidation messages. Deliberately separate from the HTTP
     * ObjectMapper (snake_case) so changing the API's JSON settings cannot silently change what is stored in
     * Redis, and without default typing: each cache binds its values to one declared type.
     * <p>
     * Two Jackson 3 defaults are switched back to what Jackson 2 did: properties keep their declaration order
     * (Jackson 3 sorts them alphabetically), so the stored JSON is byte for byte what Spring Boot 3 instances wrote
     * and the {@code v1} entries stay shared across the upgrade without a key version bump; and an unknown property
     * fails the read (Jackson 3 ignores it), so an entry of another shape is still treated as a miss instead of
     * being read as a half-empty DTO.
     */
    public static ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }


    public static TwoLevelCacheManager redisBacked(
            RedisConnectionFactory connectionFactory,
            ObjectMapper objectMapper,
            TwoLevelCacheSettings settings,
            Collection<TwoLevelCacheSpec> specs,
            CacheInvalidationPublisher invalidationPublisher,
            CacheErrorHandler errorHandler
    ) {
        TtlJitter ttlJitter = new TtlJitter(settings.remoteTtlJitter());
        // One breaker for all caches: they share one Redis, so one cache's failure means the others' calls
        // would wait for the same timeout.
        RemoteCacheCircuitBreaker circuitBreaker = new RemoteCacheCircuitBreaker(
                settings.remoteCircuitOpenDuration(),
                settings.remoteSlowCallThreshold()
        );
        List<TwoLevelCache> caches = specs.stream()
                .map(spec -> new TwoLevelCache(
                        spec.name(),
                        Caffeine.newBuilder()
                                .expireAfterWrite(settings.localTtl())
                                .maximumSize(spec.localMaximumSize())
                                .recordStats()
                                .build(),
                        new RedisCacheStore(
                                connectionFactory,
                                RedisCacheStore.keyPrefix(settings.keyNamespace(), spec.version(), spec.name()),
                                objectMapper,
                                spec.valueType(),
                                settings.remoteTtl(),
                                ttlJitter
                        ),
                        invalidationPublisher,
                        errorHandler,
                        circuitBreaker
                ))
                .toList();
        return new TwoLevelCacheManager(caches);
    }
}
