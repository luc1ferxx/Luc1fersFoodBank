package com.laioffer.onlineorder;


import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.laioffer.onlineorder.entity.OutboxEventEntity;
import com.laioffer.onlineorder.messaging.OutboxEventPublisher;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;


import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;


/**
 * The publisher's tracing: each send runs in the trace stored with the event, failures are logged in that trace, and
 * tracing problems never change whether or how often an event is published.
 */
class OutboxEventPublisherTracingTests {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT_SPAN_ID = "00f067aa0ba902b7";
    private static final String FAILURE_LOG_PREFIX = "Failed to publish outbox event";
    // The token of the claim the mocked claimNextBatch returns; every result write must pass it on.
    private static final String CLAIM_TOKEN = "claim-1";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = Mockito.mock(KafkaTemplate.class);
    private final OrderEventOutboxService orderEventOutboxService = Mockito.mock(OrderEventOutboxService.class);
    private final ApplicationMetricsService metricsService = Mockito.mock(ApplicationMetricsService.class);


    @Test
    void publish_shouldSendEachEventInsideTheTraceStoredWithIt() {
        try (TracingFixture tracing = new TracingFixture()) {
            OutboxEventEntity event = tracedEvent();
            AtomicReference<Span> spanAtSend = new AtomicReference<>();
            Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(event));
            Answer<?> recordSpanAndAcknowledge = invocation -> {
                spanAtSend.set(tracing.tracer.currentSpan());
                return CompletableFuture.completedFuture(Mockito.mock(SendResult.class));
            };
            Mockito.when(kafkaTemplate.send(event.topic(), event.eventKey(), event.payload()))
                    .thenAnswer(recordSpanAndAcknowledge);

            publishWith(tracing.registry);

            Assertions.assertNotNull(spanAtSend.get(), "the send should run with the relay span in scope");
            Assertions.assertEquals(TRACE_ID, spanAtSend.get().context().traceId());
            Assertions.assertEquals(PARENT_SPAN_ID, spanAtSend.get().context().parentId());
            Assertions.assertNull(tracing.tracer.currentSpan(), "the relay scope is closed after the send");
            Mockito.verify(orderEventOutboxService).markPublished(CLAIM_TOKEN, List.of(event.id()));
            SpanData relay = tracing.relaySpan();
            Assertions.assertEquals(TRACE_ID, relay.getTraceId());
            Assertions.assertEquals(StatusCode.UNSET, relay.getStatus().getStatusCode());
        }
    }


    @Test
    void publish_whenTheAcknowledgementFails_shouldLogTheFailureInsideTheEventsTrace() {
        try (TracingFixture tracing = new TracingFixture()) {
            OutboxEventEntity event = tracedEvent();
            Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(event));
            // The producer accepts the record and reports the failure later, from its own thread.
            Answer<?> failLater = invocation -> {
                CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
                CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS)
                        .execute(() -> future.completeExceptionally(new KafkaException("broker rejected the batch")));
                return future;
            };
            Mockito.when(kafkaTemplate.send(event.topic(), event.eventKey(), event.payload())).thenAnswer(failLater);

            List<String> traceIdsAtFailureLog = traceIdsAtFailureLogs(tracing, () -> publishWith(tracing.registry));

            Assertions.assertEquals(List.of(TRACE_ID), traceIdsAtFailureLog);
            Mockito.verify(orderEventOutboxService).markRetryableFailure(Mockito.eq(CLAIM_TOKEN), Mockito.eq(event.id()), Mockito.anyString());
            SpanData relay = tracing.relaySpan();
            Assertions.assertEquals(TRACE_ID, relay.getTraceId());
            Assertions.assertEquals(StatusCode.ERROR, relay.getStatus().getStatusCode());
            Assertions.assertNull(tracing.tracer.currentSpan(), "the relay scope is closed after the failure log");
        }
    }


    @Test
    void publish_whenTheSendIsRejected_shouldLogTheFailureInsideTheEventsTrace() {
        try (TracingFixture tracing = new TracingFixture()) {
            OutboxEventEntity event = tracedEvent();
            Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(event));
            Mockito.when(kafkaTemplate.send(event.topic(), event.eventKey(), event.payload()))
                    .thenThrow(new KafkaException("producer closed"));

            List<String> traceIdsAtFailureLog = traceIdsAtFailureLogs(tracing, () -> publishWith(tracing.registry));

            Assertions.assertEquals(List.of(TRACE_ID), traceIdsAtFailureLog);
            Assertions.assertEquals(StatusCode.ERROR, tracing.relaySpan().getStatus().getStatusCode());
            Assertions.assertNull(tracing.tracer.currentSpan());
        }
    }


    @Test
    void publish_whenTheRelayCannotStart_shouldStillPublishTheEvent() {
        ObservationRegistry registry = registryWithFailingHandler(handler -> handler.failOnStart = true);
        OutboxEventEntity event = event();
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(event));
        acknowledgeSends(event);

        publishWith(registry);

        assertPublishedOnce(event);
    }


    @Test
    void publish_whenTheRelayScopeCannotBeOpened_shouldPublishTheEventOnce() {
        ObservationRegistry registry = registryWithFailingHandler(handler -> handler.failOnScopeOpened = true);
        OutboxEventEntity event = event();
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(event));
        acknowledgeSends(event);

        publishWith(registry);

        assertPublishedOnce(event);
        Assertions.assertNull(registry.getCurrentObservationScope(), "a failed scope must not stay current");
    }


    @Test
    void publish_whenClosingTheRelayScopeFails_shouldNotSendTheEventAgain() {
        // The record was handed to Kafka before the scope closed: treating the close failure as a send failure would
        // use up an attempt and send the event a second time.
        ObservationRegistry registry = registryWithFailingHandler(handler -> handler.failOnScopeClosed = true);
        OutboxEventEntity event = event();
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(event));
        acknowledgeSends(event);

        publishWith(registry);

        assertPublishedOnce(event);
        Assertions.assertNull(registry.getCurrentObservationScope(), "a failed close must restore the previous scope");
    }


    @Test
    void publish_whenEndingTheRelayFails_shouldStillMarkTheEventPublished() {
        ObservationRegistry registry = registryWithFailingHandler(handler -> handler.failOnStop = true);
        OutboxEventEntity event = event();
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(event));
        acknowledgeSends(event);

        publishWith(registry);

        assertPublishedOnce(event);
    }


    private void publishWith(ObservationRegistry registry) {
        OutboxEventPublisher publisher = new OutboxEventPublisher(
                kafkaTemplate,
                Duration.ofSeconds(5),
                20,
                Duration.ofMillis(500),
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                orderEventOutboxService,
                metricsService,
                Clock.systemUTC()
        );
        publisher.setObservationRegistry(registry);
        publisher.publishPendingEvents();
    }


    private void acknowledgeSends(OutboxEventEntity event) {
        Mockito.when(kafkaTemplate.send(event.topic(), event.eventKey(), event.payload()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }


    private void assertPublishedOnce(OutboxEventEntity event) {
        Mockito.verify(kafkaTemplate, Mockito.times(1)).send(event.topic(), event.eventKey(), event.payload());
        Mockito.verify(orderEventOutboxService).markPublished(CLAIM_TOKEN, List.of(event.id()));
        Mockito.verify(orderEventOutboxService, Mockito.never())
                .markRetryableFailure(Mockito.any(), Mockito.anyLong(), Mockito.anyString());
        Mockito.verify(orderEventOutboxService, Mockito.never())
                .releaseFailedClaims(Mockito.any(), Mockito.anyList(), Mockito.anyString());
        Mockito.verify(orderEventOutboxService, Mockito.never()).releaseClaims(Mockito.any(), Mockito.anyList());
    }


    /**
     * Records, for every "Failed to publish outbox event" line, the trace id in scope when the line was written; this
     * is the trace id the logging MDC carries in the application.
     */
    private static List<String> traceIdsAtFailureLogs(TracingFixture tracing, Runnable action) {
        List<String> traceIds = Collections.synchronizedList(new ArrayList<>());
        Logger logger = (Logger) LoggerFactory.getLogger(OutboxEventPublisher.class);
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent loggingEvent) {
                if (loggingEvent.getFormattedMessage().startsWith(FAILURE_LOG_PREFIX)) {
                    Span span = tracing.tracer.currentSpan();
                    traceIds.add(span == null ? "no span in scope" : span.context().traceId());
                }
            }
        };
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return List.copyOf(traceIds);
    }


    private static ObservationRegistry registryWithFailingHandler(Consumer<FailingHandler> configuration) {
        FailingHandler handler = new FailingHandler();
        configuration.accept(handler);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(handler);
        return registry;
    }


    private static OutboxEventEntity tracedEvent() {
        return event().withTraceParent("00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01");
    }


    private static OutboxEventEntity event() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 1, 12, 0);
        return new OutboxEventEntity(
                1L,
                "ORDER",
                7L,
                "evt-1",
                "order-events",
                "7",
                "order.paid",
                "{}",
                "PROCESSING",
                1,
                null,
                now,
                now,
                null
        ).withClaimToken(CLAIM_TOKEN);
    }


    /**
     * An observation handler that stands in for a broken tracing backend.
     */
    private static final class FailingHandler implements ObservationHandler<Observation.Context> {

        private boolean failOnStart;
        private boolean failOnScopeOpened;
        private boolean failOnScopeClosed;
        private boolean failOnStop;


        @Override
        public void onStart(Observation.Context context) {
            failIf(failOnStart, "start");
        }


        @Override
        public void onScopeOpened(Observation.Context context) {
            failIf(failOnScopeOpened, "scope opened");
        }


        @Override
        public void onScopeClosed(Observation.Context context) {
            failIf(failOnScopeClosed, "scope closed");
        }


        @Override
        public void onStop(Observation.Context context) {
            failIf(failOnStop, "stop");
        }


        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }


        private static void failIf(boolean fail, String phase) {
            if (fail) {
                throw new IllegalStateException("tracing backend broken on " + phase);
            }
        }
    }


    /**
     * An OpenTelemetry SDK tracer wired into an observation registry the way Spring Boot wires it, with spans
     * exported to memory.
     */
    private static final class TracingFixture implements AutoCloseable {

        private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
        private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        private final Tracer tracer;
        private final ObservationRegistry registry = ObservationRegistry.create();


        private TracingFixture() {
            io.opentelemetry.api.trace.Tracer otelTracer = tracerProvider.get("publisher-tracing-test");
            tracer = new OtelTracer(otelTracer, new OtelCurrentTraceContext(), event -> {
            });
            OtelPropagator propagator = new OtelPropagator(
                    ContextPropagators.create(W3CTraceContextPropagator.getInstance()),
                    otelTracer
            );
            registry.observationConfig().observationHandler(
                    new ObservationHandler.FirstMatchingCompositeObservationHandler(
                            new PropagatingSenderTracingObservationHandler<>(tracer, propagator),
                            new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                            new DefaultTracingObservationHandler(tracer)
                    )
            );
        }


        private SpanData relaySpan() {
            List<SpanData> relays = exporter.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().equals("order-events outbox relay"))
                    .toList();
            Assertions.assertEquals(1, relays.size(), () -> "one ended relay span expected: " + relays);
            return relays.get(0);
        }


        @Override
        public void close() {
            tracerProvider.close();
        }
    }
}
