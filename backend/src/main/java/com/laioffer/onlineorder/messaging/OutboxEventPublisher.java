package com.laioffer.onlineorder.messaging;


import com.laioffer.onlineorder.entity.OutboxEventEntity;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.InvalidRecordException;
import org.apache.kafka.common.errors.RecordBatchTooLargeException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
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
 *     <li>Lease-based recovery: claimed events are PROCESSING. When the publisher is interrupted, the events
 *     still in flight stay PROCESSING and the events it never sent are released. Events left PROCESSING for
 *     longer than app.outbox.claim-timeout, for example after a crash or a shutdown mid-batch, are claimed
 *     again; if Kafka never received such an event, the later events of its key can overtake it.</li>
 *     <li>Multi-instance safety: claiming uses SELECT ... FOR UPDATE SKIP LOCKED, so publishers in several
 *     instances claim disjoint batches. Per-key order is not guaranteed across instances.</li>
 * </ul>
 *
 * <p>Failures: a transient failure (a Kafka RetriableException such as a metadata or delivery timeout, or no
 * acknowledgement before the batch deadline) returns the event to PENDING without using up one of its
 * app.outbox.max-attempts, so a Kafka outage does not push events to FAILED. Any other failure uses up an
 * attempt. A send rejected before the producer accepted the record stops the batch, except that a record error,
 * such as a too large record, only holds back the later events of its key unless the previous send was rejected
 * too.
 *
 * <p>After a run with a failure, later runs are skipped for a backoff that starts at app.outbox.publish-interval,
 * doubles after each failed run and is capped at app.outbox.failure-backoff-max (zero disables it). A run whose
 * only failures were record errors does not back off when it published other events.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.publisher-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxEventPublisher.class);

    private static final Duration CLAIM_TIMEOUT_MARGIN = Duration.ofSeconds(5);
    private static final Duration KAFKA_DEFAULT_MAX_BLOCK = Duration.ofMinutes(1);
    private static final int MAX_BACKOFF_DOUBLINGS = 30;
    private static final int MAX_CAUSE_DEPTH = 10;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Duration publishTimeout;
    private final int batchSize;
    private final Duration publishInterval;
    private final Duration failureBackoffMax;
    private final OrderEventOutboxService orderEventOutboxService;
    private final ApplicationMetricsService metricsService;
    private final Clock clock;

    private int consecutiveFailedRuns;
    private Instant pausedAt;
    private Duration pause = Duration.ZERO;


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
        Duration maxBlock = producerMaxBlock(kafkaTemplate);
        if (claimTimeout.compareTo(publishTimeout.plus(maxBlock).plus(CLAIM_TIMEOUT_MARGIN)) <= 0) {
            LOGGER.warn(
                    "app.outbox.claim-timeout ({}) should exceed app.kafka.publish-timeout ({}) plus the Kafka "
                            + "producer max.block.ms ({}) by more than {}: claims can expire mid-batch and the same "
                            + "events can be published twice",
                    claimTimeout,
                    publishTimeout,
                    maxBlock,
                    CLAIM_TIMEOUT_MARGIN
            );
        }
    }


    @Scheduled(fixedDelayString = "${app.outbox.publish-interval:500ms}")
    public synchronized void publishPendingEvents() {
        if (isPaused()) {
            return;
        }

        boolean backOff = true;
        try {
            backOff = drainPendingEvents();
        } finally {
            updateFailureBackoff(backOff);
        }
    }


    /**
     * @return whether the run failed in a way that calls for a backoff
     */
    private boolean drainPendingEvents() {
        while (!Thread.currentThread().isInterrupted()) {
            List<OutboxEventEntity> events = orderEventOutboxService.claimNextBatch();
            if (events.isEmpty()) {
                return false;
            }

            BatchResult result = new BatchPublication(events).publish();
            if (result.failed()) {
                return result.backOff();
            }
            if (result.publishedCount() < events.size() || events.size() < batchSize) {
                return false;
            }
        }
        return false;
    }


    private boolean isPaused() {
        if (pausedAt == null) {
            return false;
        }

        Instant now = clock.instant();
        // A wall clock that was set back must not stretch the pause beyond its length.
        return !now.isBefore(pausedAt) && now.isBefore(pausedAt.plus(pause));
    }


    private void updateFailureBackoff(boolean backOff) {
        if (!backOff) {
            if (consecutiveFailedRuns > 0) {
                LOGGER.info("Outbox publisher resumed after {} failed run(s)", consecutiveFailedRuns);
            }
            consecutiveFailedRuns = 0;
            pausedAt = null;
            return;
        }

        consecutiveFailedRuns++;
        int doublings = Math.min(consecutiveFailedRuns - 1, MAX_BACKOFF_DOUBLINGS);
        Duration backoff = publishInterval.multipliedBy(1L << doublings);
        if (backoff.compareTo(failureBackoffMax) > 0) {
            backoff = failureBackoffMax;
        }
        pause = backoff;
        pausedAt = clock.instant();
        LOGGER.warn(
                "Outbox publish run failed; pausing the outbox publisher for {} (consecutive failed runs: {})",
                backoff,
                consecutiveFailedRuns
        );
    }


    /**
     * A send can block for up to the producer's max.block.ms after the batch deadline was checked, so a batch can
     * hold its claims for publish-timeout plus max.block.ms.
     *
     * @return the producer's max.block.ms, or zero when the producer configuration is not available
     */
    private static Duration producerMaxBlock(KafkaTemplate<String, String> kafkaTemplate) {
        try {
            ProducerFactory<String, String> producerFactory = kafkaTemplate.getProducerFactory();
            if (producerFactory == null) {
                return Duration.ZERO;
            }

            Object maxBlock = producerFactory.getConfigurationProperties().get(ProducerConfig.MAX_BLOCK_MS_CONFIG);
            if (maxBlock == null) {
                return KAFKA_DEFAULT_MAX_BLOCK;
            }
            return Duration.ofMillis(maxBlock instanceof Number number
                    ? number.longValue()
                    : Long.parseLong(maxBlock.toString().trim()));
        } catch (RuntimeException ex) {
            return Duration.ZERO;
        }
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
        private boolean previousSendRejected;
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
            boolean backOff = !failed.isEmpty() && (published.isEmpty() || failed.stream()
                    .anyMatch(failure -> failure.kind() != FailureKind.RECORD));
            return new BatchResult(published.size(), !failed.isEmpty(), backOff);
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
                CompletableFuture<SendResult<String, String>> future = send(event, keyEvents);
                if (future != null) {
                    wave.add(new InFlightEvent(event, future));
                }
                if (keyEvents.isEmpty()) {
                    keys.remove();
                }
            }
            return wave;
        }


        /**
         * @param laterEvents the later events of the event's key in this batch
         * @return the pending send, or null when the event was not sent
         */
        private CompletableFuture<SendResult<String, String>> send(
                OutboxEventEntity event,
                Deque<OutboxEventEntity> laterEvents
        ) {
            CompletableFuture<SendResult<String, String>> future;
            try {
                future = kafkaTemplate.send(event.topic(), event.eventKey(), event.payload());
            } catch (RuntimeException ex) {
                if (Thread.interrupted()) {
                    interrupted = true;
                    sendingStopped = true;
                    unsentIds.add(event.id());
                } else {
                    reject(event, ex, laterEvents);
                }
                return null;
            }

            if (future == null) {
                reject(event, new IllegalStateException("Kafka send returned no result"), laterEvents);
                return null;
            }
            if (future.isCompletedExceptionally()) {
                reject(event, failureOf(future), laterEvents);
                return null;
            }
            previousSendRejected = false;
            return future;
        }


        /**
         * Handles a send that threw or failed right away, before the producer accepted the record. A record error
         * holds back only the later events of the event's key. Any other rejection, usually a broker or metadata
         * outage (KafkaTemplate throws once the producer gave up after max.block.ms), and a second rejection in a
         * row stop the batch.
         */
        private void reject(OutboxEventEntity event, Throwable cause, Deque<OutboxEventEntity> laterEvents) {
            FailedEvent failure = new FailedEvent(event, cause);
            failed.add(failure);
            if (failure.kind() == FailureKind.RECORD && !previousSendRejected) {
                laterEvents.forEach(laterEvent -> unsentIds.add(laterEvent.id()));
                laterEvents.clear();
            } else {
                sendingStopped = true;
            }
            previousSendRejected = true;
        }


        /**
         * When interrupted, the events still in flight stay PROCESSING until the claim timeout, while the later
         * events of their keys, which were never sent, are released with the rest of the batch.
         */
        private void awaitWave(List<InFlightEvent> wave) {
            for (InFlightEvent inFlight : wave) {
                if (interrupted) {
                    return;
                }

                OutboxEventEntity event = inFlight.event();
                try {
                    inFlight.future().get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    published.add(event);
                    continue;
                } catch (InterruptedException ex) {
                    interrupted = true;
                    sendingStopped = true;
                    return;
                } catch (ExecutionException ex) {
                    failed.add(new FailedEvent(event, ex.getCause()));
                } catch (TimeoutException ex) {
                    failed.add(new FailedEvent(
                            event,
                            "Kafka did not acknowledge the event within " + publishTimeout,
                            null,
                            FailureKind.TRANSIENT
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
         * Runs every write even when one of them fails, so that a failed write does not leave the other events
         * PROCESSING until the claim timeout; the first failure is rethrown afterwards.
         */
        private void recordOutcome() {
            RuntimeException writeFailure = null;
            if (!published.isEmpty()) {
                writeFailure = runWrite(writeFailure, () -> {
                    orderEventOutboxService.markPublished(published.stream().map(OutboxEventEntity::id).toList());
                    for (OutboxEventEntity event : published) {
                        metricsService.recordOutboxPublishResult(event.topic(), true);
                    }
                });
            }
            Map<String, List<Long>> transientFailureIdsByError = new LinkedHashMap<>();
            for (FailedEvent failure : failed) {
                OutboxEventEntity event = failure.event();
                if (failure.kind() == FailureKind.TRANSIENT) {
                    transientFailureIdsByError.computeIfAbsent(failure.error(), error -> new ArrayList<>())
                            .add(event.id());
                } else {
                    writeFailure = runWrite(
                            writeFailure,
                            () -> orderEventOutboxService.markRetryableFailure(event.id(), failure.error())
                    );
                }
                metricsService.recordOutboxPublishResult(event.topic(), false);
                LOGGER.warn(
                        "Failed to publish outbox event {} to Kafka topic {}: {}",
                        event.id(),
                        event.topic(),
                        failure.error(),
                        failure.cause()
                );
            }
            for (Map.Entry<String, List<Long>> idsByError : transientFailureIdsByError.entrySet()) {
                writeFailure = runWrite(
                        writeFailure,
                        () -> orderEventOutboxService.releaseFailedClaims(idsByError.getValue(), idsByError.getKey())
                );
            }
            if (!unsentIds.isEmpty()) {
                writeFailure = runWrite(writeFailure, () -> orderEventOutboxService.releaseClaims(unsentIds));
            }
            if (writeFailure != null) {
                throw writeFailure;
            }
        }


        private RuntimeException runWrite(RuntimeException earlierFailure, Runnable write) {
            try {
                write.run();
                return earlierFailure;
            } catch (RuntimeException ex) {
                if (earlierFailure == null) {
                    return ex;
                }
                earlierFailure.addSuppressed(ex);
                return earlierFailure;
            }
        }
    }


    private record InFlightEvent(OutboxEventEntity event, CompletableFuture<SendResult<String, String>> future) {
    }


    private record FailedEvent(OutboxEventEntity event, String error, Throwable cause, FailureKind kind) {

        private FailedEvent(OutboxEventEntity event, Throwable cause) {
            this(event, describe(cause), cause, FailureKind.of(cause));
        }
    }


    private enum FailureKind {

        /**
         * Kafka or the network is unavailable: the event is released without using up an attempt.
         */
        TRANSIENT,

        /**
         * Kafka rejected the record itself, for example because it is too large: the event uses up an attempt,
         * but the other keys keep being published.
         */
        RECORD,

        /**
         * Any other failure: the event uses up an attempt.
         */
        OTHER;


        private static FailureKind of(Throwable failure) {
            Throwable cause = failure;
            for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
                if (cause instanceof RetriableException) {
                    return TRANSIENT;
                }
                if (cause instanceof RecordTooLargeException
                        || cause instanceof RecordBatchTooLargeException
                        || cause instanceof InvalidRecordException
                        || cause instanceof SerializationException) {
                    return RECORD;
                }
                cause = cause.getCause();
            }
            return OTHER;
        }
    }


    private record BatchResult(int publishedCount, boolean failed, boolean backOff) {
    }
}
