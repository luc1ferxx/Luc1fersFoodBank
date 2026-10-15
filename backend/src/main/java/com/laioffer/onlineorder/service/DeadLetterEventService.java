package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.DeadLetterEventEntity;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.DeadLetterEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


import java.time.LocalDateTime;


@Service
public class DeadLetterEventService {

    static final String REPLAY_STATUS_PENDING = "PENDING";
    static final String REPLAY_STATUS_REPLAYING = "REPLAYING";
    static final String REPLAY_STATUS_REPLAYED = "REPLAYED";
    static final String REPLAY_STATUS_REPLAY_FAILED = "REPLAY_FAILED";
    static final String DEAD_LETTERED_AGAIN_PREFIX = "Dead-lettered again after replay: ";

    private static final Logger LOGGER = LoggerFactory.getLogger(DeadLetterEventService.class);

    private final DeadLetterEventRepository deadLetterEventRepository;
    private final ApplicationMetricsService metricsService;


    public DeadLetterEventService(
            DeadLetterEventRepository deadLetterEventRepository,
            ApplicationMetricsService metricsService
    ) {
        this.deadLetterEventRepository = deadLetterEventRepository;
        this.metricsService = metricsService;
    }


    public void record(
            String sourceTopic,
            String deadLetterTopic,
            String messageKey,
            String eventId,
            String payload,
            String errorMessage
    ) {
        LocalDateTime now = LocalDateTime.now();
        deadLetterEventRepository.save(new DeadLetterEventEntity(
                null,
                sourceTopic,
                deadLetterTopic,
                messageKey,
                eventId,
                payload,
                abbreviate(errorMessage),
                REPLAY_STATUS_PENDING,
                0,
                null,
                null,
                now,
                now
        ));
        metricsService.recordDeadLetterStored(sourceTopic);
    }


    /**
     * Updates the dead letter already stored for {@code eventId} with the error of this repeat. Returns false when
     * no dead letter is stored for the event, in which case the caller records a new one.
     */
    public boolean recordAgainIfKnown(String sourceTopic, String eventId, String errorMessage) {
        DeadLetterEventEntity existing = deadLetterEventRepository.findLatestByEventId(eventId);
        if (existing == null) {
            return false;
        }
        String error = abbreviate(errorMessage);
        deadLetterEventRepository.markDeadLetteredAgain(
                existing.id(),
                REPLAY_STATUS_REPLAYED,
                REPLAY_STATUS_REPLAYING,
                REPLAY_STATUS_REPLAY_FAILED,
                error,
                abbreviate(DEAD_LETTERED_AGAIN_PREFIX + (error == null ? "no error message" : error)),
                LocalDateTime.now()
        );
        if (REPLAY_STATUS_REPLAYED.equals(existing.replayStatus()) || REPLAY_STATUS_REPLAYING.equals(existing.replayStatus())) {
            LOGGER.warn(
                    "Dead-letter event {} (event id {}) was dead-lettered again after its replay: {}",
                    existing.id(),
                    eventId,
                    error
            );
        }
        metricsService.recordDeadLetterStored(sourceTopic);
        return true;
    }


    private String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }
}
