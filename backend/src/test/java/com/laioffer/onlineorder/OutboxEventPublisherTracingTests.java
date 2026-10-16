package com.laioffer.onlineorder;


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
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;


import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;


/**
 * The publisher's tracing: each send runs in the trace stored with the event, and tracing problems never change
 * whether an event is published.
 */
class OutboxEventPublisherTracingTests {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT_SPAN_ID = "00f067aa0ba902b7";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = Mockito.mock(KafkaTemplate.class);
    private final OrderEventOutboxService orderEventOutboxService = Mockito.mock(OrderEventOutboxService.class);
    private final ApplicationMetricsService metricsService = Mockito.mock(ApplicationMetricsService.class);


    @Test
    void publish_shouldSendEachEventInsideTheTraceStoredWithIt() {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).build();
        io.opentelemetry.api.trace.Tracer otelTracer = tracerProvider.get("publisher-tracing-test");
        Tracer tracer = new OtelTracer(otelTracer, new OtelCurrentTraceContext(), event -> {
        });
        OtelPropagator propagator = new OtelPropagator(
                ContextPropagators.create(W3CTraceContextPropagator.getInstance()),
                otelTracer
        );
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler.FirstMatchingCompositeObservationHandler(
                new PropagatingSenderTracingObservationHandler<>(tracer, propagator),
                new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                new DefaultTracingObservationHandler(tracer)
        ));

        OutboxEventEntity event = event().withTraceParent("00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01");
        AtomicReference<Span> spanAtSend = new AtomicReference<>();
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(event));
        Mockito.when(kafkaTemplate.send(event.topic(), event.eventKey(), event.payload())).thenAnswer(invocation -> {
            spanAtSend.set(tracer.currentSpan());
            return CompletableFuture.completedFuture(Mockito.mock(SendResult.class));
        });

        OutboxEventPublisher publisher = newPublisher();
        publisher.setObservationRegistry(registry);
        publisher.publishPendingEvents();

        Assertions.assertNotNull(spanAtSend.get(), "the send should run with the relay span in scope");
        Assertions.assertEquals(TRACE_ID, spanAtSend.get().context().traceId());
        Assertions.assertEquals(PARENT_SPAN_ID, spanAtSend.get().context().parentId());
        Assertions.assertNull(tracer.currentSpan(), "the relay scope is closed after the send");
        Mockito.verify(orderEventOutboxService).markPublished(List.of(event.id()));
        tracerProvider.close();
    }


    @Test
    void publish_whenTracingFails_shouldStillPublishTheEvent() {
        ObservationRegistry failingRegistry = ObservationRegistry.create();
        failingRegistry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public void onStart(Observation.Context context) {
                throw new IllegalStateException("tracing backend broken");
            }


            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        OutboxEventEntity event = event();
        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(event));
        Mockito.when(kafkaTemplate.send(event.topic(), event.eventKey(), event.payload()))
                .thenReturn(CompletableFuture.completedFuture(null));

        OutboxEventPublisher publisher = newPublisher();
        publisher.setObservationRegistry(failingRegistry);
        publisher.publishPendingEvents();

        Mockito.verify(kafkaTemplate).send(event.topic(), event.eventKey(), event.payload());
        Mockito.verify(orderEventOutboxService).markPublished(List.of(event.id()));
        Mockito.verify(orderEventOutboxService, Mockito.never()).markRetryableFailure(Mockito.anyLong(), Mockito.anyString());
    }


    private OutboxEventPublisher newPublisher() {
        return new OutboxEventPublisher(
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
        );
    }
}
