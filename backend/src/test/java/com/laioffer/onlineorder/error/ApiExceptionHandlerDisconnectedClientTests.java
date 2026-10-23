package com.laioffer.onlineorder.error;


import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;


import java.io.IOException;
import java.util.List;


/**
 * ApiExceptionHandler's answer to a response body that could not be written, without a Spring context: a client that
 * went away gets one DEBUG line and no error body, any other write failure is rethrown as it is so the default
 * resolver answers 500 as before.
 */
class ApiExceptionHandlerDisconnectedClientTests {

    private static final String BROKEN_PIPE_MESSAGE =
            "Could not write JSON: ServletOutputStream failed to write: java.io.IOException: Broken pipe";

    private final ApiErrorResponseFactory errorResponseFactory = Mockito.mock(ApiErrorResponseFactory.class);
    private final ApiExceptionHandler handler = new ApiExceptionHandler(errorResponseFactory);
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/merchant/orders");

    private Logger handlerLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> captured;


    @BeforeEach
    void captureHandlerLog() {
        // The dispatcher sets the matched route template before a handler runs; the log names it, not the raw URI.
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/merchant/orders");
        handlerLogger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
        originalLevel = handlerLogger.getLevel();
        handlerLogger.setLevel(Level.DEBUG);
        captured = new ListAppender<>();
        captured.start();
        handlerLogger.addAppender(captured);
    }


    @AfterEach
    void restoreHandlerLog() {
        handlerLogger.detachAppender(captured);
        captured.stop();
        handlerLogger.setLevel(originalLevel);
    }


    @Test
    void handleNotWritable_brokenPipe_logsOneDebugLineAndWritesNothing() {
        HttpMessageNotWritableException ex =
                new HttpMessageNotWritableException(BROKEN_PIPE_MESSAGE, new IOException("Broken pipe"));

        Assertions.assertDoesNotThrow(() -> handler.handleNotWritable(ex, request));

        Mockito.verifyNoInteractions(errorResponseFactory);
        List<ILoggingEvent> events = captured.list;
        Assertions.assertEquals(1, events.size(), "exactly one log event, got " + events);
        ILoggingEvent event = events.get(0);
        Assertions.assertEquals(Level.DEBUG, event.getLevel());
        Assertions.assertTrue(
                event.getFormattedMessage().contains("GET /merchant/orders"),
                "names the request: " + event.getFormattedMessage()
        );
        Assertions.assertTrue(
                event.getFormattedMessage().contains(ex.toString()),
                "carries the exception's toString: " + event.getFormattedMessage()
        );
        Assertions.assertNull(event.getThrowableProxy(), "no stack trace");
        Assertions.assertTrue(
                events.stream().noneMatch(logged -> logged.getLevel().isGreaterOrEqual(Level.WARN)),
                "nothing at WARN or above: " + events
        );
    }


    @Test
    void handleNotWritable_brokenPipeNestedDeeper_returnsNormally() {
        HttpMessageNotWritableException ex = new HttpMessageNotWritableException(
                BROKEN_PIPE_MESSAGE,
                new RuntimeException("write failed", new IOException("Broken pipe"))
        );

        Assertions.assertDoesNotThrow(() -> handler.handleNotWritable(ex, request));

        Mockito.verifyNoInteractions(errorResponseFactory);
        Assertions.assertEquals(1, captured.list.size(), "one DEBUG line, got " + captured.list);
        Assertions.assertEquals(Level.DEBUG, captured.list.get(0).getLevel());
    }


    @Test
    void handleNotWritable_otherWriteFailure_rethrowsTheSameInstance() {
        HttpMessageNotWritableException ex =
                new HttpMessageNotWritableException("Could not write JSON: boom", new IllegalStateException("boom"));

        HttpMessageNotWritableException thrown = Assertions.assertThrows(
                HttpMessageNotWritableException.class,
                () -> handler.handleNotWritable(ex, request)
        );

        Assertions.assertSame(ex, thrown);
        Mockito.verifyNoInteractions(errorResponseFactory);
        Assertions.assertTrue(captured.list.isEmpty(), "logs nothing for a real serialization failure: " + captured.list);
    }
}
