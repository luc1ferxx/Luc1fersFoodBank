package com.laioffer.onlineorder.service;


/**
 * Hands out the sequence of the next outbox event of an aggregate: 1 for its first event, then one more than the last
 * one. Runs in the transaction that writes the event and keeps the aggregate's counter row locked until it ends, so:
 * <ul>
 *     <li>two transactions writing events of the same aggregate, whatever its type and whatever else they lock, get
 *     different sequences and commit in sequence order (the second waits for the first);</li>
 *     <li>a rolled-back transaction gives its sequence back, so an aggregate's sequences have no gaps.</li>
 * </ul>
 * The counter lives in its own table rather than being derived from the outbox: published outbox rows are deleted
 * after app.cleanup.outbox-retention, and a counter derived from what is left would start again at 1.
 */
public interface OutboxSequenceAllocator {

    long next(String aggregateType, Long aggregateId);
}
