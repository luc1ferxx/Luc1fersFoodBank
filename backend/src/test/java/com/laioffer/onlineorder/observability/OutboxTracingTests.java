package com.laioffer.onlineorder.observability;


import com.laioffer.onlineorder.entity.OutboxEventEntity;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.Kind;
import io.micrometer.observation.transport.SenderContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;


import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


class OutboxTracingTests {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .setSampler(Sampler.alwaysOn())
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
    private final io.opentelemetry.api.trace.Tracer otelTracer = tracerProvider.get("outbox-tracing-test");
    private final Tracer tracer = new OtelTracer(otelTracer, new OtelCurrentTraceContext(), event -> {
    });
    private final ObservationRegistry registry = ObservationRegistry.create();


    OutboxTracingTests() {
        OtelPropagator propagator = new OtelPropagator(
                ContextPropagators.create(W3CTraceContextPropagator.getInstance()),
                otelTracer
        );
        registry.observationConfig().observationHandler(new ObservationHandler.FirstMatchingCompositeObservationHandler(
                new PropagatingSenderTracingObservationHandler<>(tracer, propagator),
                new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                new DefaultTracingObservationHandler(tracer)
        ));
    }


    @AfterEach
    void closeTracerProvider() {
        tracerProvider.close();
    }


    @Test
    void currentTraceParent_shouldFormatTheSpanInScopeAsW3cTraceparent() {
        Span request = tracer.nextSpan().name("request").start();
        String traceParent;
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            traceParent = OutboxTracing.currentTraceParent(tracer);
        } finally {
            request.end();
        }

        Assertions.assertEquals(
                "00-" + request.context().traceId() + "-" + request.context().spanId() + "-01",
                traceParent
        );
        Assertions.assertNull(OutboxTracing.currentTraceParent(tracer), "no span in scope");
        Assertions.assertNull(OutboxTracing.currentTraceParent(Tracer.NOOP));
        Assertions.assertNull(OutboxTracing.currentTraceParent(null));
    }


    @Test
    void traceParent_shouldRejectMalformedOrInvalidIds() {
        Assertions.assertNull(OutboxTracing.traceParent("0".repeat(32), "1".repeat(16), true));
        Assertions.assertNull(OutboxTracing.traceParent("1".repeat(32), "0".repeat(16), true));
        Assertions.assertNull(OutboxTracing.traceParent("not-a-trace-id", "1".repeat(16), true));
        Assertions.assertEquals(
                "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-00",
                OutboxTracing.traceParent("a".repeat(32), "b".repeat(16), false)
        );
    }


    @Test
    void startRelay_shouldContinueStoredTraceAndParentTheKafkaSendSpan() {
        Span request = tracer.nextSpan().name("request").start();
        String traceParent;
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            traceParent = OutboxTracing.currentTraceParent(tracer);
        } finally {
            request.end();
        }
        OutboxEventEntity event = event(null).withTraceParent(traceParent);

        // A separate, unrelated span in scope stands in for the scheduler run that relays the event.
        Span schedulerRun = tracer.nextSpan().name("scheduler run").start();
        Map<String, String> kafkaHeaders = new HashMap<>();
        try (Tracer.SpanInScope ignored = tracer.withSpan(schedulerRun)) {
            Observation relay = OutboxTracing.startRelay(registry, event);
            try (Observation.Scope scope = relay.openScope()) {
                SenderContext<Map<String, String>> send = new SenderContext<>(Map::put, Kind.PRODUCER);
                send.setCarrier(kafkaHeaders);
                Observation.createNotStarted("kafka send", () -> send, registry).observe(() -> {
                });
            }
            relay.stop();
        } finally {
            schedulerRun.end();
        }

        SpanData relaySpan = span("order-events outbox relay");
        SpanData sendSpan = span("kafka send");
        Assertions.assertEquals(request.context().traceId(), relaySpan.getTraceId());
        Assertions.assertEquals(request.context().spanId(), relaySpan.getParentSpanId());
        Assertions.assertEquals(SpanKind.CONSUMER, relaySpan.getKind());
        Assertions.assertEquals("evt-1", relaySpan.getAttributes().asMap().entrySet().stream()
                .filter(entry -> entry.getKey().getKey().equals("outbox.event_id"))
                .map(entry -> String.valueOf(entry.getValue()))
                .findFirst()
                .orElse(null));
        Assertions.assertEquals(request.context().traceId(), sendSpan.getTraceId());
        Assertions.assertEquals(relaySpan.getSpanId(), sendSpan.getParentSpanId());
        Assertions.assertTrue(
                kafkaHeaders.get(OutboxTracing.TRACEPARENT).contains(request.context().traceId()),
                () -> "Kafka record headers should carry the request trace: " + kafkaHeaders
        );
    }


    @Test
    void startRelay_withoutStoredContext_shouldStartNewTrace() {
        Observation relay = OutboxTracing.startRelay(registry, event(null));
        relay.stop();
        Observation malformed = OutboxTracing.startRelay(registry, event(null).withTraceParent("garbage"));
        malformed.stop();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        Assertions.assertEquals(2, spans.size());
        spans.forEach(span -> Assertions.assertFalse(span.getParentSpanContext().isValid()));
        Assertions.assertNotEquals(spans.get(0).getTraceId(), spans.get(1).getTraceId());
    }


    @Test
    void callback_shouldStampOnlyNewEventsWithoutContext() {
        OutboxTraceContextCallback callback = new OutboxTraceContextCallback(
                new StaticListableBeanFactory(Map.of("tracer", tracer)).getBeanProvider(Tracer.class)
        );
        Span request = tracer.nextSpan().name("request").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            OutboxEventEntity stamped = callback.onBeforeConvert(event(null));
            Assertions.assertEquals(OutboxTracing.currentTraceParent(tracer), stamped.traceParent());

            OutboxEventEntity existing = event(7L);
            Assertions.assertSame(existing, callback.onBeforeConvert(existing), "updates keep their context");

            OutboxEventEntity alreadyStamped = event(null).withTraceParent("00-" + "c".repeat(32) + "-" + "d".repeat(16) + "-01");
            Assertions.assertSame(alreadyStamped, callback.onBeforeConvert(alreadyStamped));
        } finally {
            request.end();
        }

        OutboxEventEntity untraced = event(null);
        Assertions.assertNull(callback.onBeforeConvert(untraced).traceParent(), "no span in scope");
    }


    private SpanData span(String name) {
        return exporter.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No span named " + name + " in " + exporter.getFinishedSpanItems()));
    }


    private static OutboxEventEntity event(Long id) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 1, 12, 0);
        return new OutboxEventEntity(
                id,
                "ORDER",
                42L,
                "evt-1",
                "order-events",
                "42",
                "order.paid",
                "{}",
                "PENDING",
                0,
                null,
                now,
                now,
                null
        );
    }
}
