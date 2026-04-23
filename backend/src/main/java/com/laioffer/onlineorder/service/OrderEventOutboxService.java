package com.laioffer.onlineorder.service;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OutboxEventEntity;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventPayload;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.model.OrderHistoryItemDto;
import com.laioffer.onlineorder.repository.OutboxEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;


@Service
public class OrderEventOutboxService {

    private static final String OUTBOX_STATUS_PENDING = "PENDING";
    private static final String OUTBOX_STATUS_PROCESSING = "PROCESSING";
    private static final String OUTBOX_STATUS_PUBLISHED = "PUBLISHED";

    private final int batchSize;
    private final Duration claimTimeout;
    private final ObjectMapper objectMapper;
    private final String orderTopic;
    private final OutboxEventRepository outboxEventRepository;


    public OrderEventOutboxService(
            @Value("${app.outbox.batch-size:20}") int batchSize,
            @Value("${app.outbox.claim-timeout:30s}") Duration claimTimeout,
            @Value("${app.kafka.order-topic}") String orderTopic,
            ObjectMapper objectMapper,
            OutboxEventRepository outboxEventRepository
    ) {
        this.batchSize = batchSize;
        this.claimTimeout = claimTimeout;
        this.objectMapper = objectMapper;
        this.orderTopic = orderTopic;
        this.outboxEventRepository = outboxEventRepository;
    }


    public void enqueueOrderEvent(OrderEntity order, List<OrderHistoryItemDto> items) {
        enqueueOrderEvent(order, items, null);
    }


    public void enqueueOrderEvent(OrderEntity order, List<OrderHistoryItemDto> items, String idempotencyKey) {
        String eventType = resolveEventType(order.status());
        LocalDateTime occurredAt = order.createdAt() == null ? LocalDateTime.now() : order.createdAt();
        String eventId = UUID.randomUUID().toString();
        String normalizedIdempotencyKey = normalizeNullable(idempotencyKey);
        OrderEventEnvelope envelope = new OrderEventEnvelope(
                eventId,
                1,
                eventType,
                "ORDER",
                order.id(),
                occurredAt,
                normalizedIdempotencyKey == null ? eventId : normalizedIdempotencyKey,
                normalizedIdempotencyKey,
                new OrderEventPayload(
                        order.customerId(),
                        order.status(),
                        order.totalPrice(),
                        occurredAt,
                        items
                )
        );
        LocalDateTime now = LocalDateTime.now();
        outboxEventRepository.save(new OutboxEventEntity(
                null,
                "ORDER",
                order.id(),
                eventId,
                orderTopic,
                String.valueOf(order.id()),
                eventType,
                serializePayload(envelope),
                OUTBOX_STATUS_PENDING,
                0,
                null,
                now,
                now,
                null
        ));
    }


    @Transactional
    public List<OutboxEventEntity> claimNextBatch() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minus(claimTimeout);
        List<OutboxEventEntity> events = outboxEventRepository.lockNextBatchReadyToPublish(
                OUTBOX_STATUS_PENDING,
                OUTBOX_STATUS_PROCESSING,
                staleBefore,
                batchSize
        );

        for (OutboxEventEntity event : events) {
            outboxEventRepository.markProcessing(event.id(), OUTBOX_STATUS_PROCESSING, now);
        }
        return events;
    }


    @Transactional
    public void markPublished(Long eventId) {
        LocalDateTime now = LocalDateTime.now();
        outboxEventRepository.markPublished(eventId, OUTBOX_STATUS_PUBLISHED, now, now);
    }


    @Transactional
    public void markRetryableFailure(Long eventId, String lastError) {
        outboxEventRepository.markPendingRetry(
                eventId,
                OUTBOX_STATUS_PENDING,
                LocalDateTime.now(),
                abbreviate(lastError)
        );
    }


    public OrderEventEnvelope readEnvelope(String payload) {
        try {
            return objectMapper.readValue(payload, OrderEventEnvelope.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to deserialize order event payload", ex);
        }
    }


    private String resolveEventType(String status) {
        return OrderStatus.normalize(status).eventType();
    }


    private String serializePayload(OrderEventEnvelope payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize order event payload", ex);
        }
    }


    private String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }


    private String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "Unknown publish error";
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }
}
