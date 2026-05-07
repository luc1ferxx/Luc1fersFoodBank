package com.laioffer.onlineorder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.entity.OutboxEventEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.model.OutboxEventDto;
import com.laioffer.onlineorder.repository.OutboxEventRepository;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
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


    @Test
    void getFailedEvents_shouldReturnFailedOutboxEventsInRepositoryOrder() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);
        OutboxEventEntity failedEvent = eventWithStatus("FAILED", 10, "Kafka unavailable");
        Mockito.when(repository.findByStatusOrderByUpdatedAtAsc("FAILED", 50)).thenReturn(List.of(failedEvent));

        List<OutboxEventDto> events = service.getFailedEvents(50);

        Assertions.assertEquals(1, events.size());
        Assertions.assertEquals(1L, events.get(0).id());
        Assertions.assertEquals("FAILED", events.get(0).status());
        Assertions.assertEquals("Kafka unavailable", events.get(0).lastError());
    }


    @Test
    void retryFailedEvent_whenEventIsFailed_shouldResetToPendingForPublisher() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);
        OutboxEventEntity failedEvent = eventWithStatus("FAILED", 10, "Kafka unavailable");
        OutboxEventEntity retryableEvent = eventWithStatus("PENDING", 0, null);
        Mockito.when(repository.lockById(1L)).thenReturn(failedEvent);
        Mockito.when(repository.resetFailedForRetry(
                Mockito.eq(1L),
                Mockito.eq("FAILED"),
                Mockito.eq("PENDING"),
                Mockito.any(LocalDateTime.class)
        )).thenReturn(1);
        Mockito.when(repository.findById(1L)).thenReturn(Optional.of(retryableEvent));

        OutboxEventDto dto = service.retryFailedEvent(1L);

        Assertions.assertEquals("PENDING", dto.status());
        Assertions.assertEquals(0, dto.attempts());
        Assertions.assertNull(dto.lastError());
        Mockito.verify(repository).resetFailedForRetry(
                Mockito.eq(1L),
                Mockito.eq("FAILED"),
                Mockito.eq("PENDING"),
                Mockito.any(LocalDateTime.class)
        );
    }


    @Test
    void retryFailedEvent_whenEventIsNotFailed_shouldRejectWithoutResetting() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);
        Mockito.when(repository.lockById(1L)).thenReturn(eventWithStatus("PROCESSING", 2, null));

        Assertions.assertThrows(BadRequestException.class, () -> service.retryFailedEvent(1L));

        Mockito.verify(repository, Mockito.never()).resetFailedForRetry(
                Mockito.anyLong(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(LocalDateTime.class)
        );
    }


    @Test
    void retryFailedEvent_whenEventDoesNotExist_shouldReturnNotFound() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);
        Mockito.when(repository.lockById(1L)).thenReturn(null);

        Assertions.assertThrows(ResourceNotFoundException.class, () -> service.retryFailedEvent(1L));
    }


    private OrderEventOutboxService newService(OutboxEventRepository repository) {
        return new OrderEventOutboxService(
                20,
                Duration.ofSeconds(30),
                2,
                "order-events",
                new ObjectMapper().findAndRegisterModules(),
                repository
        );
    }


    private OutboxEventEntity eventWithAttempts(int attempts) {
        return eventWithStatus("PROCESSING", attempts, null);
    }


    private OutboxEventEntity eventWithStatus(String status, int attempts, String lastError) {
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
                status,
                attempts,
                lastError,
                now,
                now,
                null
        );
    }
}
