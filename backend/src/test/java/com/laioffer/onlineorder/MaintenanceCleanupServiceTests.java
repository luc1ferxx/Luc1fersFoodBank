package com.laioffer.onlineorder;


import com.laioffer.onlineorder.repository.DeadLetterEventRepository;
import com.laioffer.onlineorder.repository.IdempotencyRequestRepository;
import com.laioffer.onlineorder.repository.OutboxEventRepository;
import com.laioffer.onlineorder.repository.ProcessedEventRepository;
import com.laioffer.onlineorder.service.MaintenanceCleanupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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


    @Test
    void cleanupExpiredRecords_shouldDeleteExpiredOperationalData() {
        MaintenanceCleanupService service = new MaintenanceCleanupService(
                true,
                Duration.ofDays(7),
                Duration.ofDays(14),
                Duration.ofDays(3),
                Duration.ofDays(30),
                outboxEventRepository,
                processedEventRepository,
                idempotencyRequestRepository,
                deadLetterEventRepository
        );

        service.cleanupExpiredRecords();

        Mockito.verify(outboxEventRepository).deleteByStatusAndPublishedAtBefore(
                Mockito.eq("PUBLISHED"),
                Mockito.any(LocalDateTime.class)
        );
        Mockito.verify(processedEventRepository).deleteByProcessedAtBefore(Mockito.any(LocalDateTime.class));
        Mockito.verify(idempotencyRequestRepository).deleteByUpdatedAtBefore(Mockito.any(LocalDateTime.class));
        Mockito.verify(deadLetterEventRepository).deleteByReplayStatusAndUpdatedAtBefore(
                Mockito.eq("REPLAYED"),
                Mockito.any(LocalDateTime.class)
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
                outboxEventRepository,
                processedEventRepository,
                idempotencyRequestRepository,
                deadLetterEventRepository
        );

        service.cleanupExpiredRecords();

        Mockito.verifyNoInteractions(
                outboxEventRepository,
                processedEventRepository,
                idempotencyRequestRepository,
                deadLetterEventRepository
        );
    }
}
