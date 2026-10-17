package com.laioffer.onlineorder;


import com.laioffer.onlineorder.repository.ConsumerAggregateSequenceRepository;
import com.laioffer.onlineorder.repository.DeadLetterEventRepository;
import com.laioffer.onlineorder.repository.IdempotencyRequestRepository;
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


    @Test
    void cleanupExpiredRecords_shouldDeleteExpiredOperationalData() {
        MaintenanceCleanupService service = new MaintenanceCleanupService(
                true,
                Duration.ofDays(7),
                Duration.ofDays(14),
                Duration.ofDays(3),
                Duration.ofDays(30),
                1000,
                outboxEventRepository,
                processedEventRepository,
                idempotencyRequestRepository,
                deadLetterEventRepository,
                consumerAggregateSequenceRepository
        );

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
        MaintenanceCleanupService service = new MaintenanceCleanupService(
                true,
                Duration.ofDays(7),
                Duration.ofDays(14),
                Duration.ofDays(3),
                Duration.ofDays(30),
                1000,
                outboxEventRepository,
                processedEventRepository,
                idempotencyRequestRepository,
                deadLetterEventRepository,
                consumerAggregateSequenceRepository
        );
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
        MaintenanceCleanupService service = new MaintenanceCleanupService(
                false,
                Duration.ofDays(7),
                Duration.ofDays(14),
                Duration.ofDays(3),
                Duration.ofDays(30),
                1000,
                outboxEventRepository,
                processedEventRepository,
                idempotencyRequestRepository,
                deadLetterEventRepository,
                consumerAggregateSequenceRepository
        );

        service.cleanupExpiredRecords();

        Mockito.verifyNoInteractions(
                outboxEventRepository,
                processedEventRepository,
                idempotencyRequestRepository,
                deadLetterEventRepository,
                consumerAggregateSequenceRepository
        );
    }
}
