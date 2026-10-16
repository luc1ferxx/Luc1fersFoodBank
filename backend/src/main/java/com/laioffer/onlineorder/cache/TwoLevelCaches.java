package com.laioffer.onlineorder.cache;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.data.redis.connection.RedisConnectionFactory;


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
     */
    public static ObjectMapper objectMapper() {
        return JsonMapper.builder().build();
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
                        errorHandler
                ))
                .toList();
        return new TwoLevelCacheManager(caches);
    }
}
