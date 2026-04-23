package com.laioffer.onlineorder.observability;


import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;


@Service
public class ApplicationMetricsService {

    private final MeterRegistry meterRegistry;


    public ApplicationMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }


    public void recordCheckout(String status) {
        meterRegistry.counter("onlineorder.checkout.completed", "status", normalize(status)).increment();
    }


    public void recordOrderTransition(String fromStatus, String toStatus) {
        meterRegistry.counter(
                "onlineorder.order.transition",
                "from", normalize(fromStatus),
                "to", normalize(toStatus)
        ).increment();
    }


    public void recordOutboxPublishResult(String topic, boolean success) {
        meterRegistry.counter(
                "onlineorder.outbox.publish",
                "topic", normalize(topic),
                "result", success ? "success" : "failure"
        ).increment();
    }


    public void recordDeadLetterStored(String sourceTopic) {
        meterRegistry.counter(
                "onlineorder.dead_letter.stored",
                "source_topic", normalize(sourceTopic)
        ).increment();
    }


    public void recordDeadLetterReplay(boolean success) {
        meterRegistry.counter(
                "onlineorder.dead_letter.replay",
                "result", success ? "success" : "failure"
        ).increment();
    }


    public void recordRateLimitRejection(String route) {
        meterRegistry.counter(
                "onlineorder.security.rate_limit.rejected",
                "route", normalize(route)
        ).increment();
    }


    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase().replace(' ', '_');
    }
}
