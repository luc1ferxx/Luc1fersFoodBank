package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.repository.ConsumerAggregateSequenceRepository;
import com.laioffer.onlineorder.audit.AuditLogRepository;
import com.laioffer.onlineorder.messaging.OutboxStatus;
import com.laioffer.onlineorder.partition.PartitionCatalog;
import com.laioffer.onlineorder.partition.PartitionedTable;
import com.laioffer.onlineorder.repository.DeadLetterEventRepository;
import com.laioffer.onlineorder.repository.IdempotencyRequestRepository;
import com.laioffer.onlineorder.repository.OrderStatusHistoryRepository;
import com.laioffer.onlineorder.repository.OutboxEventRepository;
import com.laioffer.onlineorder.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.IntUnaryOperator;


/**
 * Hourly row-by-row retention for the operational tables. processed_events, audit_log and order_status_history are
 * left to PartitionMaintenanceService where they are partitioned (PostgreSQL, V10), which drops whole months instead;
 * here (H2) their expired rows are deleted. audit_log and order_status_history are kept forever unless
 * app.cleanup.audit-log-retention / order-status-history-retention is set.
 */
@Service
public class MaintenanceCleanupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaintenanceCleanupService.class);
    private static final String OUTBOX_STATUS_PUBLISHED = OutboxStatus.PUBLISHED.name();
    private static final String DEAD_LETTER_STATUS_REPLAYED = "REPLAYED";

    private final boolean cleanupEnabled;
    private final Duration outboxRetention;
    private final Duration processedEventRetention;
    private final Duration idempotencyRetention;
    private final Duration deadLetterRetention;
    private final Duration auditLogRetention;
    private final Duration orderStatusHistoryRetention;
    private final int batchSize;
    private final OutboxEventRepository outboxEventRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final IdempotencyRequestRepository idempotencyRequestRepository;
    private final DeadLetterEventRepository deadLetterEventRepository;
    private final ConsumerAggregateSequenceRepository consumerAggregateSequenceRepository;
    private final AuditLogRepository auditLogRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final PartitionCatalog partitionCatalog;


    public MaintenanceCleanupService(
            @Value("${app.cleanup.enabled:true}") boolean cleanupEnabled,
            @Value("${app.cleanup.outbox-retention:7d}") Duration outboxRetention,
            @Value("${app.cleanup.processed-event-retention:14d}") Duration processedEventRetention,
            @Value("${app.cleanup.idempotency-retention:3d}") Duration idempotencyRetention,
            @Value("${app.cleanup.dead-letter-retention:30d}") Duration deadLetterRetention,
            @Value("${app.cleanup.audit-log-retention:0d}") Duration auditLogRetention,
            @Value("${app.cleanup.order-status-history-retention:0d}") Duration orderStatusHistoryRetention,
            @Value("${app.cleanup.batch-size:5000}") int batchSize,
            OutboxEventRepository outboxEventRepository,
            ProcessedEventRepository processedEventRepository,
            IdempotencyRequestRepository idempotencyRequestRepository,
            DeadLetterEventRepository deadLetterEventRepository,
            ConsumerAggregateSequenceRepository consumerAggregateSequenceRepository,
            AuditLogRepository auditLogRepository,
            OrderStatusHistoryRepository orderStatusHistoryRepository,
            PartitionCatalog partitionCatalog
    ) {
        this.cleanupEnabled = cleanupEnabled;
        this.outboxRetention = outboxRetention;
        this.processedEventRetention = processedEventRetention;
        this.idempotencyRetention = idempotencyRetention;
        this.deadLetterRetention = deadLetterRetention;
        this.auditLogRetention = auditLogRetention;
        this.orderStatusHistoryRetention = orderStatusHistoryRetention;
        this.batchSize = Math.max(1, batchSize);
        this.outboxEventRepository = outboxEventRepository;
        this.processedEventRepository = processedEventRepository;
        this.idempotencyRequestRepository = idempotencyRequestRepository;
        this.deadLetterEventRepository = deadLetterEventRepository;
        this.consumerAggregateSequenceRepository = consumerAggregateSequenceRepository;
        this.auditLogRepository = auditLogRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.partitionCatalog = partitionCatalog;
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
        LocalDateTime outboxCutoff = now.minus(outboxRetention);
        LocalDateTime processedCutoff = now.minus(processedEventRetention);
        LocalDateTime idempotencyCutoff = now.minus(idempotencyRetention);
        LocalDateTime deadLetterCutoff = now.minus(deadLetterRetention);
        int deletedOutbox = deleteInBatches(limit -> outboxEventRepository.deleteByStatusAndPublishedAtBefore(
                OUTBOX_STATUS_PUBLISHED,
                outboxCutoff,
                limit
        ));
        int deletedProcessed = partitionCatalog.isPartitioned(PartitionedTable.PROCESSED_EVENTS)
                ? 0
                : deleteInBatches(limit -> processedEventRepository.deleteByProcessedAtBefore(processedCutoff, limit));
        // Consumer positions (stale-event detection) age out with the processed_events marks: an event older than that
        // is applied without the check, and the consumers' own guards still hold.
        int deletedPositions = deleteInBatches(limit -> consumerAggregateSequenceRepository.deleteByUpdatedAtBefore(
                processedCutoff,
                limit
        ));
        int deletedIdempotency = deleteInBatches(limit -> idempotencyRequestRepository.deleteByUpdatedAtBefore(
                idempotencyCutoff,
                limit
        ));
        int deletedDeadLetters = deleteInBatches(limit -> deadLetterEventRepository.deleteByReplayStatusAndUpdatedAtBefore(
                DEAD_LETTER_STATUS_REPLAYED,
                deadLetterCutoff,
                limit
        ));

        int deletedAuditLog = keepsRows(auditLogRetention, PartitionedTable.AUDIT_LOG)
                ? 0
                : deleteInBatches(limit -> auditLogRepository.deleteByOccurredAtBefore(
                        now.minus(auditLogRetention),
                        limit
                ));
        int deletedHistory = keepsRows(orderStatusHistoryRetention, PartitionedTable.ORDER_STATUS_HISTORY)
                ? 0
                : deleteInBatches(limit -> orderStatusHistoryRepository.deleteByOccurredAtBefore(
                        now.minus(orderStatusHistoryRetention),
                        limit
                ));

        LOGGER.info(
                "Maintenance cleanup finished: outbox={}, processed_events={}, consumer_aggregate_sequences={}, "
                        + "idempotency_requests={}, dead_letters={}, audit_log={}, order_status_history={}",
                deletedOutbox,
                deletedProcessed,
                deletedPositions,
                deletedIdempotency,
                deletedDeadLetters,
                deletedAuditLog,
                deletedHistory
        );
    }


    /**
     * Whether this job leaves the table alone: no retention configured, or the table is partitioned and
     * PartitionMaintenanceService drops its expired months.
     */
    private boolean keepsRows(Duration retention, PartitionedTable table) {
        return retention.isZero() || retention.isNegative() || partitionCatalog.isPartitioned(table);
    }


    /**
     * Deletes in short statements of at most batch-size rows, so a large backlog neither runs into the pool's
     * statement_timeout nor holds its row locks in one long transaction.
     */
    private int deleteInBatches(IntUnaryOperator deleteBatch) {
        int deleted = 0;
        while (true) {
            int deletedInBatch = deleteBatch.applyAsInt(batchSize);
            deleted += deletedInBatch;
            if (deletedInBatch < batchSize || Thread.currentThread().isInterrupted()) {
                return deleted;
            }
        }
    }
}
