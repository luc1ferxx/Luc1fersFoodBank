package com.laioffer.onlineorder.push;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;


import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
 *
 * <p>Nothing tells a subscription that was lost for good (there is no callback for the loss), so the instance probes
 * the whole path itself: every {@code app.push.probe-interval} the same thread publishes
 * {@code {"probe":"<instance id>","seq":N}} on the ":probe" sibling of the channel, which the same listener container
 * subscribes to, and the gauge {@code onlineorder.push.subscribed} reads 1 while the instance's own probe comes back.
 * A probe that has not come back by the next one, or a publish that fails, marks the subscription as not receiving
 * (0) until a probe comes back again; other instances' probes are ignored. The signal channel's message format is
 * untouched.
 */
public class PushSubscription implements SmartLifecycle, DisposableBean, MessageListener, SubscriptionListener {

    static final String PROBE_CHANNEL_SUFFIX = ":probe";

    private static final Logger LOGGER = LoggerFactory.getLogger(PushSubscription.class);

    private final StringRedisTemplate redisTemplate;
    private final PushStreamRegistry registry;
    private final ObjectMapper objectMapper;
    private final String channel;
    private final byte[] channelBytes;
    private final String probeChannel;
    private final byte[] probeChannelBytes;
    private final String instanceId = UUID.randomUUID().toString();
    private final Duration probeInterval;
    private final Duration initialRetryDelay;
    private final Duration maxRetryDelay;
    private final PushWorker deliveryWorker = new PushWorker("push-delivery");
    private final RedisMessageListenerContainer container;
    private final LongAdder subscriptions = new LongAdder();
    private final Object monitor = new Object();
    private final AtomicLong probesPublished = new AtomicLong();
    private final AtomicLong probesReturned = new AtomicLong();
    private final Object receivingMonitor = new Object();

    private ScheduledExecutorService starter;
    private volatile boolean running;
    private volatile boolean receiving;
    // Guarded by monitor.
    private Duration nextRetryDelay;
    private ScheduledFuture<?> probeTask;
    // Guarded by receivingMonitor.
    private boolean confirmedSinceStart;


    public PushSubscription(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate redisTemplate,
            PushStreamRegistry registry,
            PushMetrics metrics,
            ObjectMapper objectMapper,
            String channel,
            Duration probeInterval,
            Duration initialRetryDelay,
            Duration maxRetryDelay
    ) {
        this.redisTemplate = redisTemplate;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.channel = channel;
        this.channelBytes = channel.getBytes(StandardCharsets.UTF_8);
        this.probeChannel = channel + PROBE_CHANNEL_SUFFIX;
        this.probeChannelBytes = probeChannel.getBytes(StandardCharsets.UTF_8);
        this.probeInterval = probeInterval;
        this.initialRetryDelay = initialRetryDelay;
        this.maxRetryDelay = maxRetryDelay;
        this.nextRetryDelay = initialRetryDelay;
        RedisMessageListenerContainer listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(connectionFactory);
        listenerContainer.addMessageListener(this, new ChannelTopic(channel));
        listenerContainer.addMessageListener(this, new ChannelTopic(probeChannel));
        // A full delivery queue drops the message (at most once, best effort) instead of failing the listener.
        listenerContainer.setTaskExecutor(task -> {
            if (!deliveryWorker.submit(task)) {
                LOGGER.debug("Push signal dropped: the delivery queue is full");
            }
        });
        listenerContainer.afterPropertiesSet();
        this.container = listenerContainer;
        metrics.bindSubscribed(() -> receiving ? 1 : 0);
    }


    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (Arrays.equals(message.getChannel(), probeChannelBytes)) {
            onProbe(message);
            return;
        }
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


