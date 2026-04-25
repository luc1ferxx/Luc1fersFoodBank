package com.laioffer.onlineorder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.entity.OutboxEventEntity;
import com.laioffer.onlineorder.repository.OutboxEventRepository;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class OrderEventOutboxServiceTests {

    @Test
    void markRetryableFailure_whenAttemptsReachMax_shouldMarkFailed() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = new OrderEventOutboxService(
                20,
                Duration.ofSeconds(30),
                2,
                "order-events",
                new ObjectMapper().findAndRegisterModules(),
                repository
        );
        OutboxEventEntity event = eventWithAttempts(2);
        Mockito.when(repository.findById(1L)).thenReturn(Optional.of(event));

        service.markRetryableFailure(1L, "Kafka unavailable");

        Mockito.verify(repository).markFailed(
                Mockito.eq(1L),
                Mockito.eq("FAILED"),
                Mockito.any(LocalDateTime.class),
                Mockito.eq("Kafka unavailable")
        );
        Mockito.verify(repository, Mockito.never()).markPendingRetry(
                Mockito.anyLong(),
                Mockito.anyString(),
                Mockito.any(LocalDateTime.class),
                Mockito.anyString()
        );
    }

    @Test
    void markRetryableFailure_whenAttemptsBelowMax_shouldKeepPending() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = new OrderEventOutboxService(
                20,
                Duration.ofSeconds(30),
                2,
                "order-events",
                new ObjectMapper().findAndRegisterModules(),
                repository
        );
        OutboxEventEntity event = eventWithAttempts(1);
        Mockito.when(repository.findById(1L)).thenReturn(Optional.of(event));

        service.markRetryableFailure(1L, "Kafka unavailable");

        Mockito.verify(repository).markPendingRetry(
                Mockito.eq(1L),
                Mockito.eq("PENDING"),
                Mockito.any(LocalDateTime.class),
                Mockito.eq("Kafka unavailable")
        );
        Mockito.verify(repository, Mockito.never()).markFailed(
                Mockito.anyLong(),
                Mockito.anyString(),
                Mockito.any(LocalDateTime.class),
                Mockito.anyString()
        );
    }

    private OutboxEventEntity eventWithAttempts(int attempts) {
        LocalDateTime now = LocalDateTime.of(2026, 4, 25, 12, 0);
        return new OutboxEventEntity(
                1L,
                "ORDER",
                10L,
                "evt-1",
                "order-events",
                "10",
                "order.created",
                "{}",
                "PROCESSING",
                attempts,
                null,
                now,
                now,
                null
        );
    }
}
