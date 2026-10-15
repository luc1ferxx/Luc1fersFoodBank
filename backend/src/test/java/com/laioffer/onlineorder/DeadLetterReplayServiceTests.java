package com.laioffer.onlineorder;


import com.laioffer.onlineorder.entity.DeadLetterEventEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;


@ExtendWith(MockitoExtension.class)
class DeadLetterReplayServiceTests {

    private static final String TOPIC = "order-events";
    private static final String KEY = "order-7";
    private static final String PAYLOAD = "{\"event_id\":\"evt-7\"}";

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private DeadLetterEventRepository deadLetterEventRepository;

    @Mock
    private ApplicationMetricsService metricsService;


    @Test
    void replay_shouldRepublishPendingDeadLetter() {
        DeadLetterEventEntity pending = buildEvent("PENDING", 0, null, null, TOPIC);
        DeadLetterEventEntity claimed = buildEvent("REPLAYING", 1, null, null, TOPIC);
        DeadLetterEventEntity replayed = buildEvent("REPLAYED", 1, LocalDateTime.of(2026, 4, 4, 12, 5), null, TOPIC);
        DeadLetterReplayService service = service(new RecordingTransactions());

        Mockito.when(deadLetterEventRepository.findById(7L))
                .thenReturn(Optional.of(pending), Optional.of(claimed), Optional.of(replayed));
        Mockito.when(deadLetterEventRepository.claimForReplay(
                Mockito.eq(7L),
                Mockito.eq("REPLAYING"),
                Mockito.eq("PENDING"),
                Mockito.eq("REPLAY_FAILED"),
                Mockito.any(LocalDateTime.class)
        )).thenReturn(1);
        Mockito.when(kafkaTemplate.send(TOPIC, KEY, PAYLOAD)).thenReturn(CompletableFuture.completedFuture(null));

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
    void replay_shouldNeverSendToKafkaInsideADatabaseTransaction() {
        DeadLetterEventEntity pending = buildEvent("PENDING", 0, null, null, TOPIC);
        DeadLetterEventEntity claimed = buildEvent("REPLAYING", 1, null, null, TOPIC);
        DeadLetterEventEntity replayed = buildEvent("REPLAYED", 1, LocalDateTime.of(2026, 4, 4, 12, 5), null, TOPIC);
        RecordingTransactions transactions = new RecordingTransactions();
        DeadLetterReplayService service = service(transactions);
        List<Integer> transactionDepthDuringSend = new ArrayList<>();
        List<String> order = new ArrayList<>();

        Mockito.when(deadLetterEventRepository.findById(7L))
                .thenReturn(Optional.of(pending), Optional.of(claimed), Optional.of(replayed));
        Mockito.when(deadLetterEventRepository.claimForReplay(
                Mockito.eq(7L), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.any(LocalDateTime.class)
        )).thenAnswer(invocation -> {
            order.add("claim");
            return 1;
        });
        Mockito.when(kafkaTemplate.send(TOPIC, KEY, PAYLOAD)).thenAnswer(invocation -> {
            order.add("send");
            transactionDepthDuringSend.add(transactions.depth);
            return CompletableFuture.completedFuture(null);
        });
        Mockito.doAnswer(invocation -> {
            order.add("markReplayed");
            return null;
        }).when(deadLetterEventRepository).markReplayed(
                Mockito.eq(7L), Mockito.anyString(), Mockito.any(LocalDateTime.class), Mockito.any(LocalDateTime.class)
        );

        service.replay(7L);

        Assertions.assertEquals(List.of(0), transactionDepthDuringSend, "the Kafka send ran inside a transaction");
        Assertions.assertEquals(List.of("claim", "send", "markReplayed"), order);
        Assertions.assertEquals(2, transactions.started, "claim and mark must be separate transactions");
        Assertions.assertEquals(List.of("claimForReplay"), transactions.callsIn(0));
        Assertions.assertEquals(List.of("markReplayed"), transactions.callsIn(1));
    }


    @Test
    void replay_shouldRecordTheFailureOutsideTheClaimTransactionWhenKafkaSendFails() {
        DeadLetterEventEntity pending = buildEvent("PENDING", 0, null, null, TOPIC);
        DeadLetterEventEntity claimed = buildEvent("REPLAYING", 1, null, null, TOPIC);
        RecordingTransactions transactions = new RecordingTransactions();
        DeadLetterReplayService service = service(transactions);

        Mockito.when(deadLetterEventRepository.findById(7L)).thenReturn(Optional.of(pending), Optional.of(claimed));
        Mockito.when(deadLetterEventRepository.claimForReplay(
                Mockito.eq(7L), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.any(LocalDateTime.class)
        )).thenReturn(1);
        Mockito.when(kafkaTemplate.send(TOPIC, KEY, PAYLOAD))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")));

        IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class, () -> service.replay(7L));

        Assertions.assertTrue(ex.getMessage().contains("Unable to replay dead-letter event"));
        Mockito.verify(metricsService).recordDeadLetterReplay(false);
        Mockito.verify(deadLetterEventRepository).markReplayFailed(
                Mockito.eq(7L),
                Mockito.eq("REPLAY_FAILED"),
                Mockito.any(LocalDateTime.class),
                Mockito.contains("Kafka unavailable")
        );
        Mockito.verify(deadLetterEventRepository, Mockito.never()).markReplayed(
                Mockito.anyLong(), Mockito.anyString(), Mockito.any(LocalDateTime.class), Mockito.any(LocalDateTime.class)
        );
        Assertions.assertEquals(2, transactions.started);
        Assertions.assertEquals(List.of("markReplayFailed"), transactions.callsIn(1));
    }


