package com.laioffer.onlineorder.push;


import com.laioffer.onlineorder.fulfilment.AfterCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * What writers call to tell the streams of a recipient that one of its lists changed (ADR 0003 "Publishing and
 * fan-out"). The writer passes the recipient it read from the row it changed; this package reads no table of another
 * module.
 *
 * <p>Called inside the writer's transaction, it publishes after the commit ({@link AfterCommit}), so a change that
 * rolls back signals nothing; outside a transaction it publishes at once. Publishing never fails the writer: it only
 * hands the signal to the fan-out's own thread, and anything that goes wrong is logged and counted
 * ({@code onlineorder.push.publish_failures}). A signal carries no data, so a lost or duplicated one costs at most a
 * late or an extra read.
 */
public class PushSignals {

    private static final Logger LOGGER = LoggerFactory.getLogger(PushSignals.class);

    private final PushFanout fanout;
    private final PushMetrics metrics;


    public PushSignals(PushFanout fanout, PushMetrics metrics) {
        this.fanout = fanout;
        this.metrics = metrics;
    }


    public void publish(PushRecipient recipient, PushTopic topic) {
        AfterCommit.run(() -> publishNow(recipient, topic));
    }


    private void publishNow(PushRecipient recipient, PushTopic topic) {
        try {
            metrics.signalPublished(topic);
            fanout.send(recipient, topic);
        } catch (RuntimeException ex) {
            // An after-commit callback that throws would reach the writer after its commit.
            metrics.publishFailed();
            LOGGER.warn("Could not publish push signal {} for {}", topic.wireName(), recipient, ex);
        }
    }
}
