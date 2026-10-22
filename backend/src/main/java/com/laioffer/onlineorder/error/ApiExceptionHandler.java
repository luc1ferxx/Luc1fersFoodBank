package com.laioffer.onlineorder.error;


import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.ids.IdGeneratorUnavailableException;
import com.laioffer.onlineorder.model.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.DisconnectedClientHelper;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;


import java.sql.SQLTransientConnectionException;


@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

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
        String code = ex instanceof BadRequestException badRequest ? badRequest.getCode() : BadRequestException.DEFAULT_CODE;
        return build(code, safeMessage(ex, "Bad request"), HttpStatus.BAD_REQUEST, request);
    }


    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(ConflictException ex, HttpServletRequest request) {
        return build(ex.getCode(), safeMessage(ex, "Conflict"), HttpStatus.CONFLICT, request);
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


    /**
     * HikariCP throws SQLTransientConnectionException when no connection frees up within its
     * connection-timeout. Spring wraps it in CannotGetJdbcConnectionException (plain JDBC access) or
     * CannotCreateTransactionException (@Transactional), and nested causes are matched too. Answering
     * 503 tells clients and dashboards the request was shed under load, not that it hit a bug.
     */
    @ExceptionHandler({
            CannotGetJdbcConnectionException.class,
            CannotCreateTransactionException.class,
            SQLTransientConnectionException.class
    })
    public ResponseEntity<ApiErrorResponse> handleDatabaseUnavailable(Exception ex, HttpServletRequest request) {
        return build(
                "SERVICE_UNAVAILABLE",
                "Service temporarily unavailable, please retry",
                HttpStatus.SERVICE_UNAVAILABLE,
                request
        );
    }


    /**
     * Checkout could not get an order number: no Snowflake worker id lease is held (the database was unreachable for
     * longer than the lease TTL) or the clock stepped back further than app.ids.max-clock-backward. Both pass, so the
     * client retries, with the same Idempotency-Key.
     */
    @ExceptionHandler(IdGeneratorUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleIdGeneratorUnavailable(
            IdGeneratorUnavailableException ex,
            HttpServletRequest request
    ) {
        return build(
                "SERVICE_UNAVAILABLE",
                "Service temporarily unavailable, please retry",
                HttpStatus.SERVICE_UNAVAILABLE,
                request
        );
    }


    /**
     * The client went away while the body was written: a console's read aborted by its next one (it re-reads its list
     * on every push signal), a closed tab. Jackson's write then ends in HttpMessageNotWritableException over the
     * "Broken pipe" IOException, which no handler covered, so DefaultHandlerExceptionResolver answered it, and its
     * answer for a committed response is the WARN "Ignoring exception, response committed already" (912 lines in two
     * minutes once). A client that left is not a server fault: one DEBUG line, no stack trace, nothing written,
     * whether or not the response is committed. Any other write failure is rethrown as it is:
     * ExceptionHandlerExceptionResolver takes a handler that throws the original exception as not having handled it
     * and logs nothing for it, so the next resolver answers 500 as before.
     */
    @ExceptionHandler(HttpMessageNotWritableException.class)
    public void handleNotWritable(HttpMessageNotWritableException ex, HttpServletRequest request) {
        if (!DisconnectedClientHelper.isClientDisconnectedException(ex)) {
            throw ex;
        }
        LOGGER.debug(
                "Client went away while the response was written: {} {}: {}",
                request.getMethod(),
                request.getRequestURI(),
                ex.toString()
        );
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
