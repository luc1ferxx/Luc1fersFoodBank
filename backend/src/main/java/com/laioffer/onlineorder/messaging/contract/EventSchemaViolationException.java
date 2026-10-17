package com.laioffer.onlineorder.messaging.contract;


import java.util.List;


/**
 * An event that breaks its published contract (or is not JSON at all).
 *
 * <p>On the producer side it is a bug: the transaction that tried to write the event fails and rolls back. On the
 * consumer side the record can never succeed, so the listener error handler classifies this exception as not
 * retryable and dead-letters the record right away; its message, with the violations, travels to the DLT in the
 * {@code kafka_dlt-exception-message} header and from there to {@code dead_letter_events.error_message}.
 */
public class EventSchemaViolationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    static final int MAX_REPORTED_VIOLATIONS = 10;

    private final transient List<String> violations;


    public EventSchemaViolationException(String subject, List<String> violations) {
        this(subject, violations, null);
    }


    public EventSchemaViolationException(String subject, List<String> violations, Throwable cause) {
        super(message(subject, violations), cause);
        this.violations = List.copyOf(violations);
    }


    public List<String> violations() {
        return violations;
    }


    private static String message(String subject, List<String> violations) {
        List<String> reported = violations.size() > MAX_REPORTED_VIOLATIONS
                ? violations.subList(0, MAX_REPORTED_VIOLATIONS)
                : violations;
        String more = violations.size() > MAX_REPORTED_VIOLATIONS
                ? "; and " + (violations.size() - MAX_REPORTED_VIOLATIONS) + " more"
                : "";
        return subject + " violates its event schema: " + String.join("; ", reported) + more;
    }
}
