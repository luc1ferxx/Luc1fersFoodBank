package com.laioffer.onlineorder.cache;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.SubscriptionListener;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.LongAdder;


/**
 * Applies invalidations published by other instances to this instance's L1. When the subscription is
 * confirmed (at startup and after Lettuce re-subscribes following a lost connection) the whole L1 is
 * cleared, because invalidations sent while this instance was not subscribed were lost.
 */
public class CacheInvalidationListener implements MessageListener, SubscriptionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheInvalidationListener.class);

    private final TwoLevelCacheManager cacheManager;
    private final ObjectMapper objectMapper;
    private final String instanceId;
    private final LongAdder subscriptions = new LongAdder();


    public CacheInvalidationListener(TwoLevelCacheManager cacheManager, ObjectMapper objectMapper, String instanceId) {
        this.cacheManager = cacheManager;
        this.objectMapper = objectMapper;
        this.instanceId = instanceId;
    }


    @Override
    public void onMessage(Message message, byte[] pattern) {
        CacheInvalidationMessage invalidation;
        try {
            invalidation = objectMapper.readValue(message.getBody(), CacheInvalidationMessage.class);
        } catch (IOException ex) {
            LOGGER.warn("Ignoring malformed cache invalidation message: {}", ex.toString());
            return;
        }
        if (instanceId.equals(invalidation.origin())) {
            return;
        }
        TwoLevelCache cache = cacheManager.getTwoLevelCache(invalidation.cache());
        if (cache == null) {
            LOGGER.debug("Ignoring invalidation for unknown cache {}", invalidation.cache());
            return;
        }
        cache.invalidateLocal(invalidation.key());
    }


    @Override
    public void onChannelSubscribed(byte[] channel, long count) {
        subscriptions.increment();
        LOGGER.info("Subscribed to cache invalidation channel {}; clearing local L1 caches",
                new String(channel, StandardCharsets.UTF_8));
        cacheManager.clearLocal();
    }


    long subscriptionCount() {
        return subscriptions.sum();
    }
}
