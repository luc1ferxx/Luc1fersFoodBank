package com.laioffer.onlineorder.push;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;


/**
 * {@code app.push.fanout=local}: delivers each signal to this instance's streams only, on the "push-delivery" thread.
 * For tests, the h2 profile and single-instance runs; with several instances a stream misses the signals written on
 * the others and relies on its fallback polling.
 */
public class LocalPushFanout implements PushFanout, DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalPushFanout.class);

    private final PushStreamRegistry registry;
    private final PushMetrics metrics;
    private final PushWorker worker = new PushWorker("push-delivery");


    public LocalPushFanout(PushStreamRegistry registry, PushMetrics metrics) {
        this.registry = registry;
        this.metrics = metrics;
    }


    @Override
    public void send(PushRecipient recipient, PushTopic topic) {
        if (!worker.submit(() -> registry.deliver(recipient, topic))) {
            metrics.publishFailed();
            LOGGER.debug("Push signal {} for {} dropped: the delivery queue is full", topic.wireName(), recipient);
        }
    }


    @Override
    public void destroy() {
        worker.shutdown();
    }
}
