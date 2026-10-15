package com.laioffer.onlineorder.service;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;


/**
 * Persists records from the dead-letter topic.
 *
 * <p>An event envelope is identified by its event id. The first time an event is dead-lettered a row is stored;
 * every later dead letter for the same event (a duplicate delivery, or the event failing again after an operator
 * replayed it) updates that row instead of being dropped, so a replay that fails downstream is visible. Payloads
 * that are not envelopes are deduplicated by content hash through the processed-events table as before.
 */
@Service
public class DeadLetterProcessingService {

    static final String DEAD_LETTER_CONSUMER = "order-dlt-consumer";

    private final DeadLetterEventService deadLetterEventService;
    private final ObjectMapper objectMapper;
    private final ProcessedEventService processedEventService;


    public DeadLetterProcessingService(
            DeadLetterEventService deadLetterEventService,
            ObjectMapper objectMapper,
            ProcessedEventService processedEventService
    ) {
        this.deadLetterEventService = deadLetterEventService;
        this.objectMapper = objectMapper;
        this.processedEventService = processedEventService;
    }


    @Transactional
    public void handle(String sourceTopic, String deadLetterTopic, String messageKey, String payload, String errorMessage) {
        String eventId = eventIdOf(tryReadEnvelope(payload));
        if (eventId != null) {
            if (deadLetterEventService.recordAgainIfKnown(sourceTopic, eventId, errorMessage)) {
                return;
            }
            deadLetterEventService.record(sourceTopic, deadLetterTopic, messageKey, eventId, payload, errorMessage);
            return;
        }

        String dedupKey = "raw:" + sha256(payload);
        if (!processedEventService.markIfUnseen(
                DEAD_LETTER_CONSUMER,
                dedupKey,
                null,
                "dead-letter",
                null,
                null
        )) {
            return;
        }
        deadLetterEventService.record(sourceTopic, deadLetterTopic, messageKey, null, payload, errorMessage);
    }


    private OrderEventEnvelope tryReadEnvelope(String payload) {
        try {
            return objectMapper.readValue(payload, OrderEventEnvelope.class);
        } catch (Exception ignored) {
            return null;
        }
    }


    private String eventIdOf(OrderEventEnvelope envelope) {
        if (envelope == null || envelope.eventId() == null || envelope.eventId().isBlank()) {
            return null;
        }
        return envelope.eventId().trim();
    }


    private String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
