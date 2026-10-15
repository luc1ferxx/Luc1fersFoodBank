package com.laioffer.onlineorder;


import com.laioffer.onlineorder.entity.OutboxEventEntity;
import com.laioffer.onlineorder.messaging.OutboxEventPublisher;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;


import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;


@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTests {

    private static final Duration PUBLISH_INTERVAL = Duration.ofMillis(500);

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private OrderEventOutboxService orderEventOutboxService;

    @Mock
    private ApplicationMetricsService metricsService;

    private final MutableClock clock = new MutableClock();


    @Test
    void publishPendingEvents_shouldMarkEventPublishedAfterKafkaSend() {
        OutboxEventEntity event = new OutboxEventEntity(
                1L,
                "ORDER",
                5L,
                "evt-1",
                "order-events",
                "5",
                "order.created",
                "{\"order_id\":5}",
                "PENDING",
                0,
                null,
                LocalDateTime.of(2026, 4, 4, 12, 0),
                LocalDateTime.of(2026, 4, 4, 12, 0),
                null
        );
        OutboxEventPublisher publisher = newPublisher(20, Duration.ofSeconds(5));

        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(event));
        Mockito.when(kafkaTemplate.send(event.topic(), event.eventKey(), event.payload()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService).markPublished(List.of(event.id()));
        Mockito.verify(metricsService).recordOutboxPublishResult(event.topic(), true);
        Mockito.verify(orderEventOutboxService, Mockito.never()).markRetryableFailure(Mockito.anyLong(), Mockito.anyString());
        Mockito.verify(orderEventOutboxService, Mockito.never()).releaseClaims(Mockito.anyList());
    }


    @Test
    void publishPendingEvents_shouldReturnEventToPendingWhenKafkaSendFails() {
        OutboxEventEntity event = new OutboxEventEntity(
                2L,
                "ORDER",
                6L,
                "evt-2",
                "order-events",
                "6",
                "order.paid",
                "{\"order_id\":6}",
                "PENDING",
                1,
                null,
                LocalDateTime.of(2026, 4, 4, 12, 0),
                LocalDateTime.of(2026, 4, 4, 12, 0),
                null
        );
        OutboxEventPublisher publisher = newPublisher(20, Duration.ofSeconds(5));

        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(event));
        Mockito.when(kafkaTemplate.send(event.topic(), event.eventKey(), event.payload()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")));

        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService).markRetryableFailure(Mockito.eq(event.id()), Mockito.contains("Kafka unavailable"));
        Mockito.verify(metricsService).recordOutboxPublishResult(event.topic(), false);
        Mockito.verify(orderEventOutboxService, Mockito.never()).markPublished(Mockito.anyList());
    }


    @Test
    void publishPendingEvents_whenBatchesComeBackFull_shouldKeepClaimingUntilPartialBatch() {
        List<OutboxEventEntity> firstBatch = List.of(event(1L, "1"), event(2L, "2"));
        List<OutboxEventEntity> secondBatch = List.of(event(3L, "3"), event(4L, "4"));
        List<OutboxEventEntity> lastBatch = List.of(event(5L, "5"));
        OutboxEventPublisher publisher = newPublisher(2, Duration.ofSeconds(5));
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(firstBatch, secondBatch, lastBatch);
        Mockito.when(kafkaTemplate.send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService, Mockito.times(3)).claimNextBatch();
        Mockito.verify(kafkaTemplate, Mockito.times(5)).send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
        Mockito.verify(orderEventOutboxService).markPublished(List.of(1L, 2L));
        Mockito.verify(orderEventOutboxService).markPublished(List.of(3L, 4L));
        Mockito.verify(orderEventOutboxService).markPublished(List.of(5L));
        Mockito.verify(metricsService, Mockito.times(5)).recordOutboxPublishResult("order-events", true);
    }


    @Test
    void publishPendingEvents_whenNothingIsPending_shouldNotSend() {
        OutboxEventPublisher publisher = newPublisher(2, Duration.ofSeconds(5));
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of());

        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService).claimNextBatch();
        Mockito.verifyNoInteractions(kafkaTemplate, metricsService);
        Mockito.verify(orderEventOutboxService, Mockito.never()).markPublished(Mockito.anyList());
    }


    @Test
    @Timeout(10)
    void publishPendingEvents_shouldSendWholeBatchBeforeWaitingForAcknowledgements() {
        List<OutboxEventEntity> batch = List.of(event(1L, "1"), event(2L, "2"), event(3L, "3"));
        List<CompletableFuture<SendResult<String, String>>> pendingSends = new ArrayList<>();
        OutboxEventPublisher publisher = newPublisher(10, Duration.ofSeconds(2));
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(batch);
        Mockito.when(kafkaTemplate.send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(invocation -> {
                    CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
                    pendingSends.add(future);
                    if (pendingSends.size() == batch.size()) {
                        pendingSends.forEach(pendingSend -> pendingSend.complete(null));
                    }
                    return future;
                });

        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService).markPublished(List.of(1L, 2L, 3L));
        Mockito.verify(orderEventOutboxService, Mockito.never()).markRetryableFailure(Mockito.anyLong(), Mockito.anyString());
    }


    @Test
    void publishPendingEvents_whenEventFails_shouldNotSendLaterEventsWithSameKey() {
        OutboxEventEntity first = event(1L, "order-1");
        OutboxEventEntity otherOrder = event(2L, "order-2");
        OutboxEventEntity second = event(3L, "order-1");
        CompletableFuture<SendResult<String, String>> firstSend = new CompletableFuture<>();
        OutboxEventPublisher publisher = newPublisher(10, Duration.ofSeconds(5));
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(first, otherOrder, second));
        Mockito.when(kafkaTemplate.send(first.topic(), first.eventKey(), first.payload())).thenReturn(firstSend);
        Mockito.when(kafkaTemplate.send(otherOrder.topic(), otherOrder.eventKey(), otherOrder.payload()))
                .thenAnswer(invocation -> {
                    firstSend.completeExceptionally(new IllegalStateException("Record rejected"));
                    return CompletableFuture.completedFuture(null);
                });

        publisher.publishPendingEvents();

        Mockito.verify(kafkaTemplate, Mockito.never()).send(second.topic(), second.eventKey(), second.payload());
        Mockito.verify(orderEventOutboxService).markRetryableFailure(Mockito.eq(1L), Mockito.contains("Record rejected"));
        Mockito.verify(orderEventOutboxService).markPublished(List.of(2L));
        Mockito.verify(orderEventOutboxService).releaseClaims(List.of(3L));
    }


    @Test
    @Timeout(10)
    void publishPendingEvents_shouldSendNextEventOfKeyOnlyAfterPreviousOneWasAcknowledged() {
        OutboxEventEntity first = event(1L, "order-1");
        OutboxEventEntity second = event(2L, "order-1");
        OutboxEventEntity otherOrder = event(3L, "order-2");
        CompletableFuture<SendResult<String, String>> firstSend = new CompletableFuture<>();
        List<String> sentPayloads = new ArrayList<>();
        List<Boolean> firstAcknowledgedWhenSecondSent = new ArrayList<>();
        OutboxEventPublisher publisher = newPublisher(10, Duration.ofSeconds(2));
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(first, second, otherOrder));
        Mockito.when(kafkaTemplate.send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(invocation -> {
                    String payload = invocation.getArgument(2);
                    sentPayloads.add(payload);
                    if (payload.equals(first.payload())) {
                        return firstSend;
                    }
                    if (payload.equals(second.payload())) {
                        firstAcknowledgedWhenSecondSent.add(firstSend.isDone());
                    }
                    if (payload.equals(otherOrder.payload())) {
                        firstSend.complete(null);
                    }
                    return CompletableFuture.completedFuture(null);
                });

        publisher.publishPendingEvents();

        Assertions.assertEquals(List.of(first.payload(), otherOrder.payload(), second.payload()), sentPayloads);
        Assertions.assertEquals(List.of(true), firstAcknowledgedWhenSecondSent);
        Mockito.verify(orderEventOutboxService).markPublished(List.of(1L, 3L, 2L));
        Mockito.verify(orderEventOutboxService, Mockito.never()).markRetryableFailure(Mockito.anyLong(), Mockito.anyString());
    }


    @Test
    void publishPendingEvents_whenBrokerIsDown_shouldReleaseUnsentEventsAndBackOff() {
        OutboxEventEntity first = event(1L, "1");
        OutboxEventEntity second = event(2L, "2");
        OutboxEventEntity third = event(3L, "3");
        OutboxEventPublisher publisher = newPublisher(3, Duration.ofSeconds(5));
        Mockito.when(orderEventOutboxService.claimNextBatch())
                .thenReturn(List.of(first, second, third), List.of(first, second), List.of());
        Mockito.when(kafkaTemplate.send(first.topic(), first.eventKey(), first.payload()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Topic not present in metadata")))
                .thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(kafkaTemplate.send(second.topic(), second.eventKey(), second.payload()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingEvents();

        Mockito.verify(kafkaTemplate, Mockito.times(1)).send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
        Mockito.verify(orderEventOutboxService).markRetryableFailure(Mockito.eq(1L), Mockito.contains("Topic not present"));
        Mockito.verify(orderEventOutboxService).releaseClaims(List.of(2L, 3L));
        Mockito.verify(orderEventOutboxService, Mockito.times(1)).claimNextBatch();

        clock.advance(PUBLISH_INTERVAL.minusMillis(1));
        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService, Mockito.times(1)).claimNextBatch();

        clock.advance(Duration.ofMillis(1));
        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService, Mockito.times(2)).claimNextBatch();
        Mockito.verify(orderEventOutboxService).markPublished(List.of(1L, 2L));

        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService, Mockito.times(3)).claimNextBatch();
    }


    @Test
    void publishPendingEvents_whenSendThrows_shouldStopBatchWithoutSendingRemainingEvents() {
        OutboxEventEntity first = event(1L, "1");
        OutboxEventEntity second = event(2L, "2");
        OutboxEventPublisher publisher = newPublisher(10, Duration.ofSeconds(5));
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(first, second));
        Mockito.when(kafkaTemplate.send(first.topic(), first.eventKey(), first.payload()))
                .thenThrow(new IllegalStateException("Send failed"));

        publisher.publishPendingEvents();

        Mockito.verify(kafkaTemplate, Mockito.never()).send(second.topic(), second.eventKey(), second.payload());
        Mockito.verify(orderEventOutboxService).markRetryableFailure(Mockito.eq(1L), Mockito.contains("Send failed"));
        Mockito.verify(orderEventOutboxService).releaseClaims(List.of(2L));
        Mockito.verify(orderEventOutboxService, Mockito.never()).markPublished(Mockito.anyList());
    }


    @Test
    void publishPendingEvents_whenRunsKeepFailing_shouldDoubleBackoffUpToMax() {
        OutboxEventEntity event = event(1L, "1");
        OutboxEventPublisher publisher = new OutboxEventPublisher(
                kafkaTemplate,
                Duration.ofSeconds(5),
                10,
                PUBLISH_INTERVAL,
                Duration.ofSeconds(30),
                Duration.ofMillis(1500),
                orderEventOutboxService,
                metricsService,
                clock
        );
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(event));
        Mockito.when(kafkaTemplate.send(event.topic(), event.eventKey(), event.payload()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")));

        publisher.publishPendingEvents();
        clock.advance(Duration.ofMillis(500));
        publisher.publishPendingEvents();
        clock.advance(Duration.ofMillis(999));
        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService, Mockito.times(2)).claimNextBatch();

        clock.advance(Duration.ofMillis(1));
        publisher.publishPendingEvents();
        clock.advance(Duration.ofMillis(1499));
        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService, Mockito.times(3)).claimNextBatch();

        clock.advance(Duration.ofMillis(1));
        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService, Mockito.times(4)).claimNextBatch();
    }


    @Test
    @Timeout(10)
    void publishPendingEvents_whenAcknowledgementTimesOut_shouldMarkEventRetryable() {
        OutboxEventEntity event = event(1L, "1");
        OutboxEventPublisher publisher = newPublisher(10, Duration.ofMillis(200));
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(event));
        Mockito.when(kafkaTemplate.send(event.topic(), event.eventKey(), event.payload()))
                .thenReturn(new CompletableFuture<>());

        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService).markRetryableFailure(Mockito.eq(1L), Mockito.contains("did not acknowledge"));
        Mockito.verify(metricsService).recordOutboxPublishResult(event.topic(), false);
        Mockito.verify(orderEventOutboxService, Mockito.never()).markPublished(Mockito.anyList());
    }


    @Test
    @Timeout(10)
    void publishPendingEvents_whenInterruptedWhileWaiting_shouldKeepUnconfirmedEventsClaimed() {
        OutboxEventEntity acknowledged = event(1L, "1");
        OutboxEventEntity unconfirmed = event(2L, "2");
        OutboxEventEntity laterWithSameKey = event(3L, "2");
        OutboxEventPublisher publisher = newPublisher(3, Duration.ofSeconds(5));
        Mockito.when(orderEventOutboxService.claimNextBatch())
                .thenReturn(List.of(acknowledged, unconfirmed, laterWithSameKey), List.of());
        Mockito.when(kafkaTemplate.send(acknowledged.topic(), acknowledged.eventKey(), acknowledged.payload()))
                .thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(kafkaTemplate.send(unconfirmed.topic(), unconfirmed.eventKey(), unconfirmed.payload()))
                .thenAnswer(invocation -> {
                    Thread.currentThread().interrupt();
                    return new CompletableFuture<>();
                });

        boolean interruptedAfterRun;
        try {
            publisher.publishPendingEvents();
        } finally {
            interruptedAfterRun = Thread.interrupted();
        }

        Assertions.assertTrue(interruptedAfterRun);
        Mockito.verify(kafkaTemplate, Mockito.never())
                .send(laterWithSameKey.topic(), laterWithSameKey.eventKey(), laterWithSameKey.payload());
        Mockito.verify(orderEventOutboxService, Mockito.times(1)).claimNextBatch();
        Mockito.verify(orderEventOutboxService).markPublished(List.of(1L));
        Mockito.verify(orderEventOutboxService, Mockito.never()).markRetryableFailure(Mockito.anyLong(), Mockito.anyString());
        Mockito.verify(orderEventOutboxService, Mockito.never()).releaseClaims(Mockito.anyList());
    }


    @Test
    @Timeout(10)
    void publishPendingEvents_whenInterruptedBetweenBatches_shouldStopDraining() {
        OutboxEventPublisher publisher = newPublisher(1, Duration.ofSeconds(5));
        Mockito.when(orderEventOutboxService.claimNextBatch())
                .thenReturn(List.of(event(1L, "1")), List.of(event(2L, "2")), List.of());
        Mockito.when(kafkaTemplate.send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        Mockito.doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(orderEventOutboxService).markPublished(List.of(1L));

        boolean interruptedAfterRun;
        try {
            publisher.publishPendingEvents();
        } finally {
            interruptedAfterRun = Thread.interrupted();
        }

        Assertions.assertTrue(interruptedAfterRun);
        Mockito.verify(orderEventOutboxService, Mockito.times(1)).claimNextBatch();
        Mockito.verify(kafkaTemplate, Mockito.times(1)).send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
    }


    private OutboxEventPublisher newPublisher(int batchSize, Duration publishTimeout) {
        return new OutboxEventPublisher(
                kafkaTemplate,
                publishTimeout,
                batchSize,
                PUBLISH_INTERVAL,
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                orderEventOutboxService,
                metricsService,
                clock
        );
    }


    private OutboxEventEntity event(Long id, String eventKey) {
        LocalDateTime now = LocalDateTime.of(2026, 4, 4, 12, 0);
        return new OutboxEventEntity(
                id,
                "ORDER",
                Long.valueOf(eventKey.replaceAll("\\D", "")),
                "evt-" + id,
                "order-events",
                eventKey,
                "order.created",
                "{\"event\":" + id + "}",
                "PENDING",
                0,
                null,
                now,
                now,
                null
        );
    }


    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-04-04T12:00:00Z");


        void advance(Duration duration) {
            now = now.plus(duration);
        }


        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }


        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }


        @Override
        public Instant instant() {
            return now;
        }
    }
}
