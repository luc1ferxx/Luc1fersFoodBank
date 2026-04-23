package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.DeadLetterEventEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.model.DeadLetterReplayDto;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.DeadLetterEventRepository;
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


@Service
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class DeadLetterReplayService {

    static final String REPLAY_STATUS_REPLAYED = "REPLAYED";
    static final String REPLAY_STATUS_REPLAY_FAILED = "REPLAY_FAILED";

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
        try {
            DeadLetterEventEntity event = transactionOperations.execute(status -> replayInTransaction(deadLetterEventId));
            if (event == null) {
                throw new IllegalStateException("Replay transaction completed without a dead-letter event");
            }
            metricsService.recordDeadLetterReplay(true);
            return new DeadLetterReplayDto(event);
        } catch (ResourceNotFoundException | BadRequestException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            markReplayFailed(deadLetterEventId, rootMessage(ex));
            metricsService.recordDeadLetterReplay(false);
            throw new IllegalStateException("Unable to replay dead-letter event", ex);
        }
    }


    private DeadLetterEventEntity replayInTransaction(Long deadLetterEventId) {
        DeadLetterEventEntity event = deadLetterEventRepository.lockById(deadLetterEventId);
        if (event == null) {
            throw new ResourceNotFoundException("Dead-letter event not found");
        }
        if (REPLAY_STATUS_REPLAYED.equals(event.replayStatus())) {
            return event;
        }
        if (event.sourceTopic() == null || event.sourceTopic().isBlank()) {
            throw new BadRequestException("Dead-letter event does not have a source topic");
        }

        send(event);
        LocalDateTime now = LocalDateTime.now();
        deadLetterEventRepository.markReplayed(event.id(), REPLAY_STATUS_REPLAYED, now, now);
        return deadLetterEventRepository.findById(event.id())
                .orElseThrow(() -> new ResourceNotFoundException("Dead-letter event not found after replay"));
    }


    private void send(DeadLetterEventEntity event) {
        try {
            kafkaTemplate.send(
                            event.sourceTopic(),
                            normalizeNullable(event.messageKey()),
                            event.payload()
                    )
                    .get(replayTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException(rootMessage(ex), ex);
        }
    }


    private void markReplayFailed(Long deadLetterEventId, String errorMessage) {
        transactionOperations.executeWithoutResult(status -> {
            if (!deadLetterEventRepository.existsById(deadLetterEventId)) {
                return;
            }
            deadLetterEventRepository.markReplayFailed(
                    deadLetterEventId,
                    REPLAY_STATUS_REPLAY_FAILED,
                    LocalDateTime.now(),
                    abbreviate(errorMessage)
            );
        });
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
}
