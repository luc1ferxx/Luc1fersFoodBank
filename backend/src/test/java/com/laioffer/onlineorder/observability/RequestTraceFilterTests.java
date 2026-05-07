package com.laioffer.onlineorder.observability;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;


import java.util.concurrent.atomic.AtomicReference;


class RequestTraceFilterTests {

    @Test
    void doFilter_shouldEchoTraceHeaderIntoResponseAndMdc() throws Exception {
        RequestTraceFilter filter = new RequestTraceFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceIdInsideFilterChain = new AtomicReference<>();
        request.addHeader(RequestTraceFilter.TRACE_ID_HEADER, "trace-12345");

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                traceIdInsideFilterChain.set(MDC.get("traceId"))
        );

        Assertions.assertEquals("trace-12345", response.getHeader(RequestTraceFilter.TRACE_ID_HEADER));
        Assertions.assertEquals("trace-12345", traceIdInsideFilterChain.get());
        Assertions.assertNull(MDC.get("traceId"));
    }
}
