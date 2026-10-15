package com.laioffer.onlineorder.messaging;


import com.laioffer.onlineorder.entity.OutboxEventEntity;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


/**
 * Relays outbox events to Kafka.
 *
 * <p>Delivery semantics:
 * <ul>
 *     <li>At-least-once: an event is marked PUBLISHED only after Kafka acknowledged it. A failed, timed-out or
 *     unrecorded send is sent again later, so consumers deduplicate by event id.</li>
 *     <li>Per-key order within one publisher: events are claimed in id order and the next event of a key (the
 *     order id) is sent only after Kafka acknowledged the previous one. When an event fails, the later events of
 *     its key are not sent in that batch. The idempotent producer (acks=all, enable.idempotence,
 *     max.in.flight.requests.per.connection &lt;= 5) keeps the sends in flight in order within each partition.</li>
 *     <li>Lease-based recovery: claimed events are PROCESSING. Events left PROCESSING for longer than
 *     app.outbox.claim-timeout, for example after a crash or a shutdown mid-batch, are claimed again.</li>
 *     <li>Multi-instance safety: claiming uses SELECT ... FOR UPDATE SKIP LOCKED, so publishers in several
 *     instances claim disjoint batches. Per-key order is not guaranteed across instances.</li>
 * </ul>
 *
 * <p>After a run in which a send failed or timed out, later runs are skipped for a backoff that starts at
 * app.outbox.publish-interval, doubles after each failed run and is capped at app.outbox.failure-backoff-max
 * (zero disables it), so a Kafka outage does not use up the events' attempts.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.publisher-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxEventPublisher.class);

    private static final Duration CLAIM_TIMEOUT_MARGIN = Duration.ofSeconds(5);
    private static final int MAX_BACKOFF_DOUBLINGS = 30;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Duration publishTimeout;
    private final int batchSize;
    private final Duration publishInterval;
    private final Duration failureBackoffMax;
    private final OrderEventOutboxService orderEventOutboxService;
    private final ApplicationMetricsService metricsService;
    private final Clock clock;

    private int consecutiveFailedRuns;
    private Instant pausedUntil;


    @Autowired
    public OutboxEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${app.kafka.publish-timeout:12s}") Duration publishTimeout,
            @Value("${app.outbox.batch-size:200}") int batchSize,
            @Value("${app.outbox.publish-interval:500ms}") Duration publishInterval,
            @Value("${app.outbox.claim-timeout:30s}") Duration claimTimeout,
            @Value("${app.outbox.failure-backoff-max:60s}") Duration failureBackoffMax,
            OrderEventOutboxService orderEventOutboxService,
            ApplicationMetricsService metricsService
    ) {
        this(
                kafkaTemplate,
                publishTimeout,
                batchSize,
                publishInterval,
                claimTimeout,
                failureBackoffMax,
                orderEventOutboxService,
                metricsService,
                Clock.systemUTC()
        );
    }


    public OutboxEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            Duration publishTimeout,
            int batchSize,
            Duration publishInterval,
            Duration claimTimeout,
            Duration failureBackoffMax,
            OrderEventOutboxService orderEventOutboxService,
            ApplicationMetricsService metricsService,
            Clock clock
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.publishTimeout = publishTimeout;
        this.batchSize = batchSize;
        this.publishInterval = publishInterval;
        this.failureBackoffMax = failureBackoffMax;
        this.orderEventOutboxService = orderEventOutboxService;
        this.metricsService = metricsService;
        this.clock = clock;
        if (claimTimeout.compareTo(publishTimeout.plus(CLAIM_TIMEOUT_MARGIN)) <= 0) {
            LOGGER.warn(
                    "app.outbox.claim-timeout ({}) should exceed app.kafka.publish-timeout ({}) by more than {}: "
                            + "claims can expire mid-batch and the same events can be published twice",
                    claimTimeout,
                    publishTimeout,
                    CLAIM_TIMEOUT_MARGIN
            );
        }
    }


    @Scheduled(fixedDelayString = "${app.outbox.publish-interval:500ms}")
    public synchronized void publishPendingEvents() {
        if (pausedUntil != null && clock.instant().isBefore(pausedUntil)) {
            return;
        }

        boolean failed = true;
        try {
            failed = drainPendingEvents();
        } finally {
            updateFailureBackoff(failed);
        }
    }


    /**
     * @return whether a send failed or timed out
     */
    private boolean drainPendingEvents() {
        while (!Thread.currentThread().isInterrupted()) {
            List<OutboxEventEntity> events = orderEventOutboxService.claimNextBatch();
            if (events.isEmpty()) {
                return false;
            }

            BatchResult result = new BatchPublication(events).publish();
            if (result.failed()) {
                return true;
            }
            if (result.publishedCount() < events.size() || events.size() < batchSize) {
                return false;
            }
        }
        return false;
    }


    private void updateFailureBackoff(boolean failed) {
        if (!failed) {
            if (consecutiveFailedRuns > 0) {
                LOGGER.info("Outbox publisher resumed after {} failed run(s)", consecutiveFailedRuns);
            }
            consecutiveFailedRuns = 0;
            pausedUntil = null;
            return;
        }

        consecutiveFailedRuns++;
        int doublings = Math.min(consecutiveFailedRuns - 1, MAX_BACKOFF_DOUBLINGS);
        Duration backoff = publishInterval.multipliedBy(1L << doublings);
        if (backoff.compareTo(failureBackoffMax) > 0) {
            backoff = failureBackoffMax;
        }
        pausedUntil = clock.instant().plus(backoff);
        LOGGER.warn(
                "Outbox publish run failed; pausing the outbox publisher for {} (consecutive failed runs: {})",
                backoff,
                consecutiveFailedRuns
        );
    }


    private static String describe(Throwable failure) {
        if (failure == null) {
            return null;
        }
        Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(failure);
        return rootCause == failure ? failure.toString() : failure + "; caused by " + rootCause;
    }


    private static Throwable failureOf(CompletableFuture<?> completedFuture) {
        try {
            completedFuture.join();
            return null;
        } catch (CompletionException ex) {
            return ex.getCause() == null ? ex : ex.getCause();
        } catch (CancellationException ex) {
            return ex;
        }
    }


    /**
     * Sends one claimed batch in waves: wave k holds the k-th event of every key, and a wave starts only after
     * the previous one was acknowledged or failed. All waves share one deadline of publish-timeout.
     */
    private final class BatchPublication {

        private final long deadline = System.nanoTime() + publishTimeout.toNanos();
        private final Map<String, Deque<OutboxEventEntity>> unsentByKey = new LinkedHashMap<>();
        private final List<OutboxEventEntity> published = new ArrayList<>();
        private final List<FailedEvent> failed = new ArrayList<>();
        private final List<Long> unsentIds = new ArrayList<>();
        private boolean sendingStopped;
        private boolean interrupted;


        private BatchPublication(List<OutboxEventEntity> events) {
            for (OutboxEventEntity event : events) {
                unsentByKey.computeIfAbsent(event.eventKey(), key -> new ArrayDeque<>()).add(event);
            }
        }


        private BatchResult publish() {
            try {
                while (!sendingStopped && !unsentByKey.isEmpty()) {
                    awaitWave(sendWave());
                }
                for (Deque<OutboxEventEntity> events : unsentByKey.values()) {
                    events.forEach(event -> unsentIds.add(event.id()));
                }
                recordOutcome();
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return new BatchResult(published.size(), !failed.isEmpty());
        }


        private List<InFlightEvent> sendWave() {
            List<InFlightEvent> wave = new ArrayList<>();
            Iterator<Deque<OutboxEventEntity>> keys = unsentByKey.values().iterator();
            while (!sendingStopped && keys.hasNext()) {
                if (deadline - System.nanoTime() <= 0) {
                    sendingStopped = true;
                    break;
                }

                Deque<OutboxEventEntity> keyEvents = keys.next();
                OutboxEventEntity event = keyEvents.removeFirst();
                if (keyEvents.isEmpty()) {
                    keys.remove();
                }
                CompletableFuture<SendResult<String, String>> future = send(event);
                if (future != null) {
                    wave.add(new InFlightEvent(event, future));
                }
            }
            return wave;
        }


        /**
         * Stops the batch when the send throws or fails right away, the usual sign of a broker or metadata
         * outage (KafkaTemplate throws once the producer gave up after max.block.ms).
         *
         * @return the pending send, or null when the event was not sent
         */
        private CompletableFuture<SendResult<String, String>> send(OutboxEventEntity event) {
            CompletableFuture<SendResult<String, String>> future;
            try {
                future = kafkaTemplate.send(event.topic(), event.eventKey(), event.payload());
            } catch (RuntimeException ex) {
                sendingStopped = true;
                if (Thread.interrupted()) {
                    interrupted = true;
                    unsentIds.add(event.id());
                } else {
                    failed.add(new FailedEvent(event, ex));
                }
                return null;
            }

            if (future == null) {
                sendingStopped = true;
                failed.add(new FailedEvent(event, new IllegalStateException("Kafka send returned no result")));
                return null;
            }
            if (future.isCompletedExceptionally()) {
                sendingStopped = true;
                failed.add(new FailedEvent(event, failureOf(future)));
                return null;
            }
            return future;
        }


        private void awaitWave(List<InFlightEvent> wave) {
            for (int index = 0; index < wave.size(); index++) {
                if (interrupted) {
                    keepClaimed(wave.subList(index, wave.size()));
                    return;
                }

                InFlightEvent inFlight = wave.get(index);
                OutboxEventEntity event = inFlight.event();
                try {
                    inFlight.future().get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    published.add(event);
                    continue;
                } catch (InterruptedException ex) {
                    interrupted = true;
                    sendingStopped = true;
                    keepClaimed(wave.subList(index, wave.size()));
                    return;
                } catch (ExecutionException ex) {
                    failed.add(new FailedEvent(event, ex.getCause()));
                } catch (TimeoutException ex) {
                    failed.add(new FailedEvent(
                            event,
                            "Kafka did not acknowledge the event within " + publishTimeout,
                            null
                    ));
                } catch (CancellationException ex) {
                    failed.add(new FailedEvent(event, ex));
                }

                Deque<OutboxEventEntity> laterEvents = unsentByKey.remove(event.eventKey());
                if (laterEvents != null) {
                    laterEvents.forEach(laterEvent -> unsentIds.add(laterEvent.id()));
                }
            }
        }


        /**
         * Leaves unconfirmed events, and the later events of their keys, PROCESSING so the claim timeout hands
         * them back together in id order.
         */
        private void keepClaimed(List<InFlightEvent> unconfirmed) {
            for (InFlightEvent inFlight : unconfirmed) {
                unsentByKey.remove(inFlight.event().eventKey());
            }
        }


        private void recordOutcome() {
            if (!published.isEmpty()) {
                orderEventOutboxService.markPublished(published.stream().map(OutboxEventEntity::id).toList());
                for (OutboxEventEntity event : published) {
                    metricsService.recordOutboxPublishResult(event.topic(), true);
                }
            }
            for (FailedEvent failure : failed) {
                OutboxEventEntity event = failure.event();
                orderEventOutboxService.markRetryableFailure(event.id(), failure.error());
                metricsService.recordOutboxPublishResult(event.topic(), false);
                LOGGER.warn(
                        "Failed to publish outbox event {} to Kafka topic {}: {}",
                        event.id(),
                        event.topic(),
                        failure.error(),
                        failure.cause()
                );
            }
            if (!unsentIds.isEmpty()) {
                orderEventOutboxService.releaseClaims(unsentIds);
            }
        }
    }


    private record InFlightEvent(OutboxEventEntity event, CompletableFuture<SendResult<String, String>> future) {
    }


    private record FailedEvent(OutboxEventEntity event, String error, Throwable cause) {

        private FailedEvent(OutboxEventEntity event, Throwable cause) {
            this(event, describe(cause), cause);
        }
    }


    private record BatchResult(int publishedCount, boolean failed) {
    }
}
