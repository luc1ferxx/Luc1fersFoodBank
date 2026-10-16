package com.laioffer.onlineorder.observability;


import com.laioffer.onlineorder.entity.OutboxEventEntity;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.relational.core.mapping.event.BeforeConvertCallback;
import org.springframework.stereotype.Component;


/**
 * Stamps every new outbox event with the trace context of the span in scope, at the one point all outbox inserts
 * share: Spring Data JDBC's save of an {@link OutboxEventEntity}. Services that enqueue events, whatever their
 * aggregate type, do not have to pass the context along. Updates and events that already carry a context are left
 * unchanged.
 */
@Component
public class OutboxTraceContextCallback implements BeforeConvertCallback<OutboxEventEntity> {

    private final ObjectProvider<Tracer> tracer;


    public OutboxTraceContextCallback(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }


    @Override
    public OutboxEventEntity onBeforeConvert(OutboxEventEntity event) {
        if (event.id() != null || event.traceParent() != null) {
            return event;
        }
        String traceParent = OutboxTracing.currentTraceParent(tracer.getIfAvailable());
        return traceParent == null ? event : event.withTraceParent(traceParent);
    }
}
