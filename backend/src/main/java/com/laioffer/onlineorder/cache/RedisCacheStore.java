package com.laioffer.onlineorder.cache;


import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;


import java.time.Duration;
import java.util.ArrayList;
import java.util.List;


/**
 * Redis L2 for one cache. Values are written as plain JSON bound to the cache's declared type (no default
 * typing, no {@code @class} property), so only the expected DTO shape can be read back. Keys are
 * {@code <prefix>:<version>:<cache>::<key>}; bumping the version when the DTO shape changes makes old and
 * new application versions use disjoint keys during a rolling deploy. Every write gets a jittered TTL.
 */
public class RedisCacheStore implements RemoteCacheStore {

    private static final int CLEAR_BATCH_SIZE = 500;

    private final RedisTemplate<String, Object> redisTemplate;
    private final String keyPrefix;
    private final Duration timeToLive;
    private final TtlJitter ttlJitter;


    public RedisCacheStore(
            RedisConnectionFactory connectionFactory,
            String keyPrefix,
            ObjectMapper objectMapper,
            JavaType valueType,
            Duration timeToLive,
            TtlJitter ttlJitter
    ) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setEnableDefaultSerializer(false);
        template.setKeySerializer(StringRedisSerializer.UTF_8);
        template.setValueSerializer(new Jackson2JsonRedisSerializer<>(objectMapper, valueType));
        template.afterPropertiesSet();
        this.redisTemplate = template;
        this.keyPrefix = keyPrefix;
        this.timeToLive = timeToLive;
        this.ttlJitter = ttlJitter;
    }


    /**
     * Builds the namespace for one cache, for example {@code onlineorder:cache:v1:menu::}.
     */
    public static String keyPrefix(String namespace, String version, String cacheName) {
        return namespace + ":" + version + ":" + cacheName + "::";
    }


    public String redisKey(String key) {
        return keyPrefix + key;
    }


    @Override
    public Object get(String key) {
        return redisTemplate.opsForValue().get(redisKey(key));
    }


    @Override
    public void put(String key, Object value) {
        redisTemplate.opsForValue().set(redisKey(key), value, ttlJitter.apply(timeToLive));
    }


    @Override
    public void evict(String key) {
        redisTemplate.delete(redisKey(key));
    }


    /**
     * Deletes this cache's keys with SCAN rather than KEYS so a large keyspace does not block Redis.
     */
    @Override
    public void clear() {
        ScanOptions options = ScanOptions.scanOptions().match(keyPrefix + "*").count(CLEAR_BATCH_SIZE).build();
        List<String> batch = new ArrayList<>(CLEAR_BATCH_SIZE);
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() == CLEAR_BATCH_SIZE) {
                    redisTemplate.delete(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            redisTemplate.delete(batch);
        }
    }
}
