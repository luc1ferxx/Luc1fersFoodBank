package com.laioffer.onlineorder.error;


import com.laioffer.onlineorder.model.ApiErrorResponse;
import com.laioffer.onlineorder.observability.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;


import java.time.Instant;


@Component
public class ApiErrorResponseFactory {

    public ApiErrorResponse create(
            String code,
            String message,
            HttpStatus status,
            HttpServletRequest request
    ) {
        return new ApiErrorResponse(
                code,
                message,
                resolveTraceId(request),
                status.value(),
                request.getRequestURI(),
                Instant.now()
        );
    }


    private String resolveTraceId(HttpServletRequest request) {
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }
        String headerValue = request.getHeader(RequestTraceFilter.TRACE_ID_HEADER);
        return headerValue == null || headerValue.isBlank() ? "unknown" : headerValue;
    }
}
