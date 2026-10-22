package com.laioffer.onlineorder.push;


import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;


import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;


/**
 * The push meters (ADR 0003 "Observability"). The counters are registered at zero, so the first signal of a topic
 * shows as an increase.
 */
public class PushMetrics {

    static final String STREAMS = "onlineorder.push.streams";
    static final String SUBSCRIBED = "onlineorder.push.subscribed";
    static final String SIGNALS = "onlineorder.push.signals";
    static final String DELIVERIES = "onlineorder.push.deliveries";
    static final String PUBLISH_FAILURES = "onlineorder.push.publish_failures";

    private final MeterRegistry meterRegistry;
    private final Map<PushTopic, Counter> signals = new EnumMap<>(PushTopic.class);
    private final Map<PushTopic, Counter> deliveries = new EnumMap<>(PushTopic.class);
    private final Counter publishFailures;


    public PushMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        for (PushTopic topic : PushTopic.values()) {
            signals.put(topic, Counter.builder(SIGNALS)
                    .description("Push signals published (after their transaction committed)")
                    .tag("topic", topic.wireName())
                    .register(meterRegistry));
            deliveries.put(topic, Counter.builder(DELIVERIES)
                    .description("Push signals written to a stream of this instance")
                    .tag("topic", topic.wireName())
                    .register(meterRegistry));
        }
        this.publishFailures = Counter.builder(PUBLISH_FAILURES)
                .description("Push signals that could not be published (Redis unavailable, queue full)")
                .register(meterRegistry);
    }


    /**
     * Registers the gauge of the open streams of this instance.
     */
    void bindStreams(Supplier<Number> openStreams) {
        Gauge.builder(STREAMS, openStreams)
                .description("Open push streams on this instance")
                .strongReference(true)
                .register(meterRegistry);
    }


    /**
     * Registers the gauge that says whether this instance receives the push channel: 1 while its own probe comes back
     * ({@link PushSubscription}), else 0; the local fan-out, where every signal reaches the local streams, binds a
     * constant 1 so the series exists in every configuration.
     */
    void bindSubscribed(Supplier<Number> subscribed) {
        Gauge.builder(SUBSCRIBED, subscribed)
                .description("1 while this instance receives the push channel's messages (its own probe comes back), "
                        + "else 0")
                .strongReference(true)
                .register(meterRegistry);
    }


    void signalPublished(PushTopic topic) {
        signals.get(topic).increment();
    }


    void delivered(PushTopic topic) {
        deliveries.get(topic).increment();
    }


    void publishFailed() {
        publishFailures.increment();
    }
}
