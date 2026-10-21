package com.laioffer.onlineorder;

import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OutboxEventEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import com.laioffer.onlineorder.messaging.DeliveryEventPayload;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.contract.EventSchemaViolationException;
import com.laioffer.onlineorder.messaging.contract.EventTestSupport;
import com.laioffer.onlineorder.model.OrderHistoryItemDto;
import com.laioffer.onlineorder.model.OutboxEventDto;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.repository.OutboxEventRepository;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class OrderEventOutboxServiceTests {

    @Test
    void enqueueOrderEvent_forStatusChange_shouldReportTransitionTimeNotOrderCreationTime() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);
        LocalDateTime createdAt = LocalDateTime.of(2026, 4, 25, 12, 0);
        LocalDateTime transitionedAt = LocalDateTime.of(2026, 4, 25, 12, 45, 30);
        OrderEntity acceptedOrder = new OrderEntity(10L, 3L, Money.of("19.00", "USD"), "ACCEPTED", createdAt);

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
        OrderEntity placedOrder = new OrderEntity(10L, 3L, Money.of("19.00", "USD"), "PLACED", createdAt);

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
                10L, 3L, Money.of("19.00", "USD"), "CANCELLED", createdAt, 2L, "MERCHANT_TIMEOUT");

        service.enqueueOrderEvent(cancelledOrder, List.of(), null, createdAt.plusMinutes(10));

        OrderEventEnvelope envelope = service.readEnvelope(savedOutboxEvent(repository).payload());
        Assertions.assertEquals("order.cancelled", envelope.eventType());
        Assertions.assertEquals(2L, envelope.data().restaurantId());
        Assertions.assertEquals("MERCHANT_TIMEOUT", envelope.data().reason());
    }


    @Test
    void enqueueOrderEvent_shouldWriteTheOrderLinesAsTheEventContractHasThem() throws Exception {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);
        LocalDateTime createdAt = LocalDateTime.of(2026, 4, 25, 12, 0);
        OrderEntity paidOrder = new OrderEntity(10L, 3L, Money.of("21.18", "USD"), "PAID", createdAt, 2L, null);
        OrderHistoryItemDto line = new OrderHistoryItemDto(
                5L, 7L, 2L, new BigDecimal("10.59"), "USD", 2, "Whopper Meal", "Meal", "https://example.com/whopper.png");

        service.enqueueOrderEvent(paidOrder, List.of(line), "checkout-key", createdAt);

        OutboxEventEntity stored = savedOutboxEvent(repository);
        JsonNode payload = EventTestSupport.mapper().readTree(stored.payload());
        Assertions.assertEquals(1, payload.get("event_version").asInt());
        Assertions.assertEquals(1L, payload.get("sequence").asLong());
        Assertions.assertEquals(1L, stored.aggregateSequence());
        JsonNode item = payload.get("data").get("items").get(0);
        Assertions.assertEquals(5L, item.get("id").asLong());
        Assertions.assertEquals(7L, item.get("menu_item_id").asLong());
        Assertions.assertEquals("10.59", item.get("price").decimalValue().toPlainString());
        Assertions.assertEquals(2, item.get("quantity").asInt());
        Assertions.assertEquals("Whopper Meal", item.get("menu_item_name").asString());
        // The lines carry no currency of their own (omitted, not null); the payload names the order's.
        Assertions.assertFalse(item.has("currency"));
        Assertions.assertEquals("USD", payload.get("data").get("currency").asString());
        Assertions.assertTrue(stored.payload().contains("\"total_price\":21.18"), stored.payload());
    }


    @Test
    void enqueueOrderEvent_shouldCarryTheOrdersCurrencyOnceForTheTotalAndTheLines() throws Exception {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);
        LocalDateTime createdAt = LocalDateTime.of(2026, 4, 25, 12, 0);
        OrderEntity paidOrder = new OrderEntity(10L, 3L, Money.of("0.50", "EUR"), "PAID", createdAt, 2L, null);
        List<OrderHistoryItemDto> items = List.of(new OrderHistoryItemDto(
                1L, 30L, 2L, new BigDecimal("0.10"), "EUR", 5, "Mint", null, null));

        service.enqueueOrderEvent(paidOrder, items, null, createdAt);

        String payload = savedOutboxEvent(repository).payload();
        JsonNode data = EventTestSupport.mapper().readTree(payload).get("data");
        Assertions.assertEquals("EUR", data.get("currency").asString());
        Assertions.assertEquals(0, new BigDecimal("0.50").compareTo(data.get("total_price").decimalValue()));
        Assertions.assertFalse(data.get("items").get(0).has("currency"), payload);
        Assertions.assertFalse(data.has("currencyCode"), "only the record components are serialized: " + payload);
        OrderEventEnvelope envelope = service.readEnvelope(payload);
        Assertions.assertEquals("EUR", envelope.data().currencyCode());
        Assertions.assertNull(envelope.data().items().get(0).currency());
    }


    @Test
    void enqueueEvent_shouldStoreAnEventOfAnyAggregateWithTheNextSequenceOfThatAggregate() throws Exception {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);

        service.enqueueEvent("delivery-events", "10", deliveryEvent("evt-7", 7L, "ASSIGNED"));
        service.enqueueEvent("delivery-events", "10", deliveryEvent("evt-8", 7L, "PICKED_UP"));
        service.enqueueEvent("delivery-events", "11", deliveryEvent("evt-9", 8L, "ASSIGNED"));

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        Mockito.verify(repository, Mockito.times(3)).save(captor.capture());
        OutboxEventEntity first = captor.getAllValues().get(0);
        Assertions.assertEquals("DELIVERY", first.aggregateType());
        Assertions.assertEquals(7L, first.aggregateId());
        Assertions.assertEquals("delivery-events", first.topic());
        Assertions.assertEquals("10", first.eventKey());
        Assertions.assertEquals("delivery.assigned", first.eventType());
        Assertions.assertEquals("evt-7", first.eventId());
        Assertions.assertEquals("PENDING", first.status());
        Assertions.assertEquals(0, first.attempts());
        Assertions.assertEquals(
                List.of(1L, 2L, 1L),
                captor.getAllValues().stream().map(OutboxEventEntity::aggregateSequence).toList()
        );
        for (OutboxEventEntity stored : captor.getAllValues()) {
            JsonNode payload = EventTestSupport.mapper().readTree(stored.payload());
            Assertions.assertEquals(stored.aggregateSequence(), payload.get("sequence").asLong());
            Assertions.assertEquals(stored.eventId(), payload.get("event_id").asString());
        }
    }


    @Test
    void enqueueEvent_whenTheEventBreaksItsSchema_shouldStoreNothingAndCountTheViolation() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OrderEventOutboxService service = newService(repository, meterRegistry);

        EventSchemaViolationException violation = Assertions.assertThrows(
                EventSchemaViolationException.class,
                () -> service.enqueueEvent("delivery-events", "10", deliveryEvent("evt-7", 7L, "LOST"))
        );

        Assertions.assertTrue(violation.getMessage().contains("/data/status"), violation.getMessage());
        Mockito.verify(repository, Mockito.never()).save(Mockito.any());
        Assertions.assertEquals(1.0, meterRegistry.get("onlineorder.events.schema_violations")
                .tag("side", "producer")
                .tag("topic", "delivery-events")
                .tag("event_type", "delivery.assigned")
                .counter()
                .count());
    }


    @Test
    void enqueueEvent_onATopicWithoutContractsOrForAnUnpublishedVersion_shouldStoreNothing() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);
        DeliveryEventEnvelope assigned = deliveryEvent("evt-7", 7L, "ASSIGNED");
        DeliveryEventEnvelope unpublishedVersion = new DeliveryEventEnvelope("evt-8", 2, "delivery.assigned",
                "DELIVERY", 7L, assigned.occurredAt(), "evt-8", null, assigned.data());

        Assertions.assertThrows(
                EventSchemaViolationException.class,
                () -> service.enqueueEvent("rider-events", "10", assigned)
        );
        Assertions.assertThrows(
                EventSchemaViolationException.class,
                () -> service.enqueueEvent("order-events", "10", assigned)
        );
        EventSchemaViolationException newerVersion = Assertions.assertThrows(
                EventSchemaViolationException.class,
                () -> service.enqueueEvent("delivery-events", "10", unpublishedVersion)
        );

        Assertions.assertTrue(newerVersion.getMessage().contains("is not published"), newerVersion.getMessage());
        Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    }


    @Test
    void readEnvelope_whenPayloadPredatesRestaurantAndReason_shouldLeaveThemNull() {
        OrderEventOutboxService service = newService(Mockito.mock(OutboxEventRepository.class));

        OrderEventEnvelope envelope = service.readEnvelope("""
                {"event_id":"evt-legacy","event_version":1,"event_type":"order.paid","aggregate_type":"ORDER",
                 "aggregate_id":10,"data":{"customer_id":3,"status":"PAID","total_price":19.0,"items":[]}}
                """);

        Assertions.assertEquals(3L, envelope.data().customerId());
        Assertions.assertNull(envelope.data().restaurantId());
        Assertions.assertNull(envelope.data().reason());
        // Every order was USD before events carried a currency.
        Assertions.assertNull(envelope.data().currency());
        Assertions.assertEquals("USD", envelope.data().currencyCode());
    }


    @Test
    void claimNextBatch_shouldClaimTheBatchUnderANewTokenWithOneBulkUpdate() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);
        List<OutboxEventEntity> lockedEvents = List.of(eventWithId(1L), eventWithId(2L), eventWithId(3L));
        Mockito.when(repository.lockNextBatchReadyToPublish(
                Mockito.eq("PENDING"),
                Mockito.eq("PROCESSING"),
                Mockito.any(LocalDateTime.class),
                Mockito.eq(20)
        )).thenReturn(lockedEvents);

        List<OutboxEventEntity> firstClaim = service.claimNextBatch();
        List<OutboxEventEntity> secondClaim = service.claimNextBatch();

        ArgumentCaptor<String> tokens = ArgumentCaptor.forClass(String.class);
        Mockito.verify(repository, Mockito.times(2)).markProcessing(
                Mockito.eq(List.of(1L, 2L, 3L)),
                Mockito.eq("PROCESSING"),
                tokens.capture(),
                Mockito.any(LocalDateTime.class)
        );
        String firstToken = tokens.getAllValues().get(0);
        String secondToken = tokens.getAllValues().get(1);
        Assertions.assertNotNull(firstToken);
        Assertions.assertNotEquals(firstToken, secondToken);
        Assertions.assertEquals(List.of(1L, 2L, 3L), firstClaim.stream().map(OutboxEventEntity::id).toList());
        Assertions.assertTrue(firstClaim.stream().allMatch(event -> firstToken.equals(event.claimToken())));
        Assertions.assertTrue(secondClaim.stream().allMatch(event -> secondToken.equals(event.claimToken())));
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
                Mockito.anyString(),
                Mockito.any(LocalDateTime.class)
        );
    }


    @Test
    void markPublished_shouldMarkTheClaimsEventsPublishedWithOneBulkUpdate() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);

        service.markPublished("claim-1", List.of(1L, 2L));
        service.markPublished("claim-1", List.of());

        Mockito.verify(repository, Mockito.times(1)).markPublished(
                Mockito.eq(List.of(1L, 2L)),
                Mockito.eq("claim-1"),
                Mockito.eq("PROCESSING"),
                Mockito.eq("PUBLISHED"),
                Mockito.any(LocalDateTime.class),
                Mockito.any(LocalDateTime.class)
        );
        Mockito.verifyNoMoreInteractions(repository);
    }


    @Test
    void releaseClaims_shouldReturnTheClaimsProcessingEventsToPending() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);

        service.releaseClaims("claim-1", List.of(4L, 5L));
        service.releaseClaims("claim-1", List.of());

        Mockito.verify(repository, Mockito.times(1)).releaseClaims(
                Mockito.eq(List.of(4L, 5L)),
                Mockito.eq("claim-1"),
                Mockito.eq("PROCESSING"),
                Mockito.eq("PENDING"),
                Mockito.any(LocalDateTime.class)
        );
        Mockito.verifyNoMoreInteractions(repository);
    }


    @Test
    void releaseFailedClaims_shouldReturnTheClaimsProcessingEventsToPendingWithAbbreviatedError() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);

        service.releaseFailedClaims("claim-1", List.of(4L, 5L), "x".repeat(1200));
        service.releaseFailedClaims("claim-1", List.of(), "Kafka unavailable");

        Mockito.verify(repository, Mockito.times(1)).releaseFailedClaims(
                Mockito.eq(List.of(4L, 5L)),
                Mockito.eq("claim-1"),
                Mockito.eq("PROCESSING"),
                Mockito.eq("PENDING"),
                Mockito.any(LocalDateTime.class),
                Mockito.eq("x".repeat(1000))
        );
        Mockito.verifyNoMoreInteractions(repository);
    }


    @Test
    void markRetryableFailure_withAttemptsLeft_shouldKeepItPendingWithoutReadingTheRowFirst() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);
        Mockito.when(repository.markPendingRetry(
                Mockito.eq(1L),
                Mockito.eq("claim-1"),
                Mockito.eq("PROCESSING"),
                Mockito.eq("PENDING"),
                Mockito.eq(2),
                Mockito.any(LocalDateTime.class),
                Mockito.eq("Kafka unavailable")
        )).thenReturn(1);

        Assertions.assertEquals(
                OrderEventOutboxService.RetryableFailure.RETRY_PENDING,
                service.markRetryableFailure("claim-1", 1L, "Kafka unavailable")
        );

        Mockito.verifyNoMoreInteractions(repository);
    }


    @Test
    void markRetryableFailure_outOfAttempts_shouldMarkItFailed() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);
        Mockito.when(repository.markFailed(
                Mockito.eq(1L),
                Mockito.eq("claim-1"),
                Mockito.eq("PROCESSING"),
                Mockito.eq("FAILED"),
                Mockito.eq(2),
                Mockito.any(LocalDateTime.class),
                Mockito.eq("Kafka unavailable")
        )).thenReturn(1);

        Assertions.assertEquals(
                OrderEventOutboxService.RetryableFailure.FAILED,
                service.markRetryableFailure("claim-1", 1L, "Kafka unavailable")
        );

        // The retry write refused it first (no attempts left): its result is the mock's 0.
        Mockito.verify(repository).markPendingRetry(
                Mockito.eq(1L),
                Mockito.eq("claim-1"),
                Mockito.eq("PROCESSING"),
                Mockito.eq("PENDING"),
                Mockito.eq(2),
                Mockito.any(LocalDateTime.class),
                Mockito.eq("Kafka unavailable")
        );
    }


    @Test
    void markRetryableFailure_whenTheClaimWasTakenOver_shouldChangeNothing() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);

        // Both fenced writes match no row: the event is no longer PROCESSING under this claim.
        Assertions.assertEquals(
                OrderEventOutboxService.RetryableFailure.CLAIM_LOST,
                service.markRetryableFailure("claim-1", 1L, "Kafka unavailable")
        );

        Mockito.verify(repository).markPendingRetry(
                Mockito.anyLong(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyInt(),
                Mockito.any(LocalDateTime.class),
                Mockito.anyString()
        );
        Mockito.verify(repository).markFailed(
                Mockito.anyLong(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyInt(),
                Mockito.any(LocalDateTime.class),
                Mockito.anyString()
        );
        Mockito.verifyNoMoreInteractions(repository);
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


    @Test
    void abandonFailedEvent_whenEventIsFailed_shouldMarkItAbandonedKeepingItsError() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);
        Mockito.when(repository.lockById(1L)).thenReturn(eventWithStatus("FAILED", 10, "RecordTooLargeException"));
        Mockito.when(repository.markFailedAbandoned(
                Mockito.eq(1L),
                Mockito.eq("FAILED"),
                Mockito.eq("ABANDONED"),
                Mockito.any(LocalDateTime.class)
        )).thenReturn(1);
        Mockito.when(repository.findById(1L))
                .thenReturn(Optional.of(eventWithStatus("ABANDONED", 10, "RecordTooLargeException")));

        OutboxEventDto dto = service.abandonFailedEvent(1L);

        Assertions.assertEquals("ABANDONED", dto.status());
        Assertions.assertEquals("RecordTooLargeException", dto.lastError());
    }


    @Test
    void abandonFailedEvent_whenEventIsNotFailed_shouldRejectWithoutChangingIt() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);
        Mockito.when(repository.lockById(1L)).thenReturn(eventWithStatus("PENDING", 0, null));
        Mockito.when(repository.lockById(2L)).thenReturn(null);

        Assertions.assertThrows(BadRequestException.class, () -> service.abandonFailedEvent(1L));
        Assertions.assertThrows(ResourceNotFoundException.class, () -> service.abandonFailedEvent(2L));
        Mockito.verify(repository, Mockito.never()).markFailedAbandoned(
                Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.any(LocalDateTime.class));
    }


    @Test
    void abandonFailedEvent_whenTheRowChangedMeanwhile_shouldConflict() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);
        Mockito.when(repository.lockById(1L)).thenReturn(eventWithStatus("FAILED", 10, "boom"));

        Assertions.assertThrows(ConflictException.class, () -> service.abandonFailedEvent(1L));
    }


    @Test
    void hasPendingSuccessor_shouldAskOnlyForPublishedEvents() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        OrderEventOutboxService service = newService(repository);
        Mockito.when(repository.countWithPendingSuccessor(List.of(4L, 5L))).thenReturn(1L);

        Assertions.assertFalse(service.hasPendingSuccessor(List.of()));
        Assertions.assertTrue(service.hasPendingSuccessor(List.of(4L, 5L)));
        Mockito.verify(repository, Mockito.times(1)).countWithPendingSuccessor(Mockito.anyList());
    }


    private OrderEventOutboxService newService(OutboxEventRepository repository) {
        return newService(repository, new SimpleMeterRegistry());
    }


    /**
     * The application's JSON mapper and event contracts, and an in-memory sequence counter per aggregate.
     */
    private OrderEventOutboxService newService(OutboxEventRepository repository, SimpleMeterRegistry meterRegistry) {
        Map<String, Long> sequences = new HashMap<>();
        return new OrderEventOutboxService(
                20,
                Duration.ofSeconds(30),
                2,
                "order-events",
                EventTestSupport.mapper(),
                repository,
                (aggregateType, aggregateId) -> sequences.merge(aggregateType + ":" + aggregateId, 1L, Long::sum),
                EventTestSupport.contracts(meterRegistry)
        );
    }


    private static DeliveryEventEnvelope deliveryEvent(String eventId, Long deliveryId, String status) {
        return new DeliveryEventEnvelope(
                eventId,
                1,
                "PICKED_UP".equals(status) ? "delivery.picked_up" : "delivery.assigned",
                "DELIVERY",
                deliveryId,
                LocalDateTime.of(2026, 4, 25, 12, 30),
                eventId,
                null,
                new DeliveryEventPayload(10L, 3L, 2L, 4L, "Alice", status)
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
