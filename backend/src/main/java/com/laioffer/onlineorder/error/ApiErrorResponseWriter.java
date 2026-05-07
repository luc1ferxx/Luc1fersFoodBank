package com.laioffer.onlineorder.error;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.model.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;


import java.io.IOException;


@Component
public class ApiErrorResponseWriter {

    private final ApiErrorResponseFactory errorResponseFactory;
    private final ObjectMapper objectMapper;


    public ApiErrorResponseWriter(
            ApiErrorResponseFactory errorResponseFactory,
            ObjectMapper objectMapper
    ) {
        this.errorResponseFactory = errorResponseFactory;
        this.objectMapper = objectMapper;
    }


    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String message
    ) throws IOException {
        if (response.isCommitted()) {
            return;
        }

        ApiErrorResponse errorResponse = errorResponseFactory.create(code, message, status, request);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), errorResponse);
    }
}
