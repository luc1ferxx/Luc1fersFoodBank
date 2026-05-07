package com.laioffer.onlineorder.error;


import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.model.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;


@RestControllerAdvice
public class ApiExceptionHandler {

    private final ApiErrorResponseFactory errorResponseFactory;


    public ApiExceptionHandler(ApiErrorResponseFactory errorResponseFactory) {
        this.errorResponseFactory = errorResponseFactory;
    }


    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        return build("VALIDATION_FAILED", "Request validation failed", HttpStatus.BAD_REQUEST, request);
    }


    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex,
            HttpServletRequest request
    ) {
        return build("VALIDATION_FAILED", "Request validation failed", HttpStatus.BAD_REQUEST, request);
    }


    @ExceptionHandler({
            BadRequestException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception ex, HttpServletRequest request) {
        return build("BAD_REQUEST", safeMessage(ex, "Bad request"), HttpStatus.BAD_REQUEST, request);
    }


    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(ConflictException ex, HttpServletRequest request) {
        return build("CONFLICT", safeMessage(ex, "Conflict"), HttpStatus.CONFLICT, request);
    }


    @ExceptionHandler({ResourceNotFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleNotFound(Exception ex, HttpServletRequest request) {
        return build("NOT_FOUND", safeMessage(ex, "Resource not found"), HttpStatus.NOT_FOUND, request);
    }


    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return build("UNAUTHORIZED", "Authentication required", HttpStatus.UNAUTHORIZED, request);
    }


    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build("FORBIDDEN", "Access denied", HttpStatus.FORBIDDEN, request);
    }


    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request
    ) {
        return build("METHOD_NOT_ALLOWED", safeMessage(ex, "Method not allowed"), HttpStatus.METHOD_NOT_ALLOWED, request);
    }


    private ResponseEntity<ApiErrorResponse> build(
            String code,
            String message,
            HttpStatus status,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(status)
                .body(errorResponseFactory.create(code, message, status, request));
    }


    private String safeMessage(Exception ex, String fallback) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }
}
