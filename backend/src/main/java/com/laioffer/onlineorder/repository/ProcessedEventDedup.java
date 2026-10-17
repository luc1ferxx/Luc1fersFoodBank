package com.laioffer.onlineorder.repository;


import java.time.LocalDateTime;


/**
 * The deduplication mark of an at-least-once consumer: one processed_events row per (consumer_name, dedup_key).
 * Implemented in Java ({@link ProcessedEventDedupImpl}) because the guarantee is built differently on the two
 * databases; see there.
 */
public interface ProcessedEventDedup {

    /**
     * Inserts the mark unless (consumer_name, dedup_key) is already present and returns the rows inserted: 1 or 0.
     * <p>
     * Call it in the transaction of the work it guards, so the mark commits or rolls back with that work. A second
     * call with the same key from another transaction waits until the first one has committed or rolled back, then
     * returns 0 or 1 accordingly, the same way a unique constraint would behave. The transaction must use READ
     * COMMITTED (the default).
     */
    int insertIfAbsent(
            String consumerName,
            String eventId,
            String dedupKey,
            String eventType,
            String aggregateType,
            Long aggregateId,
            LocalDateTime processedAt
    );
}
