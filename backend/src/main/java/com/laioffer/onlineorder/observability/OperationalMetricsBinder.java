package com.laioffer.onlineorder.observability;


import com.laioffer.onlineorder.repository.DeadLetterEventRepository;
import com.laioffer.onlineorder.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;


@Component
public class OperationalMetricsBinder implements MeterBinder {

    private static final String OUTBOX_STATUS_PENDING = "PENDING";
    private static final String OUTBOX_STATUS_PROCESSING = "PROCESSING";
    private static final String OUTBOX_STATUS_FAILED = "FAILED";
    private static final String OUTBOX_STATUS_PUBLISHED = "PUBLISHED";

    private static final String REPLAY_STATUS_PENDING = "PENDING";
    private static final String REPLAY_STATUS_FAILED = "REPLAY_FAILED";
    private static final String REPLAY_STATUS_REPLAYED = "REPLAYED";

    private final OutboxEventRepository outboxEventRepository;
    private final DeadLetterEventRepository deadLetterEventRepository;


    public OperationalMetricsBinder(
            OutboxEventRepository outboxEventRepository,
            DeadLetterEventRepository deadLetterEventRepository
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.deadLetterEventRepository = deadLetterEventRepository;
    }


    @Override
    public void bindTo(MeterRegistry registry) {
        bindOutboxStatus(registry, OUTBOX_STATUS_PENDING, "pending");
        bindOutboxStatus(registry, OUTBOX_STATUS_PROCESSING, "processing");
        bindOutboxStatus(registry, OUTBOX_STATUS_FAILED, "failed");
        bindOutboxStatus(registry, OUTBOX_STATUS_PUBLISHED, "published");

        Gauge.builder("onlineorder.outbox.backlog", this::countOutboxBacklog)
                .description("Outbox events waiting for publish or currently claimed by the publisher")
                .register(registry);
        Gauge.builder("onlineorder.outbox.failed", () -> countOutboxStatus(OUTBOX_STATUS_FAILED))
                .description("Outbox events that reached FAILED and require operator action")
                .register(registry);

        Gauge.builder("onlineorder.dead_letter.total", this::countDeadLetterTotal)
                .description("Total persisted Kafka dead-letter events")
                .register(registry);
        bindDeadLetterStatus(registry, REPLAY_STATUS_PENDING, "pending");
        bindDeadLetterStatus(registry, REPLAY_STATUS_FAILED, "replay_failed");
        bindDeadLetterStatus(registry, REPLAY_STATUS_REPLAYED, "replayed");
    }


    private void bindOutboxStatus(MeterRegistry registry, String status, String tagValue) {
        Gauge.builder("onlineorder.outbox.events", () -> countOutboxStatus(status))
                .description("Outbox events grouped by publisher status")
                .tag("status", tagValue)
                .register(registry);
    }


    private void bindDeadLetterStatus(MeterRegistry registry, String replayStatus, String tagValue) {
        Gauge.builder("onlineorder.dead_letter.events", () -> deadLetterEventRepository.countByReplayStatus(replayStatus))
                .description("Persisted Kafka dead-letter events grouped by replay status")
                .tag("replay_status", tagValue)
                .register(registry);
    }


    private long countOutboxStatus(String status) {
        return outboxEventRepository.countByStatus(status);
    }


    private long countOutboxBacklog() {
        return outboxEventRepository.countByStatuses(OUTBOX_STATUS_PENDING, OUTBOX_STATUS_PROCESSING);
    }


    private long countDeadLetterTotal() {
        return deadLetterEventRepository.countByReplayStatuses(
                REPLAY_STATUS_PENDING,
                REPLAY_STATUS_FAILED,
                REPLAY_STATUS_REPLAYED
        );
    }
}