    /**
     * Another instance's probe is ignored silently; this instance's own marks the subscription as receiving.
     */
    private void onProbe(Message message) {
        PushProbe probe;
        try {
            probe = objectMapper.readValue(message.getBody(), PushProbe.class);
        } catch (JacksonException ex) {
            LOGGER.debug("Ignoring a malformed push probe on {}: {}", probeChannel, ex.toString());
            return;
        }
        if (probe == null || probe.probe() == null || probe.seq() == null) {
            LOGGER.debug("Ignoring a push probe without an instance id or sequence on {}", probeChannel);
            return;
        }
        if (!instanceId.equals(probe.probe())) {
            return;
        }
        probesReturned.accumulateAndGet(probe.seq(), Math::max);
        markReceiving();
    }


    @Override
    public void onChannelSubscribed(byte[] subscribedChannel, long count) {
        if (!Arrays.equals(subscribedChannel, channelBytes)) {
            return;
        }
        subscriptions.increment();
        LOGGER.info("Subscribed to push channel {}", channel);
    }


    /**
     * Confirmed (re)subscriptions to the signal channel (the probe channel's are not counted).
     */
    long subscriptionCount() {
        return subscriptions.sum();
    }


    /**
     * Whether this instance's last probe came back: what {@code onlineorder.push.subscribed} reads.
     */
    boolean isReceiving() {
        return receiving;
    }


    @Override
    public void start() {
        synchronized (monitor) {
            if (running) {
                return;
            }
            running = true;
            nextRetryDelay = initialRetryDelay;
            synchronized (receivingMonitor) {
                confirmedSinceStart = false;
            }
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
            if (probeTask != null) {
                probeTask.cancel(false);
                probeTask = null;
            }
        }
        // Deliberate, so no warning: the gauge reads 0 while the instance does not listen. Before the shutdown
        // interrupts a probe publish in flight, which would otherwise read as a loss.
        synchronized (receivingMonitor) {
            receiving = false;
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
            return;
        }
        scheduleProbes();
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


    /**
     * The first probe goes at once, the next ones an interval after the previous publish returned, on the
     * "push-subscriber" thread (a plain daemon executor, not a TaskScheduler bean). A fixed delay, not a fixed rate:
     * after a hung Redis (each publish waiting for the command timeout) a fixed rate would run the missed ticks
     * back-to-back, each judging a probe published a moment before. {@link #stop} cancels them.
     */
    private void scheduleProbes() {
        synchronized (monitor) {
            if (!running || starter == null || probeTask != null) {
                return;
            }
            long intervalMillis = Math.max(1L, probeInterval.toMillis());
            probeTask = starter.scheduleWithFixedDelay(this::probe, 0L, intervalMillis, TimeUnit.MILLISECONDS);
        }
    }


    /**
     * One probe. Never throws: an exception would cancel every later run of a ScheduledExecutorService task.
     */
    private void probe() {
        if (!running) {
            return;
        }
        long previous = probesPublished.get();
        if (previous > 0 && probesReturned.get() < previous) {
            markNotReceiving();
        }
        long seq = probesPublished.incrementAndGet();
        try {
            String payload = objectMapper.writeValueAsString(new PushProbe(instanceId, seq));
            redisTemplate.convertAndSend(probeChannel, payload);
        } catch (RuntimeException ex) {
            LOGGER.debug("Push probe {} could not be published on {}: {}", seq, probeChannel, ex.toString());
            markNotReceiving();
        }
    }


    private void markReceiving() {
        synchronized (receivingMonitor) {
            if (!running || receiving) {
                return;
            }
            receiving = true;
            if (confirmedSinceStart) {
                LOGGER.info("Push subscription restored on channel {}: the probe came back", channel);
            } else {
                confirmedSinceStart = true;
                LOGGER.debug("Push subscription confirmed on channel {}: the probe came back", channel);
            }
        }
    }


    private void markNotReceiving() {
        synchronized (receivingMonitor) {
            if (!receiving) {
                return;
            }
            receiving = false;
            LOGGER.warn("Push subscription lost on channel {}: this instance's probe did not come back within {}; "
                    + "signals published meanwhile do not reach its streams, which poll every 60 s",
                    channel, probeInterval);
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
