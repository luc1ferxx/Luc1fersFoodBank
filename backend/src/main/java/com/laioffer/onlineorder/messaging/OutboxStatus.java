package com.laioffer.onlineorder.messaging;


/**
 * The status of an outbox_events row, which the CHECK chk_outbox_events_status (V13) limits to these names. An event
 * is inserted PENDING; a publisher's claim makes it PROCESSING under the claim's token; the claim's outcome makes it
 * PUBLISHED, PENDING again (a release or a retryable failure) or FAILED (out of attempts); an operator retries a FAILED
 * event (PENDING) or abandons it (ABANDONED, never published).
 *
 * <p>Some queries of {@code OutboxEventRepository} and the partial indexes of V3 and V9_1 spell statuses as SQL
 * literals, so that PostgreSQL can match the indexes; {@code OutboxStatusLiteralsTests} checks that they are these
 * names.
 */
public enum OutboxStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    FAILED,
    ABANDONED
}
