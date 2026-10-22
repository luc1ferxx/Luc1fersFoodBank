package com.laioffer.onlineorder.push;


import com.laioffer.onlineorder.error.ApiErrorResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


import java.io.IOException;
import java.sql.SQLTransientConnectionException;


/**
 * GET /events/stream: the push stream of the signed-in login (ADR 0003), text/event-stream over servlet async, so no
 * request thread is held while it is open. Anonymous requests get 401 from the security chain; the refusals of
 * {@link PushStreamService} are written here as JSON whatever the request accepts (an EventSource asks only for
 * text/event-stream, which an error body cannot be negotiated to).
 */
@RestController
public class PushStreamController {

    static final String ACCEL_BUFFERING_HEADER = "X-Accel-Buffering";

    private final PushStreamService pushStreamService;
    private final ApiErrorResponseWriter apiErrorResponseWriter;


    public PushStreamController(PushStreamService pushStreamService, ApiErrorResponseWriter apiErrorResponseWriter) {
        this.pushStreamService = pushStreamService;
        this.apiErrorResponseWriter = apiErrorResponseWriter;
    }


    /**
     * No caching, and no buffering in nginx or the ingress, which would hold the frames back.
     */
    @GetMapping(path = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(Authentication authentication) {
        SseEmitter emitter = pushStreamService.open(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header(ACCEL_BUFFERING_HEADER, "no")
                .body(emitter);
    }


    @ExceptionHandler(PushStreamRefusedException.class)
    public void refused(
            PushStreamRefusedException ex,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        apiErrorResponseWriter.write(request, response, ex.status(), ex.code(), ex.getMessage());
    }


    /**
     * The database could not be reached for the login's lookups: the same 503 as {@code ApiExceptionHandler} answers.
     */
    @ExceptionHandler({
            CannotGetJdbcConnectionException.class,
            CannotCreateTransactionException.class,
            SQLTransientConnectionException.class
    })
    public void databaseUnavailable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        apiErrorResponseWriter.write(request, response, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "Service temporarily unavailable, please retry");
    }
}
