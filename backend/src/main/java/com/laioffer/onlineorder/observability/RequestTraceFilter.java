package com.laioffer.onlineorder.observability;


import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;


import java.io.IOException;
import java.util.UUID;


/**
 * Returns the request's trace id in the {@value #TRACE_ID_HEADER} response header and exposes it to the error
 * responses.
 *
 * <p>Runs right after Spring's ServerHttpObservationFilter (order {@code HIGHEST_PRECEDENCE + 1}), which starts the
 * request's server span from an incoming W3C {@code traceparent} header or as a new trace. The header then carries
 * the OpenTelemetry trace id, the same id the logs (MDC {@code traceId}/{@code spanId}, set by Micrometer Tracing),
 * the exported spans and the Kafka records of this request carry. A client-supplied {@value #TRACE_ID_HEADER} is
 * ignored in that case; clients join a trace by sending {@code traceparent}.
 *
 * <p>When no span is in scope (tracing switched off) the previous behaviour applies: a well-formed incoming
 * {@value #TRACE_ID_HEADER} is echoed, otherwise a random id is generated, and the filter puts it in the MDC itself.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RequestTraceFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_ATTRIBUTE = RequestTraceFilter.class.getName() + ".traceId";

    private static final String MDC_TRACE_ID = "traceId";

    private final ObjectProvider<Tracer> tracer;


    public RequestTraceFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }


    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = currentTraceId();
        if (traceId != null) {
            response.setHeader(TRACE_ID_HEADER, traceId);
            request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
            filterChain.doFilter(request, response);
            return;
        }

        traceId = resolveTraceId(request);
        response.setHeader(TRACE_ID_HEADER, traceId);
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        MDC.put(MDC_TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
        }
    }


    private String currentTraceId() {
        Tracer currentTracer = tracer.getIfAvailable();
        Span span = currentTracer == null ? null : currentTracer.currentSpan();
        if (span == null) {
            return null;
        }
        String traceId = span.context().traceId();
        return traceId == null || traceId.isBlank() ? null : traceId;
    }


    private String resolveTraceId(HttpServletRequest request) {
        String headerValue = request.getHeader(TRACE_ID_HEADER);
        if (headerValue != null && headerValue.matches("^[A-Za-z0-9._-]{8,128}$")) {
            return headerValue;
        }
        return UUID.randomUUID().toString();
    }
}
