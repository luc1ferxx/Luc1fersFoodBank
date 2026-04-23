package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.DeadLetterEventEntity;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.DeadLetterEventRepository;
import org.springframework.stereotype.Service;


import java.time.LocalDateTime;


@Service
public class DeadLetterEventService {

    static final String REPLAY_STATUS_PENDING = "PENDING";

    private final DeadLetterEventRepository deadLetterEventRepository;
    private final ApplicationMetricsService metricsService;


    public DeadLetterEventService(
            DeadLetterEventRepository deadLetterEventRepository,
            ApplicationMetricsService metricsService
    ) {
        this.deadLetterEventRepository = deadLetterEventRepository;
        this.metricsService = metricsService;
    }


    public void record(String sourceTopic, String deadLetterTopic, String messageKey, String payload, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        deadLetterEventRepository.save(new DeadLetterEventEntity(
                null,
                sourceTopic,
                deadLetterTopic,
                messageKey,
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


    private String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }
}
