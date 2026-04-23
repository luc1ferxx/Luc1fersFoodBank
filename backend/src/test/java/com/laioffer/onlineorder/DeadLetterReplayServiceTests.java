package com.laioffer.onlineorder;


import com.laioffer.onlineorder.entity.DeadLetterEventEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.model.DeadLetterReplayDto;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.DeadLetterEventRepository;
import com.laioffer.onlineorder.service.DeadLetterReplayService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionOperations;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;


@ExtendWith(MockitoExtension.class)
class DeadLetterReplayServiceTests {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private DeadLetterEventRepository deadLetterEventRepository;

    @Mock
    private ApplicationMetricsService metricsService;


    @Test
    void replay_shouldRepublishPendingDeadLetter() {
        DeadLetterEventEntity event = buildEvent("PENDING", 0, null, null, "order-events");
        DeadLetterEventEntity replayed = buildEvent("REPLAYED", 1, LocalDateTime.of(2026, 4, 4, 12, 5), null, "order-events");
        DeadLetterReplayService service = new DeadLetterReplayService(
                Duration.ofSeconds(5),
                kafkaTemplate,
                deadLetterEventRepository,
                metricsService,
                passthroughTransactions()
        );

        Mockito.when(deadLetterEventRepository.lockById(7L)).thenReturn(event);
        Mockito.when(kafkaTemplate.send("order-events", "order-7", "{\"event_id\":\"evt-7\"}"))
                .thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(deadLetterEventRepository.findById(7L)).thenReturn(Optional.of(replayed));

        DeadLetterReplayDto dto = service.replay(7L);

        Assertions.assertEquals("REPLAYED", dto.replayStatus());
        Assertions.assertEquals(1, dto.replayAttempts());
        Mockito.verify(metricsService).recordDeadLetterReplay(true);
        Mockito.verify(deadLetterEventRepository).markReplayed(
                Mockito.eq(7L),
                Mockito.eq("REPLAYED"),
                Mockito.any(LocalDateTime.class),
                Mockito.any(LocalDateTime.class)
        );
        Mockito.verify(deadLetterEventRepository, Mockito.never()).markReplayFailed(
                Mockito.anyLong(),
                Mockito.anyString(),
                Mockito.any(LocalDateTime.class),
                Mockito.anyString()
        );
    }


    @Test
    void replay_shouldReturnExistingResultWhenAlreadyReplayed() {
        DeadLetterEventEntity event = buildEvent("REPLAYED", 1, LocalDateTime.of(2026, 4, 4, 12, 5), null, "order-events");
        DeadLetterReplayService service = new DeadLetterReplayService(
                Duration.ofSeconds(5),
                kafkaTemplate,
                deadLetterEventRepository,
                metricsService,
                passthroughTransactions()
        );

        Mockito.when(deadLetterEventRepository.lockById(7L)).thenReturn(event);

        DeadLetterReplayDto dto = service.replay(7L);

        Assertions.assertEquals("REPLAYED", dto.replayStatus());
        Mockito.verify(metricsService).recordDeadLetterReplay(true);
        Mockito.verifyNoInteractions(kafkaTemplate);
        Mockito.verify(deadLetterEventRepository, Mockito.never()).markReplayed(
                Mockito.anyLong(),
                Mockito.anyString(),
                Mockito.any(LocalDateTime.class),
                Mockito.any(LocalDateTime.class)
        );
    }


    @Test
    void replay_shouldMarkFailureWhenKafkaSendFails() {
        DeadLetterEventEntity event = buildEvent("PENDING", 0, null, null, "order-events");
        DeadLetterReplayService service = new DeadLetterReplayService(
                Duration.ofSeconds(5),
                kafkaTemplate,
                deadLetterEventRepository,
                metricsService,
                passthroughTransactions()
        );

        Mockito.when(deadLetterEventRepository.lockById(7L)).thenReturn(event);
        Mockito.when(kafkaTemplate.send("order-events", "order-7", "{\"event_id\":\"evt-7\"}"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")));
        Mockito.when(deadLetterEventRepository.existsById(7L)).thenReturn(true);

        IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class, () -> service.replay(7L));

        Assertions.assertTrue(ex.getMessage().contains("Unable to replay dead-letter event"));
        Mockito.verify(metricsService).recordDeadLetterReplay(false);
        Mockito.verify(deadLetterEventRepository).markReplayFailed(
                Mockito.eq(7L),
                Mockito.eq("REPLAY_FAILED"),
                Mockito.any(LocalDateTime.class),
                Mockito.contains("Kafka unavailable")
        );
    }


    @Test
    void replay_shouldRejectDeadLetterWithoutSourceTopic() {
        DeadLetterEventEntity event = buildEvent("PENDING", 0, null, null, null);
        DeadLetterReplayService service = new DeadLetterReplayService(
                Duration.ofSeconds(5),
                kafkaTemplate,
                deadLetterEventRepository,
                metricsService,
                passthroughTransactions()
        );

        Mockito.when(deadLetterEventRepository.lockById(7L)).thenReturn(event);

        Assertions.assertThrows(BadRequestException.class, () -> service.replay(7L));
        Mockito.verifyNoInteractions(kafkaTemplate, metricsService);
    }


    private DeadLetterEventEntity buildEvent(
            String replayStatus,
            int replayAttempts,
            LocalDateTime replayedAt,
            String lastReplayError,
            String sourceTopic
    ) {
        return new DeadLetterEventEntity(
                7L,
                sourceTopic,
                "order-events.dlt",
                "order-7",
                "{\"event_id\":\"evt-7\"}",
                "processing failed",
                replayStatus,
                replayAttempts,
                replayedAt,
                lastReplayError,
                LocalDateTime.of(2026, 4, 4, 12, 0),
                LocalDateTime.of(2026, 4, 4, 12, 0)
        );
    }


    private TransactionOperations passthroughTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }

            @Override
            public void executeWithoutResult(java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action) {
                action.accept(null);
            }
        };
    }
}
