package com.laioffer.onlineorder.cache;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;


import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


/**
 * Owns the Redis pub/sub subscription for L1 invalidations without making Redis a startup dependency.
 * <p>
 * {@link RedisMessageListenerContainer#start()} throws when Redis is unreachable, which would fail application
 * startup (the cache itself already degrades to the database). This lifecycle bean starts the container on a
 * background thread instead and retries with exponential backoff until the first subscription succeeds; from
 * then on the container and Lettuce handle reconnects and re-subscription themselves. Until the subscription
 * exists, L1 entries are bounded by their TTL.
 */
public class CacheInvalidationSubscription implements SmartLifecycle, DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheInvalidationSubscription.class);

    private final RedisMessageListenerContainer container;
    private final String channel;
    private final Duration initialRetryDelay;
    private final Duration maxRetryDelay;
    private final Object monitor = new Object();

    private ScheduledExecutorService executor;
    private volatile boolean running;
    private volatile boolean subscribed;
    private Duration nextRetryDelay;


    public CacheInvalidationSubscription(
            RedisConnectionFactory connectionFactory,
            CacheInvalidationListener listener,
            String channel,
            Duration initialRetryDelay,
            Duration maxRetryDelay
    ) {
        RedisMessageListenerContainer listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(connectionFactory);
        listenerContainer.addMessageListener(listener, new ChannelTopic(channel));
        listenerContainer.afterPropertiesSet();
        this.container = listenerContainer;
        this.channel = channel;
        this.initialRetryDelay = initialRetryDelay;
        this.maxRetryDelay = maxRetryDelay;
        this.nextRetryDelay = initialRetryDelay;
    }


    @Override
    public void start() {
        synchronized (monitor) {
            if (running) {
                return;
            }
            running = true;
            nextRetryDelay = initialRetryDelay;
            executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "cache-invalidation-subscriber");
                thread.setDaemon(true);
                return thread;
            });
            executor.execute(this::subscribe);
        }
    }


    @Override
    public void stop() {
        ScheduledExecutorService toShutDown;
        synchronized (monitor) {
            if (!running) {
                return;
            }
            running = false;
            toShutDown = executor;
            executor = null;
        }
        toShutDown.shutdownNow();
        stopContainerQuietly();
        subscribed = false;
    }


    @Override
    public boolean isRunning() {
        return running;
    }


    /**
     * Whether the listener container has been started successfully (it may still be reconnecting).
     */
    public boolean isSubscribed() {
        return subscribed;
    }


    @Override
    public void destroy() throws Exception {
        stop();
        container.destroy();
    }


    private void subscribe() {
        if (!running) {
            return;
        }
        try {
            container.start();
            subscribed = true;
            LOGGER.info("Listening for cache invalidations on Redis channel {}", channel);
        } catch (RuntimeException ex) {
            stopContainerQuietly();
            scheduleRetry(ex);
        }
    }


    private void scheduleRetry(RuntimeException cause) {
        synchronized (monitor) {
            if (!running || executor == null) {
                return;
            }
            Duration delay = nextRetryDelay;
            Duration doubled = nextRetryDelay.multipliedBy(2);
            nextRetryDelay = doubled.compareTo(maxRetryDelay) > 0 ? maxRetryDelay : doubled;
            LOGGER.warn(
                    "Could not subscribe to cache invalidation channel {} ({}); L1 entries expire by TTL until it is available. Retrying in {}",
                    channel,
                    cause.toString(),
                    delay
            );
            executor.schedule(this::subscribe, delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }


    private void stopContainerQuietly() {
        try {
            container.stop();
        } catch (RuntimeException ex) {
            LOGGER.debug("Stopping the cache invalidation listener container failed", ex);
        }
    }
}
