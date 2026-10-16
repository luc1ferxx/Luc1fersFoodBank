package com.laioffer.onlineorder.observability;


import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;


/**
 * Reads the OpenTelemetry trace id of the span in scope, the id the X-Trace-Id header, the logs and the error bodies
 * carry, for rows that record who did what (order status history, audit log).
 */
public final class TraceIds {

    private static final String INVALID_TRACE_ID = "0".repeat(32);


    private TraceIds() {
    }


    /**
     * @return the trace id of the span in scope, or null when there is no tracer or no valid span in scope
     */
    public static String current(Tracer tracer) {
        if (tracer == null) {
            return null;
        }
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        String traceId = context.traceId();
        if (traceId == null || traceId.isBlank() || INVALID_TRACE_ID.equals(traceId)) {
            return null;
        }
        return traceId;
    }
}
