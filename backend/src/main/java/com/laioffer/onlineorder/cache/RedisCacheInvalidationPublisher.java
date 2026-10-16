package com.laioffer.onlineorder.cache;


import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;


/**
 * Publishes L1 invalidations on a Redis pub/sub channel. Pub/sub is fire-and-forget: an instance that is
 * disconnected misses the message, which is why L1 entries also carry a short TTL and every instance clears
 * its L1 when its subscription is (re)established.
 */
public class RedisCacheInvalidationPublisher implements CacheInvalidationPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String channel;
    private final String instanceId;


    public RedisCacheInvalidationPublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            String channel,
            String instanceId
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.channel = channel;
        this.instanceId = instanceId;
    }


    @Override
    public void publishEvict(String cacheName, String key) {
        publish(new CacheInvalidationMessage(instanceId, cacheName, key));
    }


    @Override
    public void publishClear(String cacheName) {
        publish(new CacheInvalidationMessage(instanceId, cacheName, null));
    }


    private void publish(CacheInvalidationMessage message) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Could not serialize cache invalidation message", ex);
        }
        redisTemplate.convertAndSend(channel, payload);
    }
}
