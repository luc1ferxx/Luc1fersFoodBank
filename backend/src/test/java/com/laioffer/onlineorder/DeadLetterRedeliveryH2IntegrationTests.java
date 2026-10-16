package com.laioffer.onlineorder;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.audit.AuditActor;
import com.laioffer.onlineorder.audit.AuditLogService;
import com.laioffer.onlineorder.entity.DeadLetterEventEntity;
import com.laioffer.onlineorder.model.DeadLetterReplayDto;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.DeadLetterEventRepository;
import com.laioffer.onlineorder.service.DeadLetterProcessingService;
import com.laioffer.onlineorder.service.DeadLetterReplayService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;


import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;


/**
 * The same event envelope dead-lettered twice: once before and once after an operator replayed it.
 */
@SpringBootTest
@ActiveProfiles("h2")
class DeadLetterRedeliveryH2IntegrationTests {

    private static final String TOPIC = "order-events";
    private static final String DLT = "order-events.dlt";
    private static final String KEY = "42";
    private static final String ENVELOPE = """
            {"event_id":"evt-redelivered","event_version":1,"event_type":"order.paid","aggregate_type":"ORDER","aggregate_id":42}
            """;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.outbox.publisher-enabled", () -> "false");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }

    @Autowired
    private DeadLetterProcessingService deadLetterProcessingService;

    @Autowired
    private DeadLetterEventRepository deadLetterEventRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private ObjectMapper objectMapper;

