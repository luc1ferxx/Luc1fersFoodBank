package com.laioffer.onlineorder.push;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;


/**
 * {@code app.push.fanout=redis} (the default): publishes each signal on the Redis pub/sub channel, from which every
 * instance's {@link PushSubscription}, this one's included, hands it to its local streams. The publish runs on the
 * "push-publisher" thread, so a slow or unreachable Redis (each call waits up to {@code spring.data.redis.timeout})
 * never holds a writer. It fails open: a failed publish is logged and counted
 * ({@code onlineorder.push.publish_failures}) and the signal is lost, which the clients' fallback polling covers.
 */
public class RedisPushFanout implements PushFanout, DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisPushFanout.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String channel;
    private final PushMetrics metrics;
    private final PushWorker worker;
    // Only touched by the publisher thread.
    private int consecutiveFailures;


    public RedisPushFanout(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            String channel,
            PushMetrics metrics
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.channel = channel;
        this.metrics = metrics;
        this.worker = new PushWorker("push-publisher");
    }


    @Override
    public void send(PushRecipient recipient, PushTopic topic) {
        if (!worker.submit(() -> publish(recipient, topic))) {
            metrics.publishFailed();
            LOGGER.debug("Push signal {} for {} dropped: the publish queue is full", topic.wireName(), recipient);
        }
    }


    @Override
    public void destroy() {
        worker.shutdown();
    }


    /**
     * During an outage only the first failure of a streak is logged as a warning; the streak's end is logged once.
     */
    private void publish(PushRecipient recipient, PushTopic topic) {
        try {
            String payload = objectMapper.writeValueAsString(new PushMessage(recipient.wireName(), topic.wireName()));
            redisTemplate.convertAndSend(channel, payload);
            if (consecutiveFailures > 0) {
                LOGGER.info("Push signals reach Redis channel {} again after {} failed publish(es)",
                        channel, consecutiveFailures);
                consecutiveFailures = 0;
            }
        } catch (RuntimeException ex) {
            metrics.publishFailed();
            consecutiveFailures++;
            if (consecutiveFailures == 1) {
                LOGGER.warn("Could not publish a push signal on Redis channel {}; clients fall back to polling: {}",
                        channel, ex.toString());
            } else {
                LOGGER.debug("Push signal publish failed {} times in a row: {}", consecutiveFailures, ex.toString());
            }
        }
    }
}
