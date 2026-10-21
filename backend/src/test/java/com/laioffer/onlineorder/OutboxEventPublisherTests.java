package com.laioffer.onlineorder;


import com.laioffer.onlineorder.entity.OutboxEventEntity;
import com.laioffer.onlineorder.messaging.OutboxEventPublisher;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaProducerException;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;


@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTests {

    private static final Duration PUBLISH_INTERVAL = Duration.ofMillis(500);
    // The token of the claim the mocked claimNextBatch returns; every result write must pass it on.
    private static final String CLAIM_TOKEN = "claim-1";

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
        ).withClaimToken(CLAIM_TOKEN);
        OutboxEventPublisher publisher = newPublisher(20, Duration.ofSeconds(5));

        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(event));
        Mockito.when(kafkaTemplate.send(event.topic(), event.eventKey(), event.payload()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService).markPublished(CLAIM_TOKEN, List.of(event.id()));
        Mockito.verify(metricsService).recordOutboxPublishResult(event.topic(), true);
        Mockito.verify(orderEventOutboxService, Mockito.never()).markRetryableFailure(Mockito.any(), Mockito.anyLong(), Mockito.anyString());
        Mockito.verify(orderEventOutboxService, Mockito.never()).releaseClaims(Mockito.any(), Mockito.anyList());
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
        ).withClaimToken(CLAIM_TOKEN);
        OutboxEventPublisher publisher = newPublisher(20, Duration.ofSeconds(5));

        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(event));
        Mockito.when(kafkaTemplate.send(event.topic(), event.eventKey(), event.payload()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")));

        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService).markRetryableFailure(Mockito.eq(CLAIM_TOKEN), Mockito.eq(event.id()), Mockito.contains("Kafka unavailable"));
        Mockito.verify(metricsService).recordOutboxPublishResult(event.topic(), false);
        Mockito.verify(orderEventOutboxService, Mockito.never()).markPublished(Mockito.any(), Mockito.anyList());
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
        Mockito.verify(orderEventOutboxService).markPublished(CLAIM_TOKEN, List.of(1L, 2L));
        Mockito.verify(orderEventOutboxService).markPublished(CLAIM_TOKEN, List.of(3L, 4L));
        Mockito.verify(orderEventOutboxService).markPublished(CLAIM_TOKEN, List.of(5L));
        Mockito.verify(metricsService, Mockito.times(5)).recordOutboxPublishResult("order-events", true);
    }


    /**
     * The claim returns only the head of each aggregate, so a partial batch does not mean the backlog is drained: a
     * published head whose successor is PENDING lets the run claim that successor right away.
     */
    @Test
    void publishPendingEvents_whenAPublishedHeadHasAPendingSuccessor_shouldClaimAgainInTheSameRun() {
        List<OutboxEventEntity> heads = List.of(event(1L, "1"), event(2L, "2"));
        List<OutboxEventEntity> successors = List.of(event(3L, "1"));
        OutboxEventPublisher publisher = newPublisher(20, Duration.ofSeconds(5));
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(heads, successors, List.of());
        Mockito.when(orderEventOutboxService.hasPendingSuccessor(List.of(1L, 2L))).thenReturn(true);
        Mockito.when(orderEventOutboxService.hasPendingSuccessor(List.of(3L))).thenReturn(false);
        Mockito.when(kafkaTemplate.send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService, Mockito.times(2)).claimNextBatch();
        Mockito.verify(orderEventOutboxService).markPublished(CLAIM_TOKEN, List.of(1L, 2L));
        Mockito.verify(orderEventOutboxService).markPublished(CLAIM_TOKEN, List.of(3L));
        Mockito.verify(orderEventOutboxService).hasPendingSuccessor(List.of(3L));
    }


    @Test
    void publishPendingEvents_whenAnEventOfTheBatchFailed_shouldEndTheRunWithoutLookingForSuccessors() {
        OutboxEventEntity sent = event(1L, "1");
        OutboxEventEntity rejected = event(2L, "2");
        OutboxEventPublisher publisher = newPublisher(20, Duration.ofSeconds(5));
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(sent, rejected));
        Mockito.when(kafkaTemplate.send(sent.topic(), sent.eventKey(), sent.payload()))
                .thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(kafkaTemplate.send(rejected.topic(), rejected.eventKey(), rejected.payload()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker rejected it")));

        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService).claimNextBatch();
        Mockito.verify(orderEventOutboxService, Mockito.never()).hasPendingSuccessor(Mockito.anyList());
    }


    @Test
    void publishPendingEvents_whenNothingIsPending_shouldNotSend() {
        OutboxEventPublisher publisher = newPublisher(2, Duration.ofSeconds(5));
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of());

        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService).claimNextBatch();
        Mockito.verify(kafkaTemplate, Mockito.never()).send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
        Mockito.verifyNoInteractions(metricsService);
        Mockito.verify(orderEventOutboxService, Mockito.never()).markPublished(Mockito.any(), Mockito.anyList());
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

        Mockito.verify(orderEventOutboxService).markPublished(CLAIM_TOKEN, List.of(1L, 2L, 3L));
        Mockito.verify(orderEventOutboxService, Mockito.never()).markRetryableFailure(Mockito.any(), Mockito.anyLong(), Mockito.anyString());
    }


    @Test
    void publishPendingEvents_whenEventFails_shouldNotSendLaterEventsWithSameKey() {
        OutboxEventEntity first = event(1L, "order-1");
        OutboxEventEntity otherOrder = event(2L, "order-2");
        OutboxEventEntity second = event(3L, "order-1");
        OutboxEventEntity otherOrderSecond = event(4L, "order-2");
        CompletableFuture<SendResult<String, String>> firstSend = new CompletableFuture<>();
        OutboxEventPublisher publisher = newPublisher(10, Duration.ofSeconds(5));
        Mockito.when(orderEventOutboxService.claimNextBatch())
                .thenReturn(List.of(first, otherOrder, second, otherOrderSecond));
        Mockito.when(kafkaTemplate.send(first.topic(), first.eventKey(), first.payload())).thenReturn(firstSend);
        Mockito.when(kafkaTemplate.send(otherOrder.topic(), otherOrder.eventKey(), otherOrder.payload()))
                .thenAnswer(invocation -> {
                    firstSend.completeExceptionally(new IllegalStateException("Record rejected"));
                    return CompletableFuture.completedFuture(null);
                });
        Mockito.when(kafkaTemplate.send(otherOrderSecond.topic(), otherOrderSecond.eventKey(), otherOrderSecond.payload()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingEvents();

        Mockito.verify(kafkaTemplate, Mockito.never()).send(second.topic(), second.eventKey(), second.payload());
        Mockito.verify(orderEventOutboxService).markRetryableFailure(Mockito.eq(CLAIM_TOKEN), Mockito.eq(1L), Mockito.contains("Record rejected"));
        Mockito.verify(orderEventOutboxService).markPublished(CLAIM_TOKEN, List.of(2L, 4L));
        Mockito.verify(orderEventOutboxService).releaseClaims(CLAIM_TOKEN, List.of(3L));
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
        Mockito.verify(orderEventOutboxService).markPublished(CLAIM_TOKEN, List.of(1L, 3L, 2L));
        Mockito.verify(orderEventOutboxService, Mockito.never()).markRetryableFailure(Mockito.any(), Mockito.anyLong(), Mockito.anyString());
    }


    @Test
    void publishPendingEvents_whenBrokerIsDown_shouldReleaseEventsWithoutUsingAttemptsAndBackOff() {
        OutboxEventEntity first = event(1L, "1");
        OutboxEventEntity second = event(2L, "2");
        OutboxEventEntity third = event(3L, "3");
        OutboxEventPublisher publisher = newPublisher(3, Duration.ofSeconds(5));
        Mockito.when(orderEventOutboxService.claimNextBatch())
                .thenReturn(List.of(first, second, third), List.of(first, second), List.of());
        Mockito.when(kafkaTemplate.send(first.topic(), first.eventKey(), first.payload()))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException(
                        "Send failed",
                        new TimeoutException("Topic order-events not present in metadata after 5000 ms.")
                )))
                .thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(kafkaTemplate.send(second.topic(), second.eventKey(), second.payload()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingEvents();

        Mockito.verify(kafkaTemplate, Mockito.times(1)).send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
        Mockito.verify(orderEventOutboxService)
                .releaseFailedClaims(Mockito.eq(CLAIM_TOKEN), Mockito.eq(List.of(1L)), Mockito.contains("not present in metadata"));
        Mockito.verify(orderEventOutboxService).releaseClaims(CLAIM_TOKEN, List.of(2L, 3L));
        Mockito.verify(orderEventOutboxService, Mockito.never()).markRetryableFailure(Mockito.any(), Mockito.anyLong(), Mockito.anyString());
        Mockito.verify(metricsService).recordOutboxPublishResult(first.topic(), false);
        Mockito.verify(orderEventOutboxService, Mockito.times(1)).claimNextBatch();

        clock.advance(PUBLISH_INTERVAL.minusMillis(1));
        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService, Mockito.times(1)).claimNextBatch();

        clock.advance(Duration.ofMillis(1));
        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService, Mockito.times(2)).claimNextBatch();
        Mockito.verify(orderEventOutboxService).markPublished(CLAIM_TOKEN, List.of(1L, 2L));

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
        Mockito.verify(orderEventOutboxService).markRetryableFailure(Mockito.eq(CLAIM_TOKEN), Mockito.eq(1L), Mockito.contains("Send failed"));
        Mockito.verify(orderEventOutboxService).releaseClaims(CLAIM_TOKEN, List.of(2L));
        Mockito.verify(orderEventOutboxService, Mockito.never()).markPublished(Mockito.any(), Mockito.anyList());
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
    void publishPendingEvents_whenRunSucceedsAfterFailures_shouldRestartBackoffAtPublishInterval() {
        OutboxEventEntity event = event(1L, "1");
        OutboxEventPublisher publisher = newPublisher(10, Duration.ofSeconds(5));
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(event));
        Mockito.when(kafkaTemplate.send(event.topic(), event.eventKey(), event.payload()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")))
                .thenReturn(CompletableFuture.completedFuture(null))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingEvents();
        clock.advance(PUBLISH_INTERVAL);
        publisher.publishPendingEvents();
        clock.advance(PUBLISH_INTERVAL.multipliedBy(2));
        publisher.publishPendingEvents();
        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService, Mockito.times(4)).claimNextBatch();

        clock.advance(PUBLISH_INTERVAL.minusMillis(1));
        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService, Mockito.times(4)).claimNextBatch();

        clock.advance(Duration.ofMillis(1));
        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService, Mockito.times(5)).claimNextBatch();
    }


    @Test
    void publishPendingEvents_whenWallClockIsSetBack_shouldNotStretchBackoff() {
        OutboxEventEntity event = event(1L, "1");
        OutboxEventPublisher publisher = newPublisher(10, Duration.ofSeconds(5));
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(event), List.of());
        Mockito.when(kafkaTemplate.send(event.topic(), event.eventKey(), event.payload()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")));

        publisher.publishPendingEvents();
        clock.advance(Duration.ofHours(-1));
        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService, Mockito.times(2)).claimNextBatch();
    }


    @Test
    @Timeout(10)
    void publishPendingEvents_whenAcknowledgementTimesOut_shouldReleaseEventWithoutUsingAttempt() {
        OutboxEventEntity event = event(1L, "1");
        OutboxEventPublisher publisher = newPublisher(10, Duration.ofMillis(200));
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(event));
        Mockito.when(kafkaTemplate.send(event.topic(), event.eventKey(), event.payload()))
                .thenReturn(new CompletableFuture<>());

        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService)
                .releaseFailedClaims(Mockito.eq(CLAIM_TOKEN), Mockito.eq(List.of(1L)), Mockito.contains("did not acknowledge"));
        Mockito.verify(orderEventOutboxService, Mockito.never()).markRetryableFailure(Mockito.any(), Mockito.anyLong(), Mockito.anyString());
        Mockito.verify(metricsService).recordOutboxPublishResult(event.topic(), false);
        Mockito.verify(orderEventOutboxService, Mockito.never()).markPublished(Mockito.any(), Mockito.anyList());
    }


    @Test
    void publishPendingEvents_whenBrokerFailsAcceptedSends_shouldReleaseEventsWithoutUsingAttemptsAndBackOff() {
        OutboxEventEntity first = event(1L, "order-1");
        OutboxEventEntity otherOrder = event(2L, "order-2");
        OutboxEventEntity second = event(3L, "order-1");
        CompletableFuture<SendResult<String, String>> firstSend = new CompletableFuture<>();
        OutboxEventPublisher publisher = newPublisher(10, Duration.ofSeconds(5));
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(first, otherOrder, second));
        Mockito.when(kafkaTemplate.send(first.topic(), first.eventKey(), first.payload())).thenReturn(firstSend);
        Mockito.when(kafkaTemplate.send(otherOrder.topic(), otherOrder.eventKey(), otherOrder.payload()))
                .thenAnswer(invocation -> {
                    firstSend.completeExceptionally(new KafkaProducerException(
                            new ProducerRecord<>(first.topic(), first.eventKey(), first.payload()),
                            "Failed to send",
                            new TimeoutException("Expiring 1 record(s) for order-events-0: 10000 ms has passed")
                    ));
                    return CompletableFuture.completedFuture(null);
                });

        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService)
                .releaseFailedClaims(Mockito.eq(CLAIM_TOKEN), Mockito.eq(List.of(1L)), Mockito.contains("Expiring 1 record(s)"));
        Mockito.verify(orderEventOutboxService).markPublished(CLAIM_TOKEN, List.of(2L));
        Mockito.verify(orderEventOutboxService).releaseClaims(CLAIM_TOKEN, List.of(3L));
        Mockito.verify(orderEventOutboxService, Mockito.never()).markRetryableFailure(Mockito.any(), Mockito.anyLong(), Mockito.anyString());
        Mockito.verify(kafkaTemplate, Mockito.never()).send(second.topic(), second.eventKey(), second.payload());

        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService, Mockito.times(1)).claimNextBatch();
    }


    @Test
    void publishPendingEvents_whenRecordIsRejected_shouldKeepPublishingOtherKeysWithoutBackingOff() {
        OutboxEventEntity tooLarge = event(1L, "order-1");
        OutboxEventEntity laterWithSameKey = event(2L, "order-1");
        OutboxEventEntity otherOrder = event(3L, "order-2");
        OutboxEventEntity thirdOrder = event(4L, "order-3");
        OutboxEventPublisher publisher = newPublisher(10, Duration.ofSeconds(5));
        Mockito.when(orderEventOutboxService.claimNextBatch())
                .thenReturn(List.of(tooLarge, laterWithSameKey, otherOrder, thirdOrder), List.of());
        Mockito.when(kafkaTemplate.send(tooLarge.topic(), tooLarge.eventKey(), tooLarge.payload()))
                .thenThrow(new KafkaException(
                        "Send failed",
                        new RecordTooLargeException("The message is 2000000 bytes when serialized")
                ));
        Mockito.when(kafkaTemplate.send(otherOrder.topic(), otherOrder.eventKey(), otherOrder.payload()))
                .thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(kafkaTemplate.send(thirdOrder.topic(), thirdOrder.eventKey(), thirdOrder.payload()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService).markRetryableFailure(Mockito.eq(CLAIM_TOKEN), Mockito.eq(1L), Mockito.contains("RecordTooLarge"));
        Mockito.verify(kafkaTemplate, Mockito.never())
                .send(laterWithSameKey.topic(), laterWithSameKey.eventKey(), laterWithSameKey.payload());
        Mockito.verify(orderEventOutboxService).releaseClaims(CLAIM_TOKEN, List.of(2L));
        Mockito.verify(orderEventOutboxService).markPublished(CLAIM_TOKEN, List.of(3L, 4L));

        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService, Mockito.times(2)).claimNextBatch();
    }


    @Test
    void publishPendingEvents_whenConsecutiveSendsAreRejected_shouldStopBatchAndBackOff() {
        OutboxEventEntity first = event(1L, "order-1");
        OutboxEventEntity second = event(2L, "order-2");
        OutboxEventEntity third = event(3L, "order-3");
        OutboxEventPublisher publisher = newPublisher(10, Duration.ofSeconds(5));
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(first, second, third));
        Mockito.when(kafkaTemplate.send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenThrow(new KafkaException("Send failed", new RecordTooLargeException("The message is too large")));

        publisher.publishPendingEvents();

        Mockito.verify(kafkaTemplate, Mockito.never()).send(third.topic(), third.eventKey(), third.payload());
        Mockito.verify(orderEventOutboxService).markRetryableFailure(Mockito.eq(CLAIM_TOKEN), Mockito.eq(1L), Mockito.contains("too large"));
        Mockito.verify(orderEventOutboxService).markRetryableFailure(Mockito.eq(CLAIM_TOKEN), Mockito.eq(2L), Mockito.contains("too large"));
        Mockito.verify(orderEventOutboxService).releaseClaims(CLAIM_TOKEN, List.of(3L));

        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService, Mockito.times(1)).claimNextBatch();
    }


    @Test
    @Timeout(10)
    void publishPendingEvents_whenDeadlinePassesWithUnacknowledgedKey_shouldReleaseLaterWavesWithoutSending() {
        OutboxEventEntity unacknowledged = event(1L, "order-1");
        OutboxEventEntity otherOrder = event(2L, "order-2");
        OutboxEventEntity second = event(3L, "order-1");
        OutboxEventEntity otherOrderSecond = event(4L, "order-2");
        OutboxEventPublisher publisher = newPublisher(4, Duration.ofMillis(200));
        Mockito.when(orderEventOutboxService.claimNextBatch())
                .thenReturn(List.of(unacknowledged, otherOrder, second, otherOrderSecond), List.of());
        Mockito.when(kafkaTemplate.send(unacknowledged.topic(), unacknowledged.eventKey(), unacknowledged.payload()))
                .thenReturn(new CompletableFuture<>());
        Mockito.when(kafkaTemplate.send(otherOrder.topic(), otherOrder.eventKey(), otherOrder.payload()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingEvents();

        Mockito.verify(kafkaTemplate, Mockito.never())
                .send(otherOrderSecond.topic(), otherOrderSecond.eventKey(), otherOrderSecond.payload());
        Mockito.verify(orderEventOutboxService)
                .releaseFailedClaims(Mockito.eq(CLAIM_TOKEN), Mockito.eq(List.of(1L)), Mockito.contains("did not acknowledge"));
        Mockito.verify(orderEventOutboxService).markPublished(CLAIM_TOKEN, List.of(2L));
        Mockito.verify(orderEventOutboxService).releaseClaims(CLAIM_TOKEN, List.of(3L, 4L));
        Mockito.verify(orderEventOutboxService, Mockito.never()).markRetryableFailure(Mockito.any(), Mockito.anyLong(), Mockito.anyString());
        Mockito.verify(orderEventOutboxService, Mockito.times(1)).claimNextBatch();
    }


    @Test
    @Timeout(10)
    void publishPendingEvents_whenDeadlinePassesWhileSending_shouldReleaseUnsentEventsAndStopDraining() {
        OutboxEventEntity slowSend = event(1L, "order-1");
        OutboxEventEntity second = event(2L, "order-1");
        OutboxEventPublisher publisher = newPublisher(2, Duration.ofMillis(200));
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(slowSend, second), List.of());
        Mockito.when(kafkaTemplate.send(slowSend.topic(), slowSend.eventKey(), slowSend.payload()))
                .thenAnswer(invocation -> {
                    // A send blocked on metadata or buffer space outlives the batch deadline.
                    Thread.sleep(300);
                    return CompletableFuture.completedFuture(null);
                });

        publisher.publishPendingEvents();

        Mockito.verify(kafkaTemplate, Mockito.never()).send(second.topic(), second.eventKey(), second.payload());
        Mockito.verify(orderEventOutboxService).markPublished(CLAIM_TOKEN, List.of(1L));
        Mockito.verify(orderEventOutboxService).releaseClaims(CLAIM_TOKEN, List.of(2L));
        Mockito.verify(orderEventOutboxService, Mockito.times(1)).claimNextBatch();
    }


    @Test
    @Timeout(10)
    void publishPendingEvents_whenInterruptedWhileWaiting_shouldKeepUnconfirmedEventsClaimedAndReleaseUnsentOnes() {
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
        Mockito.verify(orderEventOutboxService).markPublished(CLAIM_TOKEN, List.of(1L));
        Mockito.verify(orderEventOutboxService, Mockito.never()).markRetryableFailure(Mockito.any(), Mockito.anyLong(), Mockito.anyString());
        Mockito.verify(orderEventOutboxService).releaseClaims(CLAIM_TOKEN, List.of(3L));
    }


    @Test
    @Timeout(10)
    void publishPendingEvents_whenInterruptedWhileSending_shouldReleaseUnsentEventsWithoutUsingAttempts() {
        OutboxEventEntity interruptedSend = event(1L, "1");
        OutboxEventEntity otherOrder = event(2L, "2");
        OutboxEventPublisher publisher = newPublisher(10, Duration.ofSeconds(5));
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(interruptedSend, otherOrder));
        Mockito.when(kafkaTemplate.send(interruptedSend.topic(), interruptedSend.eventKey(), interruptedSend.payload()))
                .thenAnswer(invocation -> {
                    throw new InterruptException("Interrupted while waiting for topic metadata");
                });

        boolean interruptedAfterRun;
        try {
            publisher.publishPendingEvents();
        } finally {
            interruptedAfterRun = Thread.interrupted();
        }

        Assertions.assertTrue(interruptedAfterRun);
        Mockito.verify(kafkaTemplate, Mockito.never()).send(otherOrder.topic(), otherOrder.eventKey(), otherOrder.payload());
        Mockito.verify(orderEventOutboxService).releaseClaims(CLAIM_TOKEN, List.of(1L, 2L));
        Mockito.verify(orderEventOutboxService, Mockito.never()).markRetryableFailure(Mockito.any(), Mockito.anyLong(), Mockito.anyString());
        Mockito.verify(orderEventOutboxService, Mockito.times(1)).claimNextBatch();
    }


    @Test
    void publishPendingEvents_whenRecordingAnOutcomeFails_shouldStillRecordTheOthers() {
        OutboxEventEntity acknowledged = event(1L, "1");
        OutboxEventEntity rejected = event(2L, "2");
        OutboxEventEntity laterWithSameKey = event(3L, "2");
        OutboxEventPublisher publisher = newPublisher(10, Duration.ofSeconds(5));
        Mockito.when(orderEventOutboxService.claimNextBatch())
                .thenReturn(List.of(acknowledged, rejected, laterWithSameKey));
        Mockito.when(kafkaTemplate.send(acknowledged.topic(), acknowledged.eventKey(), acknowledged.payload()))
                .thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(kafkaTemplate.send(rejected.topic(), rejected.eventKey(), rejected.payload()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Record rejected")));
        Mockito.doThrow(new IllegalStateException("Database unavailable"))
                .when(orderEventOutboxService).markPublished(CLAIM_TOKEN, List.of(1L));

        IllegalStateException failure = Assertions.assertThrows(
                IllegalStateException.class,
                publisher::publishPendingEvents
        );

        Assertions.assertEquals("Database unavailable", failure.getMessage());
        Mockito.verify(orderEventOutboxService).markRetryableFailure(Mockito.eq(CLAIM_TOKEN), Mockito.eq(2L), Mockito.contains("Record rejected"));
        Mockito.verify(orderEventOutboxService).releaseClaims(CLAIM_TOKEN, List.of(3L));
    }


    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void constructor_whenClaimTimeoutDoesNotCoverProducerMaxBlock_shouldWarn(CapturedOutput output) {
        Mockito.when(kafkaTemplate.getProducerFactory()).thenReturn(
                new DefaultKafkaProducerFactory<>(Map.of(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000")),
                new DefaultKafkaProducerFactory<>(Map.of(ProducerConfig.MAX_BLOCK_MS_CONFIG, "60000"))
        );

        newPublisher(10, Duration.ofSeconds(15));

        Assertions.assertFalse(output.getAll().contains("app.outbox.claim-timeout"));

        newPublisher(10, Duration.ofSeconds(15));

        Assertions.assertTrue(output.getAll().contains("max.block.ms (PT1M)"));
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
        }).when(orderEventOutboxService).markPublished(CLAIM_TOKEN, List.of(1L));

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
        ).withClaimToken(CLAIM_TOKEN);
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
