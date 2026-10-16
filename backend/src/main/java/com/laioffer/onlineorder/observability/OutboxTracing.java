package com.laioffer.onlineorder.observability;


import com.laioffer.onlineorder.entity.OutboxEventEntity;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.Kind;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;


import java.util.Map;
import java.util.regex.Pattern;


/**
 * Carries the trace of the request that wrote an outbox event across the outbox table.
 *
 * <p>The outbox breaks the call chain: the event is inserted in the request's transaction and sent to Kafka later by
 * the scheduled publisher. {@link #currentTraceParent(Tracer)} captures the W3C {@code traceparent} of the span in
 * scope when the event is inserted, and {@link #startRelay(ObservationRegistry, OutboxEventEntity)} starts the
 * publisher's span for the event as a child of that stored context. The KafkaTemplate send observation opened inside
 * the relay span injects its own {@code traceparent} header into the record, so the consumer's span joins the same
 * trace: HTTP request -> outbox relay -> Kafka send -> Kafka receive.
 */
public final class OutboxTracing {

    public static final String TRACEPARENT = "traceparent";

    static final String RELAY_OBSERVATION = "onlineorder.outbox.relay";

    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");
    private static final Pattern TRACEPARENT_FORMAT = Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    private static final String INVALID_TRACE_ID = "0".repeat(32);
    private static final String INVALID_SPAN_ID = "0".repeat(16);


    private OutboxTracing() {
    }


    /**
     * @return the W3C traceparent ({@code 00-<trace id>-<span id>-<flags>}) of the span in scope, or null when no
     * valid span is in scope
     */
    public static String currentTraceParent(Tracer tracer) {
        if (tracer == null) {
            return null;
        }
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        return traceParent(context.traceId(), context.spanId(), Boolean.TRUE.equals(context.sampled()));
    }


    static String traceParent(String traceId, String spanId, boolean sampled) {
        if (traceId == null || spanId == null
                || !TRACE_ID.matcher(traceId).matches() || !SPAN_ID.matcher(spanId).matches()
                || INVALID_TRACE_ID.equals(traceId) || INVALID_SPAN_ID.equals(spanId)) {
            return null;
        }
        return "00-" + traceId + "-" + spanId + (sampled ? "-01" : "-00");
    }


    /**
     * Starts the publisher's observation for relaying one event. Its span continues the trace stored with the event
     * (or starts a new trace when there is none) instead of the scheduler run that happens to send it; the caller
     * opens a scope around the Kafka send and stops the observation when the send completes.
     */
    public static Observation startRelay(ObservationRegistry registry, OutboxEventEntity event) {
        String traceParent = event.traceParent();
        Map<String, String> carrier = traceParent != null && TRACEPARENT_FORMAT.matcher(traceParent).matches()
                ? Map.of(TRACEPARENT, traceParent)
                : Map.of();
        ReceiverContext<Map<String, String>> context = new ReceiverContext<>(
                (headers, key) -> headers.get(key),
                Kind.CONSUMER
        );
        context.setCarrier(carrier);
        context.setRemoteServiceName("outbox");

        String topic = valueOrUnknown(event.topic());
        Observation relay = Observation.createNotStarted(RELAY_OBSERVATION, () -> context, registry)
                .contextualName(topic + " outbox relay")
                .lowCardinalityKeyValue("messaging.destination.name", topic)
                .lowCardinalityKeyValue("outbox.event_type", valueOrUnknown(event.eventType()))
                .highCardinalityKeyValue("outbox.event_id", valueOrUnknown(event.eventId()))
                .highCardinalityKeyValue("outbox.aggregate_type", valueOrUnknown(event.aggregateType()))
                .highCardinalityKeyValue("outbox.aggregate_id", String.valueOf(event.aggregateId()));
        // Extraction falls back to the context in scope, the scheduler run that relays the event, when the row
        // carries no traceparent. Starting from the root context gives such an event a trace of its own instead.
        try (Scope ignored = Context.root().makeCurrent()) {
            return relay.start();
        }
    }


    private static String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
