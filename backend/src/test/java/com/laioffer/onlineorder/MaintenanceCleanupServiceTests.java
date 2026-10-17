package com.laioffer.onlineorder;


import com.laioffer.onlineorder.repository.ConsumerAggregateSequenceRepository;
import com.laioffer.onlineorder.audit.AuditLogRepository;
import com.laioffer.onlineorder.partition.PartitionCatalog;
import com.laioffer.onlineorder.partition.PartitionedTable;
import com.laioffer.onlineorder.repository.DeadLetterEventRepository;
import com.laioffer.onlineorder.repository.IdempotencyRequestRepository;
import com.laioffer.onlineorder.repository.OrderStatusHistoryRepository;
import com.laioffer.onlineorder.repository.OutboxEventRepository;
import com.laioffer.onlineorder.repository.ProcessedEventRepository;
import com.laioffer.onlineorder.service.MaintenanceCleanupService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.time.Duration;
import java.time.LocalDateTime;


@ExtendWith(MockitoExtension.class)
class MaintenanceCleanupServiceTests {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private IdempotencyRequestRepository idempotencyRequestRepository;

    @Mock
    private DeadLetterEventRepository deadLetterEventRepository;

    @Mock
    private ConsumerAggregateSequenceRepository consumerAggregateSequenceRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    // Unstubbed: no table is partitioned (H2), so every retention runs here.
    @Mock
    private PartitionCatalog partitionCatalog;


    @Test
    void cleanupExpiredRecords_shouldDeleteExpiredOperationalData() {
        MaintenanceCleanupService service = service(true, Duration.ZERO);

        service.cleanupExpiredRecords();

        Mockito.verify(outboxEventRepository).deleteByStatusAndPublishedAtBefore(
                Mockito.eq("PUBLISHED"),
                Mockito.any(LocalDateTime.class),
                Mockito.eq(1000)
        );
        Mockito.verify(processedEventRepository).deleteByProcessedAtBefore(Mockito.any(LocalDateTime.class), Mockito.eq(1000));
        Mockito.verify(consumerAggregateSequenceRepository)
                .deleteByUpdatedAtBefore(Mockito.any(LocalDateTime.class), Mockito.eq(1000));
        Mockito.verify(idempotencyRequestRepository).deleteByUpdatedAtBefore(Mockito.any(LocalDateTime.class), Mockito.eq(1000));
        Mockito.verify(deadLetterEventRepository).deleteByReplayStatusAndUpdatedAtBefore(
                Mockito.eq("REPLAYED"),
                Mockito.any(LocalDateTime.class),
                Mockito.eq(1000)
        );
    }


    @Test
    void cleanupExpiredRecords_shouldKeepDeletingWhileBatchesComeBackFull() {
        MaintenanceCleanupService service = service(true, Duration.ZERO);
        Mockito.when(outboxEventRepository.deleteByStatusAndPublishedAtBefore(
                Mockito.eq("PUBLISHED"),
                Mockito.any(LocalDateTime.class),
                Mockito.eq(1000)
        )).thenReturn(1000, 1000, 1);
        Mockito.when(processedEventRepository.deleteByProcessedAtBefore(Mockito.any(LocalDateTime.class), Mockito.eq(1000)))
                .thenReturn(1000, 0);

        service.cleanupExpiredRecords();

        ArgumentCaptor<LocalDateTime> outboxCutoffs = ArgumentCaptor.forClass(LocalDateTime.class);
        Mockito.verify(outboxEventRepository, Mockito.times(3)).deleteByStatusAndPublishedAtBefore(
                Mockito.eq("PUBLISHED"),
                outboxCutoffs.capture(),
                Mockito.eq(1000)
        );
        // Every batch uses the same cutoff, so rows that expire during the run wait for the next one.
        Assertions.assertEquals(1, outboxCutoffs.getAllValues().stream().distinct().count());
        Mockito.verify(processedEventRepository, Mockito.times(2))
                .deleteByProcessedAtBefore(Mockito.any(LocalDateTime.class), Mockito.eq(1000));
        Mockito.verify(idempotencyRequestRepository).deleteByUpdatedAtBefore(Mockito.any(LocalDateTime.class), Mockito.eq(1000));
        Mockito.verify(deadLetterEventRepository).deleteByReplayStatusAndUpdatedAtBefore(
                Mockito.eq("REPLAYED"),
                Mockito.any(LocalDateTime.class),
                Mockito.eq(1000)
        );
    }


