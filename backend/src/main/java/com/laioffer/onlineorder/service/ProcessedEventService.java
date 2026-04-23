package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.repository.ProcessedEventRepository;
import org.springframework.stereotype.Service;


import java.time.LocalDateTime;


@Service
public class ProcessedEventService {

    private final ProcessedEventRepository processedEventRepository;


    public ProcessedEventService(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }


    public boolean markIfUnseen(String consumerName, OrderEventEnvelope event) {
        if (event == null) {
            throw new IllegalArgumentException("Event envelope is required");
        }
        if (event.eventId() == null || event.eventId().isBlank()) {
            throw new IllegalArgumentException("Event id is required");
        }
        return markIfUnseen(
                consumerName,
                event.eventId(),
                event.eventId(),
                event.eventType(),
                event.aggregateType(),
                event.aggregateId()
        );
    }


    public boolean markIfUnseen(
            String consumerName,
            String dedupKey,
            String eventId,
            String eventType,
            String aggregateType,
            Long aggregateId
    ) {
        if (consumerName == null || consumerName.isBlank()) {
            throw new IllegalArgumentException("Consumer name is required");
        }
        if (dedupKey == null || dedupKey.isBlank()) {
            throw new IllegalArgumentException("Dedup key is required");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("Event type is required");
        }
        return processedEventRepository.insertIfAbsent(
                consumerName.trim(),
                normalizeNullable(eventId),
                dedupKey.trim(),
                eventType.trim(),
                normalizeNullable(aggregateType),
                aggregateId,
                LocalDateTime.now()
        ) > 0;
    }


    private String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
