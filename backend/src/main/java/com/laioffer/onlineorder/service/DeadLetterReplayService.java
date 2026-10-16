package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.audit.AuditActions;
import com.laioffer.onlineorder.audit.AuditActor;
import com.laioffer.onlineorder.audit.AuditLogService;
import com.laioffer.onlineorder.entity.DeadLetterEventEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.model.DeadLetterReplayDto;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.DeadLetterEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;


import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * Replays a persisted dead letter to its source topic.
 *
 * <p>A replay is three steps, and the Kafka send never runs inside a database transaction: a short transaction
 * claims the row (PENDING or REPLAY_FAILED to REPLAYING, counting the attempt), the send runs with no transaction or
 * row lock held, and a second short transaction records REPLAYED or REPLAY_FAILED. Holding a {@code FOR UPDATE} lock
 * and a pooled connection across the send pinned both for the whole replay timeout whenever Kafka was slow.
 *
 * <p>Every update after the claim is a compare-and-set on the claim itself (status REPLAYING and the claim
 * timestamp), so a replay whose outcome arrives late cannot overwrite what a later replay recorded. A claim that is
 * never resolved, because the process died or the database was unreachable after the send, can be claimed again
 * once it is older than {@code app.dlt.replay-claim-timeout}; that timeout must exceed the longest possible send
 * (the producer's max.block.ms plus {@code app.dlt.replay-timeout}).
 *
 * <p>The outcome of a replay is written to the audit log in the transaction that records it: REPLAYED and
 * REPLAY_FAILED with the outcome transaction, ALREADY_REPLAYED (nothing sent) with the claim transaction. A refused
 * replay (unknown id, no source topic, a replay in flight) changes nothing and records nothing.
 */
@Service
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class DeadLetterReplayService {

    static final String REPLAY_STATUS_PENDING = "PENDING";
    static final String REPLAY_STATUS_REPLAYING = "REPLAYING";
    static final String REPLAY_STATUS_REPLAYED = "REPLAYED";
    static final String REPLAY_STATUS_REPLAY_FAILED = "REPLAY_FAILED";
    static final String OUTCOME_ALREADY_REPLAYED = "ALREADY_REPLAYED";

    private static final Logger LOGGER = LoggerFactory.getLogger(DeadLetterReplayService.class);

    private final Duration replayTimeout;
    private final Duration replayClaimTimeout;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final DeadLetterEventRepository deadLetterEventRepository;
    private final ApplicationMetricsService metricsService;
    private final TransactionOperations transactionOperations;
    private final AuditLogService auditLogService;


    @Autowired
    public DeadLetterReplayService(
            @Value("${app.dlt.replay-timeout:5s}") Duration replayTimeout,
            @Value("${app.dlt.replay-claim-timeout:30s}") Duration replayClaimTimeout,
            KafkaTemplate<String, String> kafkaTemplate,
            DeadLetterEventRepository deadLetterEventRepository,
            ApplicationMetricsService metricsService,
            PlatformTransactionManager transactionManager,
            AuditLogService auditLogService
    ) {
        this(
                replayTimeout,
                replayClaimTimeout,
                kafkaTemplate,
                deadLetterEventRepository,
                metricsService,
                new TransactionTemplate(transactionManager),
                auditLogService
        );
    }


    public DeadLetterReplayService(
            Duration replayTimeout,
            Duration replayClaimTimeout,
            KafkaTemplate<String, String> kafkaTemplate,
            DeadLetterEventRepository deadLetterEventRepository,
            ApplicationMetricsService metricsService,
            TransactionOperations transactionOperations,
            AuditLogService auditLogService
    ) {
        if (replayClaimTimeout.compareTo(replayTimeout) <= 0) {
            throw new IllegalArgumentException(
                    "app.dlt.replay-claim-timeout (" + replayClaimTimeout + ") must exceed app.dlt.replay-timeout ("
                            + replayTimeout + ") plus the producer's max.block.ms, or a slow replay could be claimed twice"
            );
        }
        this.replayTimeout = replayTimeout;
        this.replayClaimTimeout = replayClaimTimeout;
        this.kafkaTemplate = kafkaTemplate;
        this.deadLetterEventRepository = deadLetterEventRepository;
        this.metricsService = metricsService;
        this.transactionOperations = transactionOperations;
        this.auditLogService = auditLogService;
    }


    /**
     * A replay without a known actor, recorded in the audit log as SYSTEM.
     */
    public DeadLetterReplayDto replay(Long deadLetterEventId) {
        return replay(deadLetterEventId, AuditActor.system());
    }


    /**
     * @param actor recorded in the audit log with the outcome
     */
    public DeadLetterReplayDto replay(Long deadLetterEventId, AuditActor actor) {
        Claim claim = transactionOperations.execute(status -> {
            Claim result = claim(deadLetterEventId);
            if (!result.claimed()) {
                audit(actor, result.event(), OUTCOME_ALREADY_REPLAYED, null, true, null);
            }
            return result;
        });
        if (claim == null) {
            throw new IllegalStateException("Claim transaction completed without a dead-letter event");
        }
        if (!claim.claimed()) {
            metricsService.recordDeadLetterReplay(true);
            return new DeadLetterReplayDto(claim.event());
        }

        try {
            send(claim.event());
        } catch (RuntimeException sendFailure) {
            IllegalStateException replayFailure = new IllegalStateException("Unable to replay dead-letter event", sendFailure);
            try {
                transactionOperations.executeWithoutResult(status -> markReplayFailed(claim, rootMessage(sendFailure), actor));
            } catch (RuntimeException markFailure) {
                LOGGER.warn("Unable to record the failed replay of dead-letter event {}", deadLetterEventId, markFailure);
                replayFailure.addSuppressed(markFailure);
            }
            metricsService.recordDeadLetterReplay(false);
            throw replayFailure;
        }

        DeadLetterEventEntity replayed = transactionOperations.execute(status -> markReplayed(claim, actor));
        if (replayed == null) {
            throw new IllegalStateException("Replay transaction completed without a dead-letter event");
        }
        metricsService.recordDeadLetterReplay(true);
        return new DeadLetterReplayDto(replayed);
    }


    /**
     * Claims the row for this replay. Runs in its own short transaction and does no Kafka I/O.
     */
    private Claim claim(Long deadLetterEventId) {
        DeadLetterEventEntity event = deadLetterEventRepository.findById(deadLetterEventId)
                .orElseThrow(() -> new ResourceNotFoundException("Dead-letter event not found"));
        if (REPLAY_STATUS_REPLAYED.equals(event.replayStatus())) {
            return Claim.alreadyReplayed(event);
        }
        if (event.sourceTopic() == null || event.sourceTopic().isBlank()) {
            throw new BadRequestException("Dead-letter event does not have a source topic");
        }

        // updated_at only decides staleness; the claim is fenced on the replay_attempts value it increments.
        LocalDateTime claimedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        int claimed = deadLetterEventRepository.claimForReplay(
                deadLetterEventId,
                REPLAY_STATUS_REPLAYING,
                REPLAY_STATUS_PENDING,
                REPLAY_STATUS_REPLAY_FAILED,
                claimedAt.minus(replayClaimTimeout),
                claimedAt
        );
        if (claimed == 0) {
            DeadLetterEventEntity current = deadLetterEventRepository.findById(deadLetterEventId)
                    .orElseThrow(() -> new ResourceNotFoundException("Dead-letter event not found"));
            if (REPLAY_STATUS_REPLAYED.equals(current.replayStatus())) {
                return Claim.alreadyReplayed(current);
            }
            if (REPLAY_STATUS_REPLAYING.equals(current.replayStatus())) {
                throw new ConflictException("Dead-letter event replay is already in progress");
            }
            throw new ConflictException(
                    "Dead-letter event cannot be replayed from replay status " + current.replayStatus()
            );
        }
        DeadLetterEventEntity claimedEvent = deadLetterEventRepository.findById(deadLetterEventId)
                .orElseThrow(() -> new ResourceNotFoundException("Dead-letter event not found after claim"));
        // Read inside the claim transaction, which still holds the row lock taken by the UPDATE, so this is the
        // attempt number this claim wrote.
        return Claim.claimed(claimedEvent, claimedEvent.replayAttempts());
    }


    private void send(DeadLetterEventEntity event) {
        try {
            kafkaTemplate.send(
                            event.sourceTopic(),
                            normalizeNullable(event.messageKey()),
                            event.payload()
                    )
                    .get(replayTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while replaying dead-letter event", ex);
        } catch (Exception ex) {
            throw new IllegalStateException(rootMessage(ex), ex);
        }
    }


    private DeadLetterEventEntity markReplayed(Claim claim, AuditActor actor) {
        Long deadLetterEventId = claim.event().id();
        LocalDateTime now = LocalDateTime.now();
        int updated = deadLetterEventRepository.markReplayed(
                deadLetterEventId,
                REPLAY_STATUS_REPLAYED,
                REPLAY_STATUS_REPLAYING,
                claim.attempt(),
                now,
                now
        );
        if (updated == 0) {
            LOGGER.warn(
                    "Dead-letter event {} was replayed but no longer belongs to this claim; leaving its current status",
                    deadLetterEventId
            );
        }
        audit(actor, claim.event(), REPLAY_STATUS_REPLAYED, claim.attempt(), updated == 1, null);
        return deadLetterEventRepository.findById(deadLetterEventId)
                .orElseThrow(() -> new ResourceNotFoundException("Dead-letter event not found after replay"));
    }


    private void markReplayFailed(Claim claim, String errorMessage, AuditActor actor) {
        Long deadLetterEventId = claim.event().id();
        int updated = deadLetterEventRepository.markReplayFailed(
                deadLetterEventId,
                REPLAY_STATUS_REPLAY_FAILED,
                REPLAY_STATUS_REPLAYING,
                claim.attempt(),
                LocalDateTime.now(),
                abbreviate(errorMessage)
        );
        if (updated == 0) {
            LOGGER.warn(
                    "Replay of dead-letter event {} failed ({}) but the row no longer belongs to this claim; leaving its current status",
                    deadLetterEventId,
                    errorMessage
            );
        }
        audit(actor, claim.event(), REPLAY_STATUS_REPLAY_FAILED, claim.attempt(), updated == 1, abbreviate(errorMessage));
    }


    /**
     * @param attempt  the replay attempt this outcome belongs to; null when nothing was sent
     * @param recorded false when the outcome arrived after the claim had moved on, so the row kept a later status
     */
    private void audit(
            AuditActor actor,
            DeadLetterEventEntity event,
            String outcome,
            Integer attempt,
            boolean recorded,
            String error
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("outcome", outcome);
        details.put("attempt", attempt);
        details.put("recorded", recorded);
        details.put("source_topic", event.sourceTopic());
        details.put("event_id", event.eventId());
        details.put("error", error);
        auditLogService.record(actor, AuditActions.DEAD_LETTER_REPLAY, AuditActions.TARGET_DEAD_LETTER_EVENT, event.id(), details);
    }


    private String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }


    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }
        return message;
    }


    private String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "Unknown replay error";
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }


    private record Claim(boolean claimed, DeadLetterEventEntity event, int attempt) {

        static Claim claimed(DeadLetterEventEntity event, int attempt) {
            return new Claim(true, event, attempt);
        }


        static Claim alreadyReplayed(DeadLetterEventEntity event) {
            return new Claim(false, event, 0);
        }
    }
}