    @Test
    void cleanupExpiredRecords_shouldSkipWhenDisabled() {
        MaintenanceCleanupService service = service(false, Duration.ofDays(400));

        service.cleanupExpiredRecords();

        Mockito.verifyNoInteractions(
                outboxEventRepository,
                processedEventRepository,
                idempotencyRequestRepository,
                deadLetterEventRepository,
                consumerAggregateSequenceRepository,
                auditLogRepository,
                orderStatusHistoryRepository
        );
    }


    @Test
    void cleanupExpiredRecords_shouldKeepAuditLogAndHistoryWithoutARetention() {
        service(true, Duration.ZERO).cleanupExpiredRecords();

        Mockito.verifyNoInteractions(auditLogRepository, orderStatusHistoryRepository);
    }


    @Test
    void cleanupExpiredRecords_withARecordRetention_shouldDeleteExpiredAuditLogAndHistoryRows() {
        Mockito.when(auditLogRepository.deleteByOccurredAtBefore(Mockito.any(LocalDateTime.class), Mockito.eq(1000)))
                .thenReturn(1000, 3);
        LocalDateTime before = LocalDateTime.now();

        service(true, Duration.ofDays(400)).cleanupExpiredRecords();

        ArgumentCaptor<LocalDateTime> cutoffs = ArgumentCaptor.forClass(LocalDateTime.class);
        Mockito.verify(auditLogRepository, Mockito.times(2)).deleteByOccurredAtBefore(cutoffs.capture(), Mockito.eq(1000));
        Assertions.assertFalse(cutoffs.getValue().isBefore(before.minusDays(400)));
        Assertions.assertTrue(cutoffs.getValue().isBefore(before.minusDays(399)));
        Mockito.verify(orderStatusHistoryRepository)
                .deleteByOccurredAtBefore(Mockito.any(LocalDateTime.class), Mockito.eq(1000));
    }


    @Test
    void cleanupExpiredRecords_shouldLeavePartitionedTablesToThePartitionJob() {
        // PostgreSQL after V10: whole months are dropped by PartitionMaintenanceService instead.
        Mockito.when(partitionCatalog.isPartitioned(Mockito.any(PartitionedTable.class))).thenReturn(true);

        service(true, Duration.ofDays(400)).cleanupExpiredRecords();

        Mockito.verifyNoInteractions(processedEventRepository, auditLogRepository, orderStatusHistoryRepository);
        Mockito.verify(outboxEventRepository).deleteByStatusAndPublishedAtBefore(
                Mockito.eq("PUBLISHED"),
                Mockito.any(LocalDateTime.class),
                Mockito.eq(1000)
        );
        Mockito.verify(idempotencyRequestRepository).deleteByUpdatedAtBefore(Mockito.any(LocalDateTime.class), Mockito.eq(1000));
    }


    /**
     * @param recordRetention the retention of audit_log and order_status_history
     */
    private MaintenanceCleanupService service(boolean enabled, Duration recordRetention) {
        return new MaintenanceCleanupService(
                enabled,
                Duration.ofDays(7),
                Duration.ofDays(14),
                Duration.ofDays(3),
                Duration.ofDays(30),
                recordRetention,
                recordRetention,
                1000,
                outboxEventRepository,
                processedEventRepository,
                idempotencyRequestRepository,
                deadLetterEventRepository,
                consumerAggregateSequenceRepository,
                auditLogRepository,
                orderStatusHistoryRepository,
                partitionCatalog
        );
    }
}