    @Test
    void replay_shouldReturnExistingResultWhenAlreadyReplayed() {
        DeadLetterEventEntity event = buildEvent("REPLAYED", 1, LocalDateTime.of(2026, 4, 4, 12, 5), null, TOPIC);
        DeadLetterReplayService service = service(new RecordingTransactions());

        Mockito.when(deadLetterEventRepository.findById(7L)).thenReturn(Optional.of(event));

        DeadLetterReplayDto dto = service.replay(7L);

        Assertions.assertEquals("REPLAYED", dto.replayStatus());
        Mockito.verify(metricsService).recordDeadLetterReplay(true);
        Mockito.verifyNoInteractions(kafkaTemplate);
        Mockito.verify(deadLetterEventRepository, Mockito.never()).claimForReplay(
                Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.any(LocalDateTime.class)
        );
        Mockito.verify(deadLetterEventRepository, Mockito.never()).markReplayed(
                Mockito.anyLong(), Mockito.anyString(), Mockito.any(LocalDateTime.class), Mockito.any(LocalDateTime.class)
        );
    }


    @Test
    void replay_shouldRejectAReplayThatIsAlreadyInProgress() {
        DeadLetterEventEntity pending = buildEvent("PENDING", 0, null, null, TOPIC);
        DeadLetterEventEntity inFlight = buildEvent("REPLAYING", 1, null, null, TOPIC);
        DeadLetterReplayService service = service(new RecordingTransactions());

        Mockito.when(deadLetterEventRepository.findById(7L)).thenReturn(Optional.of(pending), Optional.of(inFlight));
        Mockito.when(deadLetterEventRepository.claimForReplay(
                Mockito.eq(7L), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.any(LocalDateTime.class)
        )).thenReturn(0);

        Assertions.assertThrows(ConflictException.class, () -> service.replay(7L));

        Mockito.verifyNoInteractions(kafkaTemplate, metricsService);
        Mockito.verify(deadLetterEventRepository, Mockito.never()).markReplayFailed(
                Mockito.anyLong(), Mockito.anyString(), Mockito.any(LocalDateTime.class), Mockito.anyString()
        );
    }


