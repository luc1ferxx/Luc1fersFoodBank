package com.laioffer.onlineorder.observability;


import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;


import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;


class RequestTraceFilterTests {

    @Test
    void doFilter_whenNoSpanIsInScope_shouldEchoTraceHeaderIntoResponseAndMdc() throws Exception {
        RequestTraceFilter filter = new RequestTraceFilter(tracerProvider(Tracer.NOOP));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceIdInsideFilterChain = new AtomicReference<>();
        request.addHeader(RequestTraceFilter.TRACE_ID_HEADER, "trace-12345");

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                traceIdInsideFilterChain.set(MDC.get("traceId"))
        );

        Assertions.assertEquals("trace-12345", response.getHeader(RequestTraceFilter.TRACE_ID_HEADER));
        Assertions.assertEquals("trace-12345", traceIdInsideFilterChain.get());
        Assertions.assertEquals("trace-12345", request.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE));
        Assertions.assertNull(MDC.get("traceId"));
    }


    @Test
    void doFilter_whenSpanIsInScope_shouldReturnItsTraceIdInsteadOfClientHeader() throws Exception {
        String otelTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        Tracer tracer = Mockito.mock(Tracer.class);
        Span span = Mockito.mock(Span.class);
        TraceContext context = Mockito.mock(TraceContext.class);
        Mockito.when(tracer.currentSpan()).thenReturn(span);
        Mockito.when(span.context()).thenReturn(context);
        Mockito.when(context.traceId()).thenReturn(otelTraceId);

        RequestTraceFilter filter = new RequestTraceFilter(tracerProvider(tracer));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(RequestTraceFilter.TRACE_ID_HEADER, "client-trace-12345");

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
        });

        Assertions.assertEquals(otelTraceId, response.getHeader(RequestTraceFilter.TRACE_ID_HEADER));
        Assertions.assertEquals(otelTraceId, request.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE));
    }


    private static ObjectProvider<Tracer> tracerProvider(Tracer tracer) {
        return new StaticListableBeanFactory(Map.of("tracer", tracer)).getBeanProvider(Tracer.class);
    }
}
