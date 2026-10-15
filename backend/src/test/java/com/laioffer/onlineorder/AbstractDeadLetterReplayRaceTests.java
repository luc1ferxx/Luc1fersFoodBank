package com.laioffer.onlineorder;


import com.laioffer.onlineorder.entity.DeadLetterEventEntity;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.model.DeadLetterReplayDto;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.DeadLetterEventRepository;
import com.laioffer.onlineorder.service.DeadLetterReplayService;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;


import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;


/**
 * Drives two replays of the same dead letter against a real database with a Kafka template whose sends complete
 * when the test says so. Subclasses pick the database (H2 in-memory, PostgreSQL in a container); the scenarios are
 * the same.
 */
abstract class AbstractDeadLetterReplayRaceTests {

    private static final String TOPIC = "order-events";
    private static final String KEY = "order-7";
    private static final String PAYLOAD = "{\"event_id\":\"evt-race\"}";

    @Autowired
    private DeadLetterEventRepository deadLetterEventRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private KafkaTemplate<String, String> kafkaTemplate;
    private DeadLetterReplayService service;
    private ExecutorService executor;
    private final List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>();
    private final List<CountDownLatch> sendStarted = new ArrayList<>();


    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate.update("DELETE FROM dead_letter_events");
        kafkaTemplate = Mockito.mock(KafkaTemplate.class);
        service = new DeadLetterReplayService(
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                kafkaTemplate,
                deadLetterEventRepository,
                Mockito.mock(ApplicationMetricsService.class),
                new TransactionTemplate(transactionManager)
        );
        executor = Executors.newFixedThreadPool(2);
        // Each send returns its own future and signals that it started; the test completes the futures.
        Mockito.when(kafkaTemplate.send(TOPIC, KEY, PAYLOAD)).thenAnswer(invocation -> {
            CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
            CountDownLatch started;
            synchronized (sends) {
                sends.add(future);
                started = sendStarted.get(sends.size() - 1);
            }
            started.countDown();
            return future;
        });
        for (int i = 0; i < 3; i++) {
            sendStarted.add(new CountDownLatch(1));
        }
    }


    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }


    @Test
    void replay_whileAnotherReplayIsInFlight_shouldConflictInsteadOfSendingAgain() throws Exception {
        long id = insertPendingDeadLetter();

        Future<DeadLetterReplayDto> first = executor.submit(() -> service.replay(id));
        awaitSend(0);

        // The claim is committed and no transaction is open while the first send is in flight.
        DeadLetterEventEntity inFlight = find(id);
        Assertions.assertEquals("REPLAYING", inFlight.replayStatus());
        Assertions.assertEquals(1, inFlight.replayAttempts());

        Assertions.assertThrows(ConflictException.class, () -> service.replay(id));
        Assertions.assertEquals(1, sendCount(), "the second request must not send while the first is in flight");

        sends.get(0).completeExceptionally(new KafkaException("broker unavailable"));
        ExecutionException firstFailure = Assertions.assertThrows(ExecutionException.class, () -> first.get(10, TimeUnit.SECONDS));
        Assertions.assertInstanceOf(IllegalStateException.class, firstFailure.getCause());

        DeadLetterEventEntity failed = find(id);
        Assertions.assertEquals("REPLAY_FAILED", failed.replayStatus());
        Assertions.assertEquals(1, failed.replayAttempts());
        Assertions.assertTrue(failed.lastReplayError().contains("broker unavailable"));

        // The failed claim is released: the operator's retry claims, sends and completes.
        Future<DeadLetterReplayDto> retry = executor.submit(() -> service.replay(id));
        awaitSend(1);
        sends.get(1).complete(null);
        DeadLetterReplayDto replayed = retry.get(10, TimeUnit.SECONDS);

        Assertions.assertEquals("REPLAYED", replayed.replayStatus());
        Assertions.assertEquals(2, replayed.replayAttempts());
        Assertions.assertNotNull(replayed.replayedAt());
        Assertions.assertNull(replayed.lastReplayError());
        Assertions.assertEquals(2, sendCount());
    }


    @Test
    void replay_whenAStaleClaimFailsAfterALaterReplayCompleted_shouldKeepTheReplayedRow() throws Exception {
        long id = insertPendingDeadLetter();

        // Request A claims the row and its send hangs.
        Future<DeadLetterReplayDto> first = executor.submit(() -> service.replay(id));
        awaitSend(0);

        // A's claim is older than the claim timeout (A never resolved it), so request B may claim the row again.
        expireClaim(id);
        Future<DeadLetterReplayDto> second = executor.submit(() -> service.replay(id));
        awaitSend(1);
        sends.get(1).complete(null);
        DeadLetterReplayDto replayedBySecond = second.get(10, TimeUnit.SECONDS);
        Assertions.assertEquals("REPLAYED", replayedBySecond.replayStatus());
        Assertions.assertEquals(2, replayedBySecond.replayAttempts());

        // Only now does A's send fail: its failure must not overwrite what B recorded.
        sends.get(0).completeExceptionally(new KafkaException("broker unavailable"));
        Assertions.assertThrows(ExecutionException.class, () -> first.get(10, TimeUnit.SECONDS));

        DeadLetterEventEntity finalRow = find(id);
        Assertions.assertEquals("REPLAYED", finalRow.replayStatus());
        Assertions.assertEquals(2, finalRow.replayAttempts());
        Assertions.assertNotNull(finalRow.replayedAt());
        Assertions.assertNull(finalRow.lastReplayError());
    }


    @Test
    void replay_whenAStaleClaimFailsWhileALaterReplayIsStillInFlight_shouldNotTouchTheLaterClaim() throws Exception {
        long id = insertPendingDeadLetter();

        Future<DeadLetterReplayDto> first = executor.submit(() -> service.replay(id));
        awaitSend(0);
        expireClaim(id);

        Future<DeadLetterReplayDto> second = executor.submit(() -> service.replay(id));
        awaitSend(1);
        LocalDateTime secondClaimedAt = find(id).updatedAt();

        // A fails while B's send is still in flight: B's claim must stay intact.
        sends.get(0).completeExceptionally(new KafkaException("broker unavailable"));
        Assertions.assertThrows(ExecutionException.class, () -> first.get(10, TimeUnit.SECONDS));
        DeadLetterEventEntity stillClaimedBySecond = find(id);
        Assertions.assertEquals("REPLAYING", stillClaimedBySecond.replayStatus());
        Assertions.assertEquals(2, stillClaimedBySecond.replayAttempts());
        Assertions.assertEquals(secondClaimedAt, stillClaimedBySecond.updatedAt());
        Assertions.assertNull(stillClaimedBySecond.lastReplayError());

        sends.get(1).complete(null);
        DeadLetterReplayDto replayed = second.get(10, TimeUnit.SECONDS);
        Assertions.assertEquals("REPLAYED", replayed.replayStatus());
        Assertions.assertEquals(2, replayed.replayAttempts());
        Assertions.assertEquals("REPLAYED", find(id).replayStatus());
    }


    private long insertPendingDeadLetter() {
        LocalDateTime now = LocalDateTime.now();
        DeadLetterEventEntity saved = deadLetterEventRepository.save(new DeadLetterEventEntity(
                null,
                TOPIC,
                "order-events.dlt",
                KEY,
                PAYLOAD,
                "processing failed",
                "PENDING",
                0,
                null,
                null,
                now,
                now
        ));
        return saved.id();
    }


    private void expireClaim(long id) {
        jdbcTemplate.update(
                "UPDATE dead_letter_events SET updated_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(5)),
                id
        );
    }


    private void awaitSend(int index) throws InterruptedException {
        Assertions.assertTrue(sendStarted.get(index).await(10, TimeUnit.SECONDS), "send " + index + " did not start");
    }


    private int sendCount() {
        synchronized (sends) {
            return sends.size();
        }
    }


    private DeadLetterEventEntity find(long id) {
        return deadLetterEventRepository.findById(id).orElseThrow();
    }
}
