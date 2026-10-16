package com.laioffer.onlineorder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OutboxEventEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.model.OutboxEventDto;
import com.laioffer.onlineorder.repository.OutboxEventRepository;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class OrderEventOutboxServiceTests {

    @Test
    void enqueueOrderEvent_forStatusChange_shouldReportTransitionTimeNotOrderCreationTime() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);
        LocalDateTime createdAt = LocalDateTime.of(2026, 4, 25, 12, 0);
        LocalDateTime transitionedAt = LocalDateTime.of(2026, 4, 25, 12, 45, 30);
        OrderEntity acceptedOrder = new OrderEntity(10L, 3L, new BigDecimal("19.00"), "ACCEPTED", createdAt);

        service.enqueueOrderEvent(acceptedOrder, List.of(), null, transitionedAt);

        OrderEventEnvelope envelope = service.readEnvelope(savedOutboxEvent(repository).payload());
        Assertions.assertEquals("order.accepted", envelope.eventType());
        Assertions.assertEquals(transitionedAt, envelope.occurredAt());
        Assertions.assertEquals(createdAt, envelope.data().createdAt());
    }


    @Test
    void enqueueOrderEvent_forNewOrder_shouldReportCreationTime() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);
        LocalDateTime createdAt = LocalDateTime.of(2026, 4, 25, 12, 0);
        OrderEntity placedOrder = new OrderEntity(10L, 3L, new BigDecimal("19.00"), "PLACED", createdAt);

        service.enqueueOrderEvent(placedOrder, List.of(), "checkout-key", createdAt);

        OrderEventEnvelope envelope = service.readEnvelope(savedOutboxEvent(repository).payload());
        Assertions.assertEquals(createdAt, envelope.occurredAt());
        Assertions.assertEquals(createdAt, envelope.data().createdAt());
        Assertions.assertEquals("checkout-key", envelope.idempotencyKey());
    }


    @Test
    void enqueueOrderEvent_shouldCarryTheRestaurantAndCancelReason() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);
        LocalDateTime createdAt = LocalDateTime.of(2026, 4, 25, 12, 0);
        OrderEntity cancelledOrder = new OrderEntity(
                10L, 3L, new BigDecimal("19.00"), "CANCELLED", createdAt, 2L, "MERCHANT_TIMEOUT");

        service.enqueueOrderEvent(cancelledOrder, List.of(), null, createdAt.plusMinutes(10));

        OrderEventEnvelope envelope = service.readEnvelope(savedOutboxEvent(repository).payload());
        Assertions.assertEquals("order.cancelled", envelope.eventType());
        Assertions.assertEquals(2L, envelope.data().restaurantId());
        Assertions.assertEquals("MERCHANT_TIMEOUT", envelope.data().reason());
    }


    @Test
    void readEnvelope_whenPayloadPredatesRestaurantAndReason_shouldLeaveThemNull() {
        OrderEventOutboxService service = newService(Mockito.mock(OutboxEventRepository.class));

        OrderEventEnvelope envelope = service.readEnvelope("""
                {"eventId":"evt-legacy","eventVersion":1,"eventType":"order.paid","aggregateType":"ORDER",
                 "aggregateId":10,"data":{"customerId":3,"status":"PAID","totalPrice":19.0,"items":[]}}
                """);

        Assertions.assertEquals(3L, envelope.data().customerId());
        Assertions.assertNull(envelope.data().restaurantId());
        Assertions.assertNull(envelope.data().reason());
    }


    @Test
    void claimNextBatch_shouldMarkClaimedEventsProcessingWithOneBulkUpdate() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);
        List<OutboxEventEntity> lockedEvents = List.of(eventWithId(1L), eventWithId(2L), eventWithId(3L));
        Mockito.when(repository.lockNextBatchReadyToPublish(
                Mockito.eq("PENDING"),
                Mockito.eq("PROCESSING"),
                Mockito.any(LocalDateTime.class),
                Mockito.eq(20)
        )).thenReturn(lockedEvents);

        List<OutboxEventEntity> claimedEvents = service.claimNextBatch();

        Assertions.assertEquals(lockedEvents, claimedEvents);
        Mockito.verify(repository, Mockito.times(1)).markProcessing(
                Mockito.eq(List.of(1L, 2L, 3L)),
                Mockito.eq("PROCESSING"),
                Mockito.any(LocalDateTime.class)
        );
        Mockito.verify(repository, Mockito.times(1)).markProcessing(
                Mockito.anyList(),
                Mockito.anyString(),
                Mockito.any(LocalDateTime.class)
        );
    }


    @Test
    void claimNextBatch_whenNothingIsReady_shouldNotUpdateEvents() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);
        Mockito.when(repository.lockNextBatchReadyToPublish(
                Mockito.eq("PENDING"),
                Mockito.eq("PROCESSING"),
                Mockito.any(LocalDateTime.class),
                Mockito.eq(20)
        )).thenReturn(List.of());

        Assertions.assertTrue(service.claimNextBatch().isEmpty());

        Mockito.verify(repository, Mockito.never()).markProcessing(
                Mockito.anyList(),
                Mockito.anyString(),
                Mockito.any(LocalDateTime.class)
        );
    }


    @Test
    void markPublished_shouldMarkAllEventsPublishedWithOneBulkUpdate() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);

        service.markPublished(List.of(1L, 2L));
        service.markPublished(List.of());

        Mockito.verify(repository, Mockito.times(1)).markPublished(
                Mockito.eq(List.of(1L, 2L)),
                Mockito.eq("PUBLISHED"),
                Mockito.any(LocalDateTime.class),
                Mockito.any(LocalDateTime.class)
        );
        Mockito.verifyNoMoreInteractions(repository);
    }


    @Test
    void releaseClaims_shouldReturnProcessingEventsToPending() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);

        service.releaseClaims(List.of(4L, 5L));
        service.releaseClaims(List.of());

        Mockito.verify(repository, Mockito.times(1)).releaseClaims(
                Mockito.eq(List.of(4L, 5L)),
                Mockito.eq("PROCESSING"),
                Mockito.eq("PENDING"),
                Mockito.any(LocalDateTime.class)
        );
        Mockito.verifyNoMoreInteractions(repository);
    }


    @Test
    void releaseFailedClaims_shouldReturnProcessingEventsToPendingWithAbbreviatedError() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);

        service.releaseFailedClaims(List.of(4L, 5L), "x".repeat(1200));
        service.releaseFailedClaims(List.of(), "Kafka unavailable");

        Mockito.verify(repository, Mockito.times(1)).releaseFailedClaims(
                Mockito.eq(List.of(4L, 5L)),
                Mockito.eq("PROCESSING"),
                Mockito.eq("PENDING"),
                Mockito.any(LocalDateTime.class),
                Mockito.eq("x".repeat(1000))
        );
        Mockito.verifyNoMoreInteractions(repository);
    }


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


    private OutboxEventEntity savedOutboxEvent(OutboxEventRepository repository) {
        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        Mockito.verify(repository).save(captor.capture());
        return captor.getValue();
    }


    private OutboxEventEntity eventWithId(Long id) {
        LocalDateTime now = LocalDateTime.of(2026, 4, 25, 12, 0);
        return new OutboxEventEntity(
                id,
                "ORDER",
                10L,
                "evt-" + id,
                "order-events",
                "10",
                "order.created",
                "{}",
                "PENDING",
                0,
                null,
                now,
                now,
                null
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
