package com.laioffer.onlineorder.observability;


import com.laioffer.onlineorder.messaging.OutboxStatus;
import com.laioffer.onlineorder.repository.DeadLetterEventRepository;
import com.laioffer.onlineorder.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;


@Component
public class OperationalMetricsBinder implements MeterBinder {

    private static final String OUTBOX_STATUS_PENDING = OutboxStatus.PENDING.name();
    private static final String OUTBOX_STATUS_PROCESSING = OutboxStatus.PROCESSING.name();
    private static final String OUTBOX_STATUS_FAILED = OutboxStatus.FAILED.name();
    private static final String OUTBOX_STATUS_PUBLISHED = OutboxStatus.PUBLISHED.name();
    private static final String OUTBOX_STATUS_ABANDONED = OutboxStatus.ABANDONED.name();

    private static final String REPLAY_STATUS_PENDING = "PENDING";
    private static final String REPLAY_STATUS_REPLAYING = "REPLAYING";
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
        bindOutboxStatus(registry, OUTBOX_STATUS_ABANDONED, "abandoned");

        Gauge.builder("onlineorder.outbox.backlog", this::countOutboxBacklog)
                .description("Outbox events waiting for publish or currently claimed by the publisher")
                .register(registry);
        Gauge.builder("onlineorder.outbox.failed", () -> countOutboxStatus(OUTBOX_STATUS_FAILED))
                .description("Outbox events that reached FAILED and require operator action")
                .register(registry);
        TimeGauge.builder("onlineorder.outbox.oldest_pending_age", this::oldestPendingAgeSeconds, TimeUnit.SECONDS)
                .description("Age of the oldest outbox event not yet published (PENDING or PROCESSING), 0 when none")
                .register(registry);
        Gauge.builder("onlineorder.outbox.blocked_aggregates", outboxEventRepository::countBlockedAggregates)
                .description("Aggregates whose later outbox events wait behind a FAILED event of the same aggregate "
                        + "(the publisher keeps each aggregate's events in order)")
                .register(registry);
        Gauge.builder("onlineorder.outbox.blocked_events", outboxEventRepository::countBlockedEvents)
                .description("Unpublished outbox events held back behind a FAILED event of their aggregate")
                .register(registry);

        Gauge.builder("onlineorder.dead_letter.total", this::countDeadLetterTotal)
                .description("Total persisted Kafka dead-letter events")
                .register(registry);
        bindDeadLetterStatus(registry, REPLAY_STATUS_PENDING, "pending");
        bindDeadLetterStatus(registry, REPLAY_STATUS_REPLAYING, "replaying");
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


    private double oldestPendingAgeSeconds() {
        LocalDateTime createdAt = outboxEventRepository.findOldestUnpublishedCreatedAt().orElse(null);
        if (createdAt == null) {
            return 0.0;
        }
        // created_at is written with LocalDateTime.now() in this JVM's time zone.
        long ageMillis = Duration.between(createdAt, LocalDateTime.now()).toMillis();
        return Math.max(0L, ageMillis) / 1000.0;
    }


    private long countOutboxBacklog() {
        return outboxEventRepository.countByStatuses(OUTBOX_STATUS_PENDING, OUTBOX_STATUS_PROCESSING);
    }


    private long countDeadLetterTotal() {
        return deadLetterEventRepository.countByReplayStatuses(
                REPLAY_STATUS_PENDING,
                REPLAY_STATUS_REPLAYING,
                REPLAY_STATUS_FAILED,
                REPLAY_STATUS_REPLAYED
        );
    }
}
