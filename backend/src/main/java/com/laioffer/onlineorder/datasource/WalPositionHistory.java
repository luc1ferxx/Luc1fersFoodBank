package com.laioffer.onlineorder.datasource;


import java.util.ArrayDeque;
import java.util.Iterator;


/**
 * The primary's WAL positions as the lag monitor saw them over time, used to turn a replica's replay position into a
 * lag in seconds on this instance's clock.
 *
 * <p>Each sample says "the primary had written up to this LSN at this time". A replica that has replayed at least the
 * newest position is caught up (lag 0). Otherwise the first sample beyond its replay position is the oldest moment the
 * replica is known to be missing: its data is older than that sample, so {@code now - sample time} is a lower bound
 * of its lag, short of the true lag by at most one check interval.
 *
 * <p>This avoids the usual {@code now() - pg_last_xact_replay_timestamp()}, which needs the two servers' clocks in
 * step and reports an idle primary's replica as minutes behind whenever some WAL without a commit (vacuum, checkpoint)
 * has not been replayed yet. A replica behind every remembered sample has no upper bound on its lag and is never
 * routable; the samples go back {@code capacity} changes of the primary's position.
 */
final class WalPositionHistory {

    private final int capacity;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();


    WalPositionHistory(int capacity) {
        this.capacity = Math.max(2, capacity);
    }


    /**
     * Records the primary's position. An unchanged position keeps the time it was first seen; a position lower than
     * the last one (the database was replaced or restored) starts the history over.
     */
    synchronized void record(long nanoTime, long lsn) {
        Sample last = samples.peekLast();
        if (last != null && lsn == last.lsn()) {
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
     * @return the replica's lag at {@code nowNanos}, or null before the primary's position was ever recorded
     */
    synchronized Lag lag(long replayLsn, long nowNanos) {
        Sample newest = samples.peekLast();
        if (newest == null) {
            return null;
        }
        if (replayLsn >= newest.lsn()) {
            return new Lag(0.0, true);
        }
        // Walk back from the newest sample to the oldest one the replica is still missing.
        Iterator<Sample> newestFirst = samples.descendingIterator();
        Sample oldestMissing = newestFirst.next();
        boolean bounded = false;
        while (newestFirst.hasNext()) {
            Sample sample = newestFirst.next();
            if (sample.lsn() <= replayLsn) {
                bounded = true;
                break;
            }
            oldestMissing = sample;
        }
        double seconds = Math.max(0L, nowNanos - oldestMissing.nanoTime()) / 1_000_000_000.0;
        return new Lag(seconds, bounded);
    }


    private record Sample(long nanoTime, long lsn) {
    }


    /**
     * @param seconds the lag, or a lower bound of it when {@code bounded} is false
     * @param bounded false when the replica is behind every remembered sample
     */
    record Lag(double seconds, boolean bounded) {
    }
}
