package com.laioffer.onlineorder.service;


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
import java.util.concurrent.TimeUnit;


/**
 * Replays a persisted dead letter to its source topic.
 *
 * <p>A replay is three steps, and the Kafka send never runs inside a database transaction: a short transaction
 * claims the row (PENDING or REPLAY_FAILED to REPLAYING, counting the attempt), the send runs with no transaction or
 * row lock held, and a second short transaction records REPLAYED or REPLAY_FAILED. Holding a {@code FOR UPDATE} lock
 * and a pooled connection across the send pinned both for the whole replay timeout whenever Kafka was slow.
 */
@Service
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class DeadLetterReplayService {

    static final String REPLAY_STATUS_PENDING = "PENDING";
    static final String REPLAY_STATUS_REPLAYING = "REPLAYING";
    static final String REPLAY_STATUS_REPLAYED = "REPLAYED";
    static final String REPLAY_STATUS_REPLAY_FAILED = "REPLAY_FAILED";

    private static final Logger LOGGER = LoggerFactory.getLogger(DeadLetterReplayService.class);

    private final Duration replayTimeout;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final DeadLetterEventRepository deadLetterEventRepository;
    private final ApplicationMetricsService metricsService;
    private final TransactionOperations transactionOperations;


    @Autowired
    public DeadLetterReplayService(
            @Value("${app.dlt.replay-timeout:5s}") Duration replayTimeout,
            KafkaTemplate<String, String> kafkaTemplate,
            DeadLetterEventRepository deadLetterEventRepository,
            ApplicationMetricsService metricsService,
            PlatformTransactionManager transactionManager
    ) {
        this(replayTimeout, kafkaTemplate, deadLetterEventRepository, metricsService, new TransactionTemplate(transactionManager));
    }


    public DeadLetterReplayService(
            Duration replayTimeout,
            KafkaTemplate<String, String> kafkaTemplate,
            DeadLetterEventRepository deadLetterEventRepository,
            ApplicationMetricsService metricsService,
            TransactionOperations transactionOperations
    ) {
        this.replayTimeout = replayTimeout;
        this.kafkaTemplate = kafkaTemplate;
        this.deadLetterEventRepository = deadLetterEventRepository;
        this.metricsService = metricsService;
        this.transactionOperations = transactionOperations;
    }


    public DeadLetterReplayDto replay(Long deadLetterEventId) {
        Claim claim = transactionOperations.execute(status -> claim(deadLetterEventId));
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
                transactionOperations.executeWithoutResult(status -> markReplayFailed(deadLetterEventId, rootMessage(sendFailure)));
            } catch (RuntimeException markFailure) {
                LOGGER.warn("Unable to record the failed replay of dead-letter event {}", deadLetterEventId, markFailure);
                replayFailure.addSuppressed(markFailure);
            }
            metricsService.recordDeadLetterReplay(false);
            throw replayFailure;
        }

        DeadLetterEventEntity replayed = transactionOperations.execute(status -> markReplayed(deadLetterEventId));
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

        int claimed = deadLetterEventRepository.claimForReplay(
                deadLetterEventId,
                REPLAY_STATUS_REPLAYING,
                REPLAY_STATUS_PENDING,
                REPLAY_STATUS_REPLAY_FAILED,
                LocalDateTime.now()
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
        return Claim.claimed(deadLetterEventRepository.findById(deadLetterEventId)
                .orElseThrow(() -> new ResourceNotFoundException("Dead-letter event not found after claim")));
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


    private DeadLetterEventEntity markReplayed(Long deadLetterEventId) {
        LocalDateTime now = LocalDateTime.now();
        deadLetterEventRepository.markReplayed(deadLetterEventId, REPLAY_STATUS_REPLAYED, now, now);
        return deadLetterEventRepository.findById(deadLetterEventId)
                .orElseThrow(() -> new ResourceNotFoundException("Dead-letter event not found after replay"));
    }


    private void markReplayFailed(Long deadLetterEventId, String errorMessage) {
        deadLetterEventRepository.markReplayFailed(
                deadLetterEventId,
                REPLAY_STATUS_REPLAY_FAILED,
                LocalDateTime.now(),
                abbreviate(errorMessage)
        );
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


    private record Claim(boolean claimed, DeadLetterEventEntity event) {

        static Claim claimed(DeadLetterEventEntity event) {
            return new Claim(true, event);
        }


        static Claim alreadyReplayed(DeadLetterEventEntity event) {
            return new Claim(false, event);
        }
    }
}