    private DeadLetterReplayService replayService;


    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate.update("DELETE FROM dead_letter_events");
        jdbcTemplate.update("DELETE FROM processed_events");
        jdbcTemplate.update("DELETE FROM audit_log WHERE target_type = 'DEAD_LETTER_EVENT'");
        KafkaTemplate<String, String> kafkaTemplate = Mockito.mock(KafkaTemplate.class);
        Mockito.when(kafkaTemplate.send(Mockito.eq(TOPIC), Mockito.eq(KEY), Mockito.anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        replayService = new DeadLetterReplayService(
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                kafkaTemplate,
                deadLetterEventRepository,
                Mockito.mock(ApplicationMetricsService.class),
                new TransactionTemplate(transactionManager),
                auditLogService
        );
    }


    @Test
    void handle_whenAReplayedEventIsDeadLetteredAgain_shouldFailTheStoredRowWithTheNewError() {
        deadLetterProcessingService.handle(TOPIC, DLT, KEY, ENVELOPE, "consumer failed: boom 1");

        DeadLetterEventEntity stored = onlyRow();
        Assertions.assertEquals("evt-redelivered", stored.eventId());
        Assertions.assertEquals("PENDING", stored.replayStatus());
        Assertions.assertEquals("consumer failed: boom 1", stored.errorMessage());

        DeadLetterReplayDto replayed = replayService.replay(stored.id());
        Assertions.assertEquals("REPLAYED", replayed.replayStatus());
        Assertions.assertEquals(1, replayed.replayAttempts());

        // The consumer fails on the replayed event, so the recoverer dead-letters the same envelope again.
        deadLetterProcessingService.handle(TOPIC, DLT, KEY, ENVELOPE, "consumer failed: boom 2");

        DeadLetterEventEntity failedAgain = onlyRow();
        Assertions.assertEquals(stored.id(), failedAgain.id());
        Assertions.assertEquals("REPLAY_FAILED", failedAgain.replayStatus());
        Assertions.assertEquals("consumer failed: boom 2", failedAgain.errorMessage());
        Assertions.assertEquals(1, failedAgain.replayAttempts());
        Assertions.assertNotNull(failedAgain.replayedAt());
        Assertions.assertTrue(failedAgain.lastReplayError().contains("boom 2"));
        Assertions.assertTrue(failedAgain.updatedAt().isAfter(replayed.updatedAt()));

        // The row is claimable again, so the operator can replay it after fixing the consumer.
        DeadLetterReplayDto replayedAgain = replayService.replay(stored.id());
        Assertions.assertEquals("REPLAYED", replayedAgain.replayStatus());
        Assertions.assertEquals(2, replayedAgain.replayAttempts());
        Assertions.assertNull(replayedAgain.lastReplayError());
        Assertions.assertEquals(1, deadLetterEventRepository.count());
    }


    @Test
    void handle_whenTheOriginalDeadLetterIsDeliveredTwice_shouldKeepOnePendingRow() {
        deadLetterProcessingService.handle(TOPIC, DLT, KEY, ENVELOPE, "consumer failed: boom 1");
        deadLetterProcessingService.handle(TOPIC, DLT, KEY, ENVELOPE, "consumer failed: boom 1 (redelivered)");

        DeadLetterEventEntity stored = onlyRow();
        Assertions.assertEquals("PENDING", stored.replayStatus());
        Assertions.assertEquals(0, stored.replayAttempts());
        Assertions.assertEquals("consumer failed: boom 1 (redelivered)", stored.errorMessage());
        Assertions.assertNull(stored.lastReplayError());
    }


    @Test
    void handle_whenTheEventIsDeadLetteredAgainWhileItsReplayIsInFlight_shouldWinOverTheReplaysSuccess() {
        deadLetterProcessingService.handle(TOPIC, DLT, KEY, ENVELOPE, "consumer failed: boom 1");
        DeadLetterEventEntity stored = onlyRow();

        // The replay's claim is committed; its send is in flight.
        int claimed = deadLetterEventRepository.claimForReplay(
                stored.id(), "REPLAYING", "PENDING", "REPLAY_FAILED",
                stored.updatedAt().minusSeconds(30), stored.updatedAt().plusSeconds(1)
        );
        Assertions.assertEquals(1, claimed);
        deadLetterProcessingService.handle(TOPIC, DLT, KEY, ENVELOPE, "consumer failed: boom 2");

        DeadLetterEventEntity failedAgain = onlyRow();
        Assertions.assertEquals("REPLAY_FAILED", failedAgain.replayStatus());
        Assertions.assertEquals("consumer failed: boom 2", failedAgain.errorMessage());
        Assertions.assertEquals(1, failedAgain.replayAttempts());

        // The replay's own success mark is fenced on its claim and must not undo that.
        int marked = deadLetterEventRepository.markReplayed(
                stored.id(), "REPLAYED", "REPLAYING", failedAgain.replayAttempts(),
                failedAgain.updatedAt(), failedAgain.updatedAt()
        );
        Assertions.assertEquals(0, marked);
        Assertions.assertEquals("REPLAY_FAILED", onlyRow().replayStatus());
    }


    @Test
    void replay_shouldWriteEachOutcomeToTheAuditLogWithTheOperator() throws Exception {
        deadLetterProcessingService.handle(TOPIC, DLT, KEY, ENVELOPE, "consumer failed: boom 1");
        DeadLetterEventEntity stored = onlyRow();

        replayService.replay(stored.id(), AuditActor.admin(7L));
        replayService.replay(stored.id(), AuditActor.admin(7L));

        List<Map<String, Object>> entries = jdbcTemplate.queryForList("""
                SELECT actor_customer_id, actor_role, action, target_id, details
                FROM audit_log
                WHERE target_type = 'DEAD_LETTER_EVENT'
                ORDER BY id
                """);
        Assertions.assertEquals(2, entries.size());
        for (Map<String, Object> entry : entries) {
            Assertions.assertEquals(7L, ((Number) entry.get("actor_customer_id")).longValue());
            Assertions.assertEquals("ROLE_ADMIN", entry.get("actor_role"));
            Assertions.assertEquals("dead_letter.replay", entry.get("action"));
            Assertions.assertEquals(String.valueOf(stored.id()), entry.get("target_id"));
        }
        JsonNode replayed = objectMapper.readTree((String) entries.get(0).get("details"));
        Assertions.assertEquals("REPLAYED", replayed.get("outcome").asText());
        Assertions.assertEquals(1, replayed.get("attempt").asInt());
        Assertions.assertTrue(replayed.get("recorded").asBoolean());
        Assertions.assertEquals("evt-redelivered", replayed.get("event_id").asText());
        // The second request found the event replayed already and sent nothing.
        JsonNode alreadyReplayed = objectMapper.readTree((String) entries.get(1).get("details"));
        Assertions.assertEquals("ALREADY_REPLAYED", alreadyReplayed.get("outcome").asText());
    }


    private DeadLetterEventEntity onlyRow() {
        List<DeadLetterEventEntity> rows = deadLetterEventRepository.findAll();
        Assertions.assertEquals(1, rows.size(), "expected exactly one dead-letter row");
        return rows.get(0);
    }
}
