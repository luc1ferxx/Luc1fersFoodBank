package com.laioffer.onlineorder.model;


import com.fasterxml.jackson.annotation.JsonProperty;


import java.time.Instant;


public record ApiErrorResponse(
        String code,
        String message,
        @JsonProperty("trace_id")
        String traceId,
        int status,
        String path,
        Instant timestamp
) {
}
