package com.laioffer.onlineorder.datasource;


import java.util.ArrayDeque;
import java.util.Iterator;


/**
 * The primary's WAL positions as the lag monitor saw them over time, used to turn a replica's replay position into how
 * old its data may be, on this instance's clock.
 *
 * <p>Each sample says "the primary was at this LSN when the monitor asked", with the time taken just before asking:
 * every transaction committed before that time has its commit record at or below that LSN. A replica that has replayed
 * up to some LSN therefore has every transaction committed before the last time the primary was seen at or below that
 * LSN ({@link Freshness#bounded() bounded} freshness). Its data is at most {@code now - that time} old, whatever the
 * monitor does afterwards: the time does not move unless the replica replays more.
 *
 * <p>This avoids the usual {@code now() - pg_last_xact_replay_timestamp()}, which needs the two servers' clocks in
 * step and reports an idle primary's replica as minutes behind whenever some WAL without a commit (vacuum, checkpoint)
 * has not been replayed yet. A replica behind every remembered sample has no bound at all and is never routable; the
 * samples go back {@code capacity} changes of the primary's position.
 */
final class WalPositionHistory {

    private final int capacity;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();


    WalPositionHistory(int capacity) {
        this.capacity = Math.max(2, capacity);
    }


    /**
     * Records the primary's position. An unchanged position keeps the time it was first seen and moves the time it was
     * last seen; a position lower than the last one (the database was replaced or restored) starts the history over.
     * Callers record in time order.
     *
     * @param nanoTime {@link System#nanoTime()} taken just before the position was queried
     */
    synchronized void record(long nanoTime, long lsn) {
        Sample last = samples.peekLast();
        if (last != null && lsn == last.lsn()) {
            last.lastSeen = nanoTime;
            return;
        }
        if (last != null && lsn < last.lsn()) {
            samples.clear();
        }
        samples.addLast(new Sample(nanoTime, lsn));
        while (samples.size() > capacity) {
            samples.removeFirst();
        }
    }


    /**
     * How old a replica's data may be, given its replay position.
     *
     * @return null before the primary's position was ever recorded
     */
    synchronized Freshness freshness(long replayLsn) {
        Sample newest = samples.peekLast();
        if (newest == null) {
            return null;
        }
        // Walk back from the newest sample to the newest one the replica has replayed.
        Iterator<Sample> newestFirst = samples.descendingIterator();
        Sample oldest = newest;
        while (newestFirst.hasNext()) {
            Sample sample = newestFirst.next();
            if (sample.lsn() <= replayLsn) {
                return new Freshness(sample.lastSeen, true);
            }
            oldest = sample;
        }
        // Behind everything remembered: older than the oldest sample, by an unknown amount.
        return new Freshness(oldest.firstSeen, false);
    }


    private static final class Sample {

        private final long firstSeen;
        private final long lsn;
        private long lastSeen;


        private Sample(long nanoTime, long lsn) {
            this.firstSeen = nanoTime;
            this.lastSeen = nanoTime;
            this.lsn = lsn;
        }


        private long lsn() {
            return lsn;
        }
    }


    /**
     * @param asOfNanos when {@code bounded}, a {@link System#nanoTime()} before which every transaction the primary
     *                  committed is on the replica, so its data is at most {@code now - asOfNanos} old; otherwise the
     *                  replica's data is older than {@code asOfNanos} by an unknown amount
     * @param bounded   false when the replica is behind every remembered sample
     */
    record Freshness(long asOfNanos, boolean bounded) {

        double ageSeconds(long nowNanos) {
            return Math.max(0L, nowNanos - asOfNanos) / 1_000_000_000.0;
        }
    }
}
