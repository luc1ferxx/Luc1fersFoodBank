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
import com.laioffer.onlineorder.messaging.OutboxStatus;
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
     * The schema version of the order events written here: event-schemas/order-events/&lt;event_type&gt;/v2.json, which
     * adds the order's public number (data.order_no) to version 1.
     */
    public static final int ORDER_EVENT_VERSION = 2;

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderEventOutboxService.class);

    private static final String OUTBOX_STATUS_PENDING = OutboxStatus.PENDING.name();
    private static final String OUTBOX_STATUS_PROCESSING = OutboxStatus.PROCESSING.name();
    private static final String OUTBOX_STATUS_PUBLISHED = OutboxStatus.PUBLISHED.name();
    private static final String OUTBOX_STATUS_FAILED = OutboxStatus.FAILED.name();
    private static final String OUTBOX_STATUS_ABANDONED = OutboxStatus.ABANDONED.name();

    private final int batchSize;
    private final Duration claimTimeout;
    private final int maxAttempts;
    private final ObjectMapper objectMapper;
    private final String orderTopic;
    private final OutboxEventRepository outboxEventRepository;
    private final OutboxSequenceAllocator sequenceAllocator;
    private final EventContracts eventContracts;


    public OrderEventOutboxService(
            @Value("${app.outbox.batch-size:200}") int batchSize,
            @Value("${app.outbox.claim-timeout:30s}") Duration claimTimeout,
            @Value("${app.outbox.max-attempts:10}") int maxAttempts,
            @Value("${app.kafka.order-topic}") String orderTopic,
            ObjectMapper objectMapper,
            OutboxEventRepository outboxEventRepository,
            OutboxSequenceAllocator sequenceAllocator,
            EventContracts eventContracts
    ) {
        this.batchSize = batchSize;
        this.claimTimeout = claimTimeout;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.objectMapper = objectMapper;
        this.orderTopic = orderTopic;
        this.outboxEventRepository = outboxEventRepository;
        this.sequenceAllocator = sequenceAllocator;
        this.eventContracts = eventContracts;
    }


    /**
     * @param order      the order after the change, as saved: its order number is set (checkout's save assigns it,
     *                   OrderNumberCallback), and the event carries it
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
                        order.currency(),
                        order.createdAt(),
                        eventItems(items),
                        order.restaurantId(),
                        order.cancelReason(),
                        order.orderNo()
                )
        );
        enqueueEvent(orderTopic, String.valueOf(order.id()), envelope);
    }


    /**
     * Stores an event of any aggregate in the outbox, in the caller's transaction (required), for the publisher to
     * relay to {@code topic}. Every outbox write, order events included, goes through this method:
     * <ol>
     *     <li>the event gets the next sequence of its aggregate ({@link OutboxSequenceAllocator}; the aggregate's
     *     counter row stays locked until the caller's transaction ends, so its events commit in sequence order);</li>
     *     <li>the envelope, with that sequence, is serialized and checked against the published schema of its event
     *     type and version ({@link EventContracts#serialize}); an event that breaks its contract fails the caller's
     *     transaction with {@code EventSchemaViolationException};</li>
     *     <li>the row is inserted with the aggregate, event id, event type and sequence of the envelope.</li>
     * </ol>
     *
     * @param topic    the Kafka topic name (app.kafka.order-topic, app.fulfilment.delivery-topic,
     *                 app.fulfilment.merchant-topic or app.after-sales.topic)
     * @param eventKey the Kafka record key: events with the same key keep their order. Use the order id, so that
     *                 all events about one order land on the same partition of each topic.
     * @param envelope the event without a sequence; serialized to JSON (snake_case) as the record value
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueEvent(String topic, String eventKey, EventEnvelope envelope) {
        long sequence = sequenceAllocator.next(envelope.aggregateType(), envelope.aggregateId());
        EventEnvelope sequenced = envelope.withSequence(sequence);
        String payload = eventContracts.serialize(topic, sequenced);
        LocalDateTime now = LocalDateTime.now();
        outboxEventRepository.save(new OutboxEventEntity(
                null,
                sequenced.aggregateType(),
                sequenced.aggregateId(),
                sequenced.eventId(),
                topic,
                eventKey,
                sequenced.eventType(),
                payload,
                OUTBOX_STATUS_PENDING,
                0,
                null,
                now,
                now,
                null,
                null,
                sequence,
                null
        ));
    }


    /**
     * Claims up to batch-size events that are ready: PENDING, or PROCESSING with an expired claim, and the head of their
     * aggregate, so no earlier event of the same aggregate is still PENDING, PROCESSING or FAILED
     * ({@link OutboxEventRepository#lockNextBatchReadyToPublish}). An aggregate's next event is only claimed once Kafka
     * acknowledged the previous one, whichever instance published it, and a FAILED event holds back the later events of
     * its aggregate until an operator retries ({@link #retryFailedEvent}) or abandons ({@link #abandonFailedEvent}) it.
     *
     * @return the claimed events, each with the claim's token ({@link OutboxEventEntity#claimToken()}), which the
     * result writes ({@link #markPublished}, {@link #releaseClaims}, {@link #releaseFailedClaims},
     * {@link #markRetryableFailure}) need: a write under a token that another claim replaced changes nothing
     */
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
        if (events.isEmpty()) {
            return events;
        }

        String claimToken = UUID.randomUUID().toString();
        outboxEventRepository.markProcessing(eventIds(events), OUTBOX_STATUS_PROCESSING, claimToken, now);
        return events.stream()
                .map(event -> event.withClaimToken(claimToken))
                .toList();
    }


    @Transactional
    public void markPublished(String claimToken, List<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int published = outboxEventRepository.markPublished(
                eventIds,
                claimToken,
                OUTBOX_STATUS_PROCESSING,
                OUTBOX_STATUS_PUBLISHED,
                now,
                now
        );
        if (published < eventIds.size()) {
            // Kafka has them, but the claim that sent them expired and another publisher reclaimed them: it sends them
            // again (consumers drop the duplicates) or already did.
            warnClaimLost("publish", eventIds, published);
        }
    }


    /**
     * Returns claimed events that were never sent to PENDING without counting the claim as an attempt.
     */
    @Transactional
    public void releaseClaims(String claimToken, List<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return;
        }

        int released = outboxEventRepository.releaseClaims(
                eventIds,
                claimToken,
                OUTBOX_STATUS_PROCESSING,
                OUTBOX_STATUS_PENDING,
                LocalDateTime.now()
        );
        if (released < eventIds.size()) {
            warnClaimLost("release", eventIds, released);
        }
    }


    /**
     * Returns claimed events whose send failed for a transient reason, such as a Kafka outage, to PENDING without
     * counting the claim as an attempt, and records the error.
     */
    @Transactional
    public void releaseFailedClaims(String claimToken, List<Long> eventIds, String lastError) {
        if (eventIds.isEmpty()) {
            return;
        }

        int released = outboxEventRepository.releaseFailedClaims(
                eventIds,
                claimToken,
                OUTBOX_STATUS_PROCESSING,
                OUTBOX_STATUS_PENDING,
                LocalDateTime.now(),
                abbreviate(lastError)
        );
        if (released < eventIds.size()) {
            warnClaimLost("release after a failed send", eventIds, released);
        }
    }


    /**
     * Records that Kafka rejected a claimed event: back to PENDING while it has attempts left, FAILED once it used up
     * app.outbox.max-attempts. The attempts are compared in the write itself, under the claim's token.
     *
     * @return what the write did
     */
    @Transactional
    public RetryableFailure markRetryableFailure(String claimToken, Long eventId, String lastError) {
        LocalDateTime now = LocalDateTime.now();
        String abbreviatedError = abbreviate(lastError);
        int retried = outboxEventRepository.markPendingRetry(
                eventId,
                claimToken,
                OUTBOX_STATUS_PROCESSING,
                OUTBOX_STATUS_PENDING,
                maxAttempts,
                now,
                abbreviatedError
        );
        if (retried == 1) {
            return RetryableFailure.RETRY_PENDING;
        }
        int failed = outboxEventRepository.markFailed(
                eventId,
                claimToken,
                OUTBOX_STATUS_PROCESSING,
                OUTBOX_STATUS_FAILED,
                maxAttempts,
                now,
                abbreviatedError
        );
        if (failed == 1) {
            LOGGER.warn(
                    "Outbox event {} FAILED after {} attempts; it holds back the later events of its aggregate "
                            + "until it is retried or abandoned: {}",
                    eventId,
                    maxAttempts,
                    abbreviatedError
            );
            return RetryableFailure.FAILED;
        }
        warnClaimLost("failure", List.of(eventId), 0);
        return RetryableFailure.CLAIM_LOST;
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


    /**
     * Gives up on a FAILED event that can never be published (for example a record the broker will always reject), so
     * that the later events of its aggregate, which the head-of-line claim holds back behind it, are published. The
     * event becomes ABANDONED: never published, never retried, never deleted by the cleanup, with its last_error kept.
     * Its consumers never receive it, so whatever it would have caused there does not happen; the operator reconciles
     * that by hand (docs/operations.md section 8). Prefer {@link #retryFailedEvent} whenever the cause can be fixed.
     *
     * @return the abandoned event
     */
    @Transactional
    public OutboxEventDto abandonFailedEvent(Long eventId) {
        OutboxEventEntity event = outboxEventRepository.lockById(eventId);
        if (event == null) {
            throw new ResourceNotFoundException("Outbox event not found");
        }
        if (!OUTBOX_STATUS_FAILED.equals(event.status())) {
            throw new BadRequestException("Only FAILED outbox events can be abandoned");
        }

        int updated = outboxEventRepository.markFailedAbandoned(
                eventId,
                OUTBOX_STATUS_FAILED,
                OUTBOX_STATUS_ABANDONED,
                LocalDateTime.now()
        );
        if (updated != 1) {
            throw new ConflictException("Unable to abandon outbox event");
        }
        LOGGER.warn(
                "Failed outbox event {} abandoned; it will never be published: aggregate_type={}, aggregate_id={}, "
                        + "sequence={}, event_type={}, last_error={}",
                event.id(),
                event.aggregateType(),
                event.aggregateId(),
                event.aggregateSequence(),
                event.eventType(),
                event.lastError()
        );
        return outboxEventRepository.findById(eventId)
                .map(OutboxEventDto::new)
                .orElseThrow(() -> new ResourceNotFoundException("Outbox event not found"));
    }


    /**
     * @return whether one of the given published events has a PENDING successor in its aggregate that the head-of-line
     * claim can return now
     */
    public boolean hasPendingSuccessor(List<Long> publishedIds) {
        if (publishedIds.isEmpty()) {
            return false;
        }
        return outboxEventRepository.countWithPendingSuccessor(publishedIds) > 0;
    }


    public OrderEventEnvelope readEnvelope(String payload) {
        try {
            return objectMapper.readValue(payload, OrderEventEnvelope.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Unable to deserialize order event payload", ex);
        }
    }


    private static void warnClaimLost(String write, List<Long> eventIds, int changed) {
        LOGGER.warn(
                "Outbox {} changed {} of the events {}; the others were no longer held by this publisher's claim: it "
                        + "expired and another publisher took them over (app.outbox.claim-timeout)",
                write,
                changed,
                eventIds
        );
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


    /**
     * What {@link #markRetryableFailure} did with a rejected event.
     */
    public enum RetryableFailure {
        /**
         * Back to PENDING: it has attempts left.
         */
        RETRY_PENDING,
        /**
         * FAILED: it used up its attempts and holds back its aggregate.
         */
        FAILED,
        /**
         * Nothing: the claim expired and another publisher holds the event, or published it.
         */
        CLAIM_LOST
    }
}
