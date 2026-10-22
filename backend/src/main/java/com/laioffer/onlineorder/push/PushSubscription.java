package com.laioffer.onlineorder.push;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;


import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;


/**
 * Every instance's subscription to the push channel ({@code app.push.fanout=redis}): hands each signal to this
 * instance's streams of its recipient, on the "push-delivery" thread.
 *
 * <p>Redis is not a startup dependency, like the cache's invalidation subscription: the listener container is started
 * on a background thread ("push-subscriber") and retried with exponential backoff until the first subscription
 * succeeds. From then on Lettuce reconnects after a lost connection or a Redis restart and subscribes the channel
 * again by itself ({@link #onChannelSubscribed} counts each subscription). Signals published while this instance was
 * not subscribed are lost; the clients' fallback polling covers them.
 */
public class PushSubscription implements SmartLifecycle, DisposableBean, MessageListener, SubscriptionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PushSubscription.class);

    private final PushStreamRegistry registry;
    private final ObjectMapper objectMapper;
    private final String channel;
    private final Duration initialRetryDelay;
    private final Duration maxRetryDelay;
    private final PushWorker deliveryWorker = new PushWorker("push-delivery");
    private final RedisMessageListenerContainer container;
    private final LongAdder subscriptions = new LongAdder();
    private final Object monitor = new Object();

    private ScheduledExecutorService starter;
    private volatile boolean running;
    private Duration nextRetryDelay;


    public PushSubscription(
            RedisConnectionFactory connectionFactory,
            PushStreamRegistry registry,
            ObjectMapper objectMapper,
            String channel,
            Duration initialRetryDelay,
            Duration maxRetryDelay
    ) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.channel = channel;
        this.initialRetryDelay = initialRetryDelay;
        this.maxRetryDelay = maxRetryDelay;
        this.nextRetryDelay = initialRetryDelay;
        RedisMessageListenerContainer listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(connectionFactory);
        listenerContainer.addMessageListener(this, new ChannelTopic(channel));
        // A full delivery queue drops the message (at most once, best effort) instead of failing the listener.
        listenerContainer.setTaskExecutor(task -> {
            if (!deliveryWorker.submit(task)) {
                LOGGER.debug("Push signal dropped: the delivery queue is full");
            }
        });
        listenerContainer.afterPropertiesSet();
        this.container = listenerContainer;
    }


    @Override
    public void onMessage(Message message, byte[] pattern) {
        PushRecipient recipient;
        PushTopic topic;
        try {
            PushMessage signal = objectMapper.readValue(message.getBody(), PushMessage.class);
            recipient = PushRecipient.parse(signal.recipient());
            topic = PushTopic.fromWireName(signal.topic());
        } catch (JacksonException | IllegalArgumentException ex) {
            LOGGER.warn("Ignoring a malformed push signal on {}: {}", channel, ex.toString());
            return;
        }
        registry.deliver(recipient, topic);
    }


    @Override
    public void onChannelSubscribed(byte[] subscribedChannel, long count) {
        subscriptions.increment();
        LOGGER.info("Subscribed to push channel {}", channel);
    }


    /**
     * Confirmed (re)subscriptions to the channel.
     */
    long subscriptionCount() {
        return subscriptions.sum();
    }


    @Override
    public void start() {
        synchronized (monitor) {
            if (running) {
                return;
            }
            running = true;
            nextRetryDelay = initialRetryDelay;
            starter = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "push-subscriber");
                thread.setDaemon(true);
                return thread;
            });
            starter.execute(this::subscribe);
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
            toShutDown = starter;
            starter = null;
        }
        toShutDown.shutdownNow();
        stopContainerQuietly();
    }


    @Override
    public boolean isRunning() {
        return running;
    }


    @Override
    public void destroy() throws Exception {
        stop();
        container.destroy();
        deliveryWorker.shutdown();
    }


    private void subscribe() {
        if (!running) {
            return;
        }
        try {
            container.start();
        } catch (RuntimeException ex) {
            stopContainerQuietly();
            scheduleRetry(ex);
        }
    }


    private void scheduleRetry(RuntimeException cause) {
        synchronized (monitor) {
            if (!running || starter == null) {
                return;
            }
            Duration delay = nextRetryDelay;
            Duration doubled = nextRetryDelay.multipliedBy(2);
            nextRetryDelay = doubled.compareTo(maxRetryDelay) > 0 ? maxRetryDelay : doubled;
            LOGGER.warn(
                    "Could not subscribe to push channel {} ({}); clients poll until it is available. Retrying in {}",
                    channel, cause.toString(), delay);
            starter.schedule(this::subscribe, delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }


    private void stopContainerQuietly() {
        try {
            container.stop();
        } catch (RuntimeException ex) {
            LOGGER.debug("Stopping the push listener container failed", ex);
        }
    }
}
