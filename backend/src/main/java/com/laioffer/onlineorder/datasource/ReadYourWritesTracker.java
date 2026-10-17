package com.laioffer.onlineorder.datasource;


import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;


import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;


/**
 * Read-your-writes for replica routing: once a request commits a write transaction, the reads of that user's session
 * go to the primary for {@code app.datasource.replica.sticky-window}, so a replica that has not replayed the write yet
 * can never answer them.
 *
 * <p>The time of the last committed write is kept in the HTTP session, which lives in Redis (Spring Session), so the
 * window holds on every instance the user's next requests reach. It is also kept on the request itself, which covers
 * reads later in the same request and writes of requests without a session (sign-up, a failed login). Work outside an
 * HTTP request (Kafka listeners, pollers) has no session and is unaffected; it writes, and writes always use the
 * primary.
 *
 * <p>The session store must not become a dependency of database reads: looking the session up is a Redis call for a
 * request that carries a session cookie but has not loaded its session yet (an anonymous catalog read by a signed-in
 * user). If that lookup fails, the request's reads use the primary, which is always fresh, and the request does not
 * look again (one timeout per request, not per read); a write whose window cannot be stored in the session still
 * succeeds and keeps its own request on the primary.
 *
 * <p>Registered as a {@link TransactionExecutionListener}: Spring Boot adds every such bean to the transaction manager,
 * which calls {@link #afterCommit} once per committed physical transaction. Writes outside a transaction (none in this
 * code base) would not start the window.
 */
public class ReadYourWritesTracker implements TransactionExecutionListener {

    static final String LAST_WRITE_ATTRIBUTE = ReadYourWritesTracker.class.getName() + ".LAST_WRITE";
    /** Set on a request whose session could not be looked up: its reads use the primary without trying again. */
    static final String SESSION_UNAVAILABLE_ATTRIBUTE = ReadYourWritesTracker.class.getName() + ".SESSION_UNAVAILABLE";

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadYourWritesTracker.class);
    private static final long WARNING_INTERVAL_MILLIS = 60_000L;
    private static final AtomicLong NEXT_WARNING_MILLIS = new AtomicLong();

    private final long windowMillis;
    private final LongSupplier clock;


    public ReadYourWritesTracker(Duration window) {
        this(window, System::currentTimeMillis);
    }


    ReadYourWritesTracker(Duration window, LongSupplier clock) {
        this.windowMillis = Math.max(0L, window.toMillis());
        this.clock = clock;
    }


    @Override
    public void afterCommit(TransactionExecution transaction, Throwable commitFailure) {
        if (commitFailure == null && !transaction.isReadOnly()) {
            recordWrite();
        }
    }


    /**
     * Starts the window for the current request and its session; a no-op outside an HTTP request. Never throws: it runs
     * after the commit, and a session store that cannot be reached must not turn a committed write into an error.
     */
    void recordWrite() {
        HttpServletRequest request = currentRequest();
        if (windowMillis == 0 || request == null) {
            return;
        }
        Long now = clock.getAsLong();
        request.setAttribute(LAST_WRITE_ATTRIBUTE, now);
        HttpSession session = existingSession(request);
        if (session == null) {
            return;
        }
        try {
            session.setAttribute(LAST_WRITE_ATTRIBUTE, now);
        } catch (IllegalStateException invalidated) {
            // Logout invalidated the session in this request; there is nothing left to keep sticky.
        } catch (RuntimeException storeFailure) {
            warnRateLimited("Could not record the read-your-writes window in the session: {}", storeFailure);
        }
    }


    /**
     * True while the current request, or an earlier request of its session, committed a write less than the window ago,
     * and when the session cannot be read (the safe answer: the primary has every write).
     */
    public boolean readsMustUsePrimary() {
        HttpServletRequest request = currentRequest();
        if (windowMillis == 0 || request == null) {
            return false;
        }
        Long lastWrite = asLong(request.getAttribute(LAST_WRITE_ATTRIBUTE));
        if (lastWrite == null) {
            HttpSession session = existingSession(request);
            if (request.getAttribute(SESSION_UNAVAILABLE_ATTRIBUTE) != null) {
                return true;
            }
            if (session != null) {
                try {
                    lastWrite = asLong(session.getAttribute(LAST_WRITE_ATTRIBUTE));
                } catch (IllegalStateException invalidated) {
                    lastWrite = null;
                }
            }
        }
        return lastWrite != null && clock.getAsLong() - lastWrite < windowMillis;
    }


    /**
     * The request's session, or null. A lookup the session store fails (Redis down or hung) is remembered on the
     * request, so the rest of the request neither waits for it again nor reads a replica.
     */
    private static HttpSession existingSession(HttpServletRequest request) {
        if (request.getAttribute(SESSION_UNAVAILABLE_ATTRIBUTE) != null) {
            return null;
        }
        try {
            return request.getSession(false);
        } catch (RuntimeException storeFailure) {
            request.setAttribute(SESSION_UNAVAILABLE_ATTRIBUTE, Boolean.TRUE);
            warnRateLimited("Session lookup failed; the request's reads use the primary: {}", storeFailure);
            return null;
        }
    }


    /**
     * During a session store outage every request with a session cookie fails its lookup: one warning a minute.
     */
    private static void warnRateLimited(String message, RuntimeException failure) {
        long now = System.currentTimeMillis();
        long next = NEXT_WARNING_MILLIS.get();
        if (now >= next && NEXT_WARNING_MILLIS.compareAndSet(next, now + WARNING_INTERVAL_MILLIS)) {
            LOGGER.warn(message, failure.toString());
        } else {
            LOGGER.debug(message, failure.toString());
        }
    }


    private static Long asLong(Object value) {
        return value instanceof Long time ? time : null;
    }


    /**
     * The HTTP request the current thread serves, or null outside one.
     */
    static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }
}
