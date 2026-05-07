package com.laioffer.onlineorder.service;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OutboxEventEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventPayload;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.model.OrderHistoryItemDto;
import com.laioffer.onlineorder.model.OutboxEventDto;
import com.laioffer.onlineorder.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;


@Service
public class OrderEventOutboxService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderEventOutboxService.class);

    private static final String OUTBOX_STATUS_PENDING = "PENDING";
    private static final String OUTBOX_STATUS_PROCESSING = "PROCESSING";
    private static final String OUTBOX_STATUS_PUBLISHED = "PUBLISHED";
    private static final String OUTBOX_STATUS_FAILED = "FAILED";

    private final int batchSize;
    private final Duration claimTimeout;
    private final int maxAttempts;
    private final ObjectMapper objectMapper;
    private final String orderTopic;
    private final OutboxEventRepository outboxEventRepository;


    public OrderEventOutboxService(
            @Value("${app.outbox.batch-size:20}") int batchSize,
            @Value("${app.outbox.claim-timeout:30s}") Duration claimTimeout,
            @Value("${app.outbox.max-attempts:10}") int maxAttempts,
            @Value("${app.kafka.order-topic}") String orderTopic,
            ObjectMapper objectMapper,
            OutboxEventRepository outboxEventRepository
    ) {
        this.batchSize = batchSize;
        this.claimTimeout = claimTimeout;
        this.maxAttempts = Math.max(1, maxAttempts);
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
        LocalDateTime now = LocalDateTime.now();
        String abbreviatedError = abbreviate(lastError);
        OutboxEventEntity event = outboxEventRepository.findById(eventId).orElse(null);
        if (event != null && event.attempts() != null && event.attempts() >= maxAttempts) {
            outboxEventRepository.markFailed(
                    eventId,
                    OUTBOX_STATUS_FAILED,
                    now,
                    abbreviatedError
            );
            return;
        }

        outboxEventRepository.markPendingRetry(
                eventId,
                OUTBOX_STATUS_PENDING,
                now,
                abbreviatedError
        );
    }


    public List<OutboxEventDto> getFailedEvents(int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, 200));
        return outboxEventRepository.findByStatusOrderByUpdatedAtAsc(OUTBOX_STATUS_FAILED, normalizedLimit)
                .stream()
                .map(OutboxEventDto::new)
                .toList();
    }


    @Transactional
    public OutboxEventDto retryFailedEvent(Long eventId) {
        OutboxEventEntity event = outboxEventRepository.lockById(eventId);
        if (event == null) {
            throw new ResourceNotFoundException("Outbox event not found");
        }
        if (!OUTBOX_STATUS_FAILED.equals(event.status())) {
            throw new BadRequestException("Only FAILED outbox events can be retried");
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = outboxEventRepository.resetFailedForRetry(
                eventId,
                OUTBOX_STATUS_FAILED,
                OUTBOX_STATUS_PENDING,
                now
        );
        if (updated != 1) {
            throw new ConflictException("Unable to reset outbox event for retry");
        }
        LOGGER.info(
                "Failed outbox event {} reset to PENDING for retry: aggregate_type={}, aggregate_id={}, event_type={}",
                event.id(),
                event.aggregateType(),
                event.aggregateId(),
                event.eventType()
        );
        return outboxEventRepository.findById(eventId)
                .map(OutboxEventDto::new)
                .orElseThrow(() -> new ResourceNotFoundException("Outbox event not found"));
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