    @Test
    void replay_shouldNotResendWhenAnotherReplayFinishedBetweenTheReadAndTheClaim() {
        DeadLetterEventEntity pending = buildEvent("PENDING", 0, null, null, TOPIC);
        DeadLetterEventEntity replayed = buildEvent("REPLAYED", 1, LocalDateTime.of(2026, 4, 4, 12, 5), null, TOPIC);
        DeadLetterReplayService service = service(new RecordingTransactions());

        Mockito.when(deadLetterEventRepository.findById(7L)).thenReturn(Optional.of(pending), Optional.of(replayed));
        Mockito.when(deadLetterEventRepository.claimForReplay(
                Mockito.eq(7L), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.any(LocalDateTime.class)
        )).thenReturn(0);

        DeadLetterReplayDto dto = service.replay(7L);

        Assertions.assertEquals("REPLAYED", dto.replayStatus());
        Mockito.verifyNoInteractions(kafkaTemplate);
    }


    @Test
    void replay_shouldRejectDeadLetterWithoutSourceTopic() {
        DeadLetterEventEntity event = buildEvent("PENDING", 0, null, null, null);
        DeadLetterReplayService service = service(new RecordingTransactions());

        Mockito.when(deadLetterEventRepository.findById(7L)).thenReturn(Optional.of(event));

        Assertions.assertThrows(BadRequestException.class, () -> service.replay(7L));
        Mockito.verifyNoInteractions(kafkaTemplate, metricsService);
        Mockito.verify(deadLetterEventRepository, Mockito.never()).claimForReplay(
                Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.any(LocalDateTime.class)
        );
    }


    @Test
    void replay_shouldReportMissingDeadLetterAsNotFound() {
        DeadLetterReplayService service = service(new RecordingTransactions());

        Mockito.when(deadLetterEventRepository.findById(7L)).thenReturn(Optional.empty());

        Assertions.assertThrows(ResourceNotFoundException.class, () -> service.replay(7L));
        Mockito.verifyNoInteractions(kafkaTemplate, metricsService);
        Mockito.verify(deadLetterEventRepository, Mockito.never()).markReplayFailed(
                Mockito.anyLong(), Mockito.anyString(), Mockito.any(LocalDateTime.class), Mockito.anyString()
        );
    }


    private DeadLetterReplayService service(TransactionOperations transactionOperations) {
        return new DeadLetterReplayService(
                Duration.ofSeconds(5),
                kafkaTemplate,
                deadLetterEventRepository,
                metricsService,
                transactionOperations
        );
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
                KEY,
                PAYLOAD,
                "processing failed",
                replayStatus,
                replayAttempts,
                replayedAt,
                lastReplayError,
                LocalDateTime.of(2026, 4, 4, 12, 0),
                LocalDateTime.of(2026, 4, 4, 12, 0)
        );
    }


    /**
     * Stands in for the TransactionTemplate: runs the callback inline while tracking how many transactions were
     * started, how deeply nested the current call is, and which repository writes each transaction performed.
     */
    private final class RecordingTransactions implements TransactionOperations {

        private int depth;
        private int started;
        private int invocationsBefore;
        private final List<List<String>> callsPerTransaction = new ArrayList<>();


        @Override
        public <T> T execute(TransactionCallback<T> action) {
            begin();
            try {
                return action.doInTransaction(null);
            } finally {
                end();
            }
        }


        @Override
        public void executeWithoutResult(Consumer<TransactionStatus> action) {
            begin();
            try {
                action.accept(null);
            } finally {
                end();
            }
        }


        List<String> callsIn(int transactionIndex) {
            return callsPerTransaction.get(transactionIndex);
        }


        private void begin() {
            if (depth == 0) {
                started++;
                callsPerTransaction.add(new ArrayList<>());
                invocationsBefore = repositoryInvocations().size();
            }
            depth++;
        }


        private void end() {
            depth--;
            if (depth == 0) {
                List<String> calls = callsPerTransaction.get(callsPerTransaction.size() - 1);
                repositoryInvocations().stream()
                        .skip(invocationsBefore)
                        .map(invocation -> invocation.getMethod().getName())
                        .filter(name -> name.startsWith("claim") || name.startsWith("mark"))
                        .forEach(calls::add);
            }
        }


        private List<org.mockito.invocation.Invocation> repositoryInvocations() {
            return new ArrayList<>(Mockito.mockingDetails(deadLetterEventRepository).getInvocations());
        }
    }
}
