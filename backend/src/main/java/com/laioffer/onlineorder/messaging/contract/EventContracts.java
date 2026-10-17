package com.laioffer.onlineorder.messaging.contract;


import com.laioffer.onlineorder.messaging.EventEnvelope;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Where events meet their published contracts (README "Event contracts"):
 * <ul>
 *     <li>{@link #serialize}: the producer side. The outbox serializes every event with it before it stores the row,
 *     in the producing transaction. The event must match the exact schema version it declares; anything else is a bug,
 *     so it throws {@link EventSchemaViolationException} (the transaction rolls back), logs an error and counts
 *     {@code onlineorder.events.schema_violations{side="producer"}}.</li>
 *     <li>{@link #read}: the consumer side. Every listener reads its records with it. A record that is not JSON, breaks
 *     its schema or cannot be mapped throws {@link EventSchemaViolationException}, which the listener error handler
 *     dead-letters without retrying ({@code side="consumer"}).</li>
 * </ul>
 */
@Component
public final class EventContracts {

    static final String PRODUCER = "producer";
    static final String CONSUMER = "consumer";

    private static final Logger LOGGER = LoggerFactory.getLogger(EventContracts.class);

    private final ObjectMapper objectMapper;
    private final EventSchemaRegistry registry;
    private final ApplicationMetricsService metricsService;
    private final Map<String, EventTopic> topicsByName;


    @Autowired
    public EventContracts(
            ObjectMapper objectMapper,
            ApplicationMetricsService metricsService,
            @Value("${app.kafka.order-topic:order-events}") String orderTopic,
            @Value("${app.fulfilment.delivery-topic:delivery-events}") String deliveryTopic,
            @Value("${app.fulfilment.merchant-topic:merchant-events}") String merchantTopic
    ) {
        this(
                objectMapper,
                EventSchemaRegistry.published(),
                metricsService,
                topics(orderTopic, deliveryTopic, merchantTopic)
        );
    }


    /**
     * @param topicsByName the contract topic of each configured Kafka topic name
     */
    public EventContracts(
            ObjectMapper objectMapper,
            EventSchemaRegistry registry,
            ApplicationMetricsService metricsService,
            Map<String, EventTopic> topicsByName
    ) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.metricsService = metricsService;
        this.topicsByName = Map.copyOf(topicsByName);
    }


    /**
     * Serializes an event that is about to be stored for {@code topicName}, after checking it against the schema of
     * the exact event type and version it declares.
     *
     * @return the record value
     * @throws EventSchemaViolationException when the topic has no contracts, the event type or version is not
     *                                       published, the type belongs to another topic, or the event breaks its schema
     */
    public String serialize(String topicName, EventEnvelope envelope) {
        String subject = "Outgoing event " + envelope.eventId() + " (" + envelope.eventType() + " v"
                + envelope.eventVersion() + ") for topic " + topicName;
        EventTopic topic = topicsByName.get(topicName);
        if (topic == null) {
            throw reject(PRODUCER, null, envelope.eventType(), subject,
                    List.of("/: topic " + topicName + " has no event contracts"), null);
        }

        String json;
        JsonNode event;
        try {
            json = objectMapper.writeValueAsString(envelope);
            event = objectMapper.readTree(json);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Unable to serialize event " + envelope.eventId(), ex);
        }
        EventValidation validation = registry.validate(topic, event);
        List<String> violations = new ArrayList<>(validation.violations());
        if (violations.isEmpty() && !validation.exactVersion()) {
            violations.add("/event_version: " + envelope.eventType() + " version " + envelope.eventVersion()
                    + " is not published; add its schema to event-schemas/catalog.json first");
        }
        if (!violations.isEmpty()) {
            throw reject(PRODUCER, topic, validation.eventType(), subject, violations, null);
        }
        return json;
    }


    /**
     * Reads a record of {@code topic} after checking it against its contract (see
     * {@link EventSchemaRegistry#validate(EventTopic, JsonNode)}): an event of a type this instance does not know is
     * only checked against the envelope, so the consumer can skip it.
     *
     * @throws EventSchemaViolationException when the record is not JSON, breaks its contract or cannot be read as
     *                                       {@code type}; it is never worth a retry
     */
    public <T extends EventEnvelope> T read(EventTopic topic, String payload, Class<T> type) {
        String subject = "Record on " + topic.contractName();
        if (payload == null || payload.isBlank()) {
            throw reject(CONSUMER, topic, null, subject, List.of("/: the record has no value"), null);
        }

        JsonNode event;
        try {
            event = objectMapper.readTree(payload);
        } catch (JacksonException ex) {
            throw reject(CONSUMER, topic, null, subject, List.of("/: not valid JSON: " + ex.getOriginalMessage()), ex);
        }
        EventValidation validation = registry.validate(topic, event);
        String described = subject + ": event " + text(event, "event_id") + " (" + validation.eventType() + " v"
                + validation.version() + ")";
        if (!validation.valid()) {
            throw reject(CONSUMER, topic, validation.eventType(), described, validation.violations(), null);
        }

        try {
            // Read from the text, not the tree: the tree holds decimals as doubles, which would lose the scale of
            // amounts such as 19.00.
            return objectMapper.readValue(payload, type);
        } catch (JacksonException ex) {
            throw reject(CONSUMER, topic, validation.eventType(), described,
                    List.of("/: cannot be read as " + type.getSimpleName() + ": " + ex.getOriginalMessage()), ex);
        }
    }


    private EventSchemaViolationException reject(
            String side,
            EventTopic topic,
            String eventType,
            String subject,
            List<String> violations,
            Throwable cause
    ) {
        EventSchemaViolationException violation = new EventSchemaViolationException(subject, violations, cause);
        String eventTypeTag = eventType != null && registry.catalog().eventTypes().containsKey(eventType)
                ? eventType
                : "unknown";
        metricsService.recordEventSchemaViolation(side, topic == null ? null : topic.contractName(), eventTypeTag);
        if (PRODUCER.equals(side)) {
            LOGGER.error("{}", violation.getMessage());
        } else {
            LOGGER.warn("{}; dead-lettering the record without retries", violation.getMessage());
        }
        return violation;
    }


    private static String text(JsonNode event, String field) {
        JsonNode value = event.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }


    private static Map<String, EventTopic> topics(String orderTopic, String deliveryTopic, String merchantTopic) {
        Map<String, EventTopic> topics = new HashMap<>();
        topics.put(orderTopic, EventTopic.ORDER_EVENTS);
        topics.put(deliveryTopic, EventTopic.DELIVERY_EVENTS);
        topics.put(merchantTopic, EventTopic.MERCHANT_EVENTS);
        if (topics.size() != EventTopic.values().length) {
            throw new IllegalStateException("The order, delivery and merchant topics must have different names");
        }
        return topics;
    }
}
