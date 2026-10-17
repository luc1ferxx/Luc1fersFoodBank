package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OutboxEventEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.messaging.EventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventItem;
import com.laioffer.onlineorder.messaging.OrderEventPayload;
import com.laioffer.onlineorder.messaging.contract.EventContracts;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.model.OrderHistoryItemDto;
import com.laioffer.onlineorder.model.OutboxEventDto;
import com.laioffer.onlineorder.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;


@Service
public class OrderEventOutboxService {

    /**
     * The schema version of the order events written here: event-schemas/order-events/&lt;event_type&gt;/v1.json.
     */
    public static final int ORDER_EVENT_VERSION = 1;

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
    private final EventContracts eventContracts;


    public OrderEventOutboxService(
            @Value("${app.outbox.batch-size:200}") int batchSize,
            @Value("${app.outbox.claim-timeout:30s}") Duration claimTimeout,
            @Value("${app.outbox.max-attempts:10}") int maxAttempts,
            @Value("${app.kafka.order-topic}") String orderTopic,
            ObjectMapper objectMapper,
            OutboxEventRepository outboxEventRepository,
            EventContracts eventContracts
    ) {
        this.batchSize = batchSize;
        this.claimTimeout = claimTimeout;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.objectMapper = objectMapper;
        this.orderTopic = orderTopic;
        this.outboxEventRepository = outboxEventRepository;
        this.eventContracts = eventContracts;
    }


    /**
     * @param occurredAt when the change this event describes happened: the order creation time for a
     *                   new order, or the transition time for a status change
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueOrderEvent(
            OrderEntity order,
            List<OrderHistoryItemDto> items,
            String idempotencyKey,
            LocalDateTime occurredAt
    ) {
        String eventType = resolveEventType(order.status());
        LocalDateTime eventTime = occurredAt == null ? LocalDateTime.now() : occurredAt;
        String eventId = UUID.randomUUID().toString();
        String normalizedIdempotencyKey = normalizeNullable(idempotencyKey);
        OrderEventEnvelope envelope = new OrderEventEnvelope(
                eventId,
                ORDER_EVENT_VERSION,
                eventType,
                "ORDER",
                order.id(),
                eventTime,
                normalizedIdempotencyKey == null ? eventId : normalizedIdempotencyKey,
                normalizedIdempotencyKey,
                new OrderEventPayload(
                        order.customerId(),
                        order.status(),
                        order.totalPrice(),
                        order.createdAt(),
                        eventItems(items),
                        order.restaurantId(),
                        order.cancelReason()
                )
        );
        enqueueEvent(orderTopic, String.valueOf(order.id()), envelope);
    }


    /**
     * Stores an event of any aggregate in the outbox, in the caller's transaction (required), for the publisher to
     * relay to {@code topic}. Every outbox write, order events included, goes through this method:
     * <ol>
     *     <li>the envelope is serialized and checked against the published schema of its event type and version
     *     ({@link EventContracts#serialize}); an event that breaks its contract fails the caller's transaction with
     *     {@code EventSchemaViolationException};</li>
     *     <li>the row is inserted with the aggregate, event id and event type of the envelope.</li>
     * </ol>
     *
     * @param topic    the Kafka topic name (app.kafka.order-topic, app.fulfilment.delivery-topic or
     *                 app.fulfilment.merchant-topic)
     * @param eventKey the Kafka record key: events with the same key keep their order. Use the order id, so that
     *                 all events about one order land on the same partition of each topic.
     * @param envelope the event; serialized to JSON (snake_case) as the record value
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueEvent(String topic, String eventKey, EventEnvelope envelope) {
        String payload = eventContracts.serialize(topic, envelope);
        LocalDateTime now = LocalDateTime.now();
        outboxEventRepository.save(new OutboxEventEntity(
                null,
                envelope.aggregateType(),
                envelope.aggregateId(),
                envelope.eventId(),
                topic,
                eventKey,
                envelope.eventType(),
                payload,
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

        if (!events.isEmpty()) {
            outboxEventRepository.markProcessing(eventIds(events), OUTBOX_STATUS_PROCESSING, now);
        }
        return events;
    }


    @Transactional
    public void markPublished(List<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        outboxEventRepository.markPublished(eventIds, OUTBOX_STATUS_PUBLISHED, now, now);
    }


    /**
     * Returns claimed events that were never sent to PENDING without counting the claim as an attempt.
     */
    @Transactional
    public void releaseClaims(List<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return;
        }

        outboxEventRepository.releaseClaims(
                eventIds,
                OUTBOX_STATUS_PROCESSING,
                OUTBOX_STATUS_PENDING,
                LocalDateTime.now()
        );
    }


    /**
     * Returns claimed events whose send failed for a transient reason, such as a Kafka outage, to PENDING without
     * counting the claim as an attempt, and records the error.
     */
    @Transactional
    public void releaseFailedClaims(List<Long> eventIds, String lastError) {
        if (eventIds.isEmpty()) {
            return;
        }

        outboxEventRepository.releaseFailedClaims(
                eventIds,
                OUTBOX_STATUS_PROCESSING,
                OUTBOX_STATUS_PENDING,
                LocalDateTime.now(),
                abbreviate(lastError)
        );
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
        } catch (JacksonException ex) {
            throw new IllegalStateException("Unable to deserialize order event payload", ex);
        }
    }


    private List<Long> eventIds(List<OutboxEventEntity> events) {
        return events.stream()
                .map(OutboxEventEntity::id)
                .toList();
    }


    private String resolveEventType(String status) {
        return OrderStatus.normalize(status).eventType();
    }


    /**
     * The order lines as the event contract has them; the REST DTO is only the input of this mapping.
     */
    private static List<OrderEventItem> eventItems(List<OrderHistoryItemDto> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .map(item -> new OrderEventItem(
                        item.id(),
                        item.menuItemId(),
                        item.restaurantId(),
                        item.price(),
                        null,
                        item.quantity(),
                        item.menuItemName(),
                        item.menuItemDescription(),
                        item.menuItemImageUrl()
                ))
                .toList();
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
