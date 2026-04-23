package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.repository.DeadLetterEventRepository;
import com.laioffer.onlineorder.repository.IdempotencyRequestRepository;
import com.laioffer.onlineorder.repository.OutboxEventRepository;
import com.laioffer.onlineorder.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;


import java.time.Duration;
import java.time.LocalDateTime;


@Service
public class MaintenanceCleanupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaintenanceCleanupService.class);
    private static final String OUTBOX_STATUS_PUBLISHED = "PUBLISHED";
    private static final String DEAD_LETTER_STATUS_REPLAYED = "REPLAYED";

    private final boolean cleanupEnabled;
    private final Duration outboxRetention;
    private final Duration processedEventRetention;
    private final Duration idempotencyRetention;
    private final Duration deadLetterRetention;
    private final OutboxEventRepository outboxEventRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final IdempotencyRequestRepository idempotencyRequestRepository;
    private final DeadLetterEventRepository deadLetterEventRepository;


    public MaintenanceCleanupService(
            @Value("${app.cleanup.enabled:true}") boolean cleanupEnabled,
            @Value("${app.cleanup.outbox-retention:7d}") Duration outboxRetention,
            @Value("${app.cleanup.processed-event-retention:14d}") Duration processedEventRetention,
            @Value("${app.cleanup.idempotency-retention:3d}") Duration idempotencyRetention,
            @Value("${app.cleanup.dead-letter-retention:30d}") Duration deadLetterRetention,
            OutboxEventRepository outboxEventRepository,
            ProcessedEventRepository processedEventRepository,
            IdempotencyRequestRepository idempotencyRequestRepository,
            DeadLetterEventRepository deadLetterEventRepository
    ) {
        this.cleanupEnabled = cleanupEnabled;
        this.outboxRetention = outboxRetention;
        this.processedEventRetention = processedEventRetention;
        this.idempotencyRetention = idempotencyRetention;
        this.deadLetterRetention = deadLetterRetention;
        this.outboxEventRepository = outboxEventRepository;
        this.processedEventRepository = processedEventRepository;
        this.idempotencyRequestRepository = idempotencyRequestRepository;
        this.deadLetterEventRepository = deadLetterEventRepository;
    }


    @Scheduled(
            fixedDelayString = "${app.cleanup.fixed-delay-ms:3600000}",
            initialDelayString = "${app.cleanup.initial-delay-ms:300000}"
    )
    public void cleanupExpiredRecords() {
        if (!cleanupEnabled) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int deletedOutbox = outboxEventRepository.deleteByStatusAndPublishedAtBefore(
                OUTBOX_STATUS_PUBLISHED,
                now.minus(outboxRetention)
        );
        int deletedProcessed = processedEventRepository.deleteByProcessedAtBefore(now.minus(processedEventRetention));
        int deletedIdempotency = idempotencyRequestRepository.deleteByUpdatedAtBefore(now.minus(idempotencyRetention));
        int deletedDeadLetters = deadLetterEventRepository.deleteByReplayStatusAndUpdatedAtBefore(
                DEAD_LETTER_STATUS_REPLAYED,
                now.minus(deadLetterRetention)
        );

        LOGGER.info(
                "Maintenance cleanup finished: outbox={}, processed_events={}, idempotency_requests={}, dead_letters={}",
                deletedOutbox,
                deletedProcessed,
                deletedIdempotency,
                deletedDeadLetters
        );
    }
}
