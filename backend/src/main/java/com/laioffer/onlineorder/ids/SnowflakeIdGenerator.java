package com.laioffer.onlineorder.ids;


import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;


/**
 * Time-ordered 64-bit ids in the Snowflake layout, generated without a database round trip:
 *
 * <pre>
 *  63 | 62 ............... 22 | 21 ....... 12 | 11 ....... 0
 *   0 | milliseconds since    | worker id     | sequence
 *     | 2020-01-01T00:00Z (41)| (10)          | (12)
 * </pre>
 *
 * <p>Ids are unique as long as no two generators run with the same worker id at the same time, and they increase
 * strictly within one generator. 41 bits of milliseconds last until 2089. Worker ids 0-1022 belong to running
 * instances ({@link #MAX_ASSIGNABLE_WORKER_ID}); 1023 is reserved for the order numbers that migration V10_1 backfilled
 * ({@link #BACKFILL_WORKER_ID}), so a live instance can never produce one of them.
 *
 * <p>The sequence of each millisecond starts at a random value below 2048 instead of 0, so neighbouring ids do not
 * differ by one and the low bits do not reveal how many ids a worker issued in that millisecond. That leaves at least
 * 2048 ids per millisecond per worker; the 2049th waits for the next millisecond. The ids are not secrets: the
 * timestamp is readable, and every API still checks that the caller may see the order.
 *
 * <p>Clock moving backwards (NTP step, VM migration, a leap-second smear on another host): the generator never reuses
 * a timestamp it already used. A step back of at most {@code maxClockBackward} is waited out; a larger one is
 * refused with {@link ClockMovedBackwardsException} until the clock has caught up, instead of risking a duplicate.
 *
 * <p>Fencing for leased worker ids ({@link WorkerIdLeaseManager}): the generator starts after a floor (the last
 * timestamp the previous holder of the worker id may have used) and never issues an id whose timestamp is past its
 * ceiling (the bound the current holder has recorded in the database). Two holders of one worker id therefore use
 * disjoint timestamp ranges even if their leases overlap in real time.
 */
public final class SnowflakeIdGenerator {

    /**
     * 2020-01-01T00:00:00Z.
     */
    public static final long EPOCH_MILLIS = 1_577_836_800_000L;
    public static final int TIMESTAMP_BITS = 41;
    public static final int WORKER_ID_BITS = 10;
    public static final int SEQUENCE_BITS = 12;
    public static final int MAX_WORKER_ID = (1 << WORKER_ID_BITS) - 1;
    /**
     * Worker id of the order numbers V10_1 assigned to orders placed before order numbers existed.
     */
    public static final int BACKFILL_WORKER_ID = MAX_WORKER_ID;
    /**
     * The highest worker id an instance may run with (1022).
     */
    public static final int MAX_ASSIGNABLE_WORKER_ID = MAX_WORKER_ID - 1;
    public static final long MAX_ELAPSED_MILLIS = (1L << TIMESTAMP_BITS) - 1;
    public static final int MAX_SEQUENCE = (1 << SEQUENCE_BITS) - 1;
    /**
     * Exclusive upper bound of the first sequence value of a millisecond.
     */
    static final int SEQUENCE_START_BOUND = 1 << (SEQUENCE_BITS - 1);
    static final Duration DEFAULT_MAX_CLOCK_BACKWARD = Duration.ofMillis(50);

    private final int workerId;
    private final long maxBackwardMillis;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final IntSupplier sequenceStart;
    private final Listener listener;

    // Guarded by this.
    private long lastTimestamp;
    private int sequence;
    private long ceilingMillis;
    private boolean closed;


    /**
     * A generator on the system clock with no floor and no ceiling.
     */
    public SnowflakeIdGenerator(int workerId, Duration maxClockBackward, Listener listener) {
        this(workerId, 0L, Long.MAX_VALUE, maxClockBackward.toMillis(), System::currentTimeMillis,
                Sleeper.THREAD_SLEEP, SnowflakeIdGenerator::randomSequenceStart, listener);
    }


    /**
     * @param startAfterMillis  the first id's timestamp (epoch milliseconds) is greater than this; 0 for none
     * @param ceilingMillis     no id gets a later timestamp (epoch milliseconds); Long.MAX_VALUE for none
     * @param maxBackwardMillis the largest backward clock step that is waited out instead of refused
     * @param clock             wall-clock time in epoch milliseconds
     * @param sequenceStart     the first sequence value of a millisecond, below {@link #SEQUENCE_START_BOUND}
     */
    SnowflakeIdGenerator(
            int workerId,
            long startAfterMillis,
            long ceilingMillis,
            long maxBackwardMillis,
            LongSupplier clock,
            Sleeper sleeper,
            IntSupplier sequenceStart,
            Listener listener
    ) {
        if (workerId < 0 || workerId > MAX_ASSIGNABLE_WORKER_ID) {
            throw new IllegalArgumentException(
                    "Snowflake worker id must be between 0 and " + MAX_ASSIGNABLE_WORKER_ID + ", got " + workerId
            );
        }
        this.workerId = workerId;
        this.maxBackwardMillis = Math.max(0L, maxBackwardMillis);
        this.clock = clock;
        this.sleeper = sleeper;
        this.sequenceStart = sequenceStart;
        this.listener = listener == null ? Listener.NONE : listener;
        this.lastTimestamp = Math.max(0L, startAfterMillis);
        this.ceilingMillis = ceilingMillis;
        // The floor itself is taken: the next id needs a later millisecond.
        this.sequence = MAX_SEQUENCE;
    }


    public int workerId() {
        return workerId;
    }


    /**
     * @throws ClockMovedBackwardsException    when the clock is behind the last id by more than the tolerance
     * @throws IdGeneratorUnavailableException when the generator is closed or the clock is past the ceiling
     */
    public synchronized long nextId() {
        if (closed) {
            throw new IdGeneratorUnavailableException("The Snowflake generator of worker " + workerId + " is closed");
        }
        long now = clock.getAsLong();
        if (now < lastTimestamp) {
            now = waitForClock(now);
        }
        int nextSequence;
        if (now == lastTimestamp) {
            if (sequence >= MAX_SEQUENCE) {
                now = awaitMillisAfter(lastTimestamp);
                nextSequence = sequenceStart.getAsInt();
            } else {
                nextSequence = sequence + 1;
            }
        } else {
            nextSequence = sequenceStart.getAsInt();
        }
        if (now > ceilingMillis) {
            throw new IdGeneratorUnavailableException(
                    "The clock is past the issued-until bound of worker " + workerId + "'s lease; waiting for a renewal"
            );
        }
        long elapsed = now - EPOCH_MILLIS;
        if (elapsed < 0 || elapsed > MAX_ELAPSED_MILLIS) {
            throw new IllegalStateException("The clock (" + now + ") is outside the Snowflake timestamp range");
        }
        lastTimestamp = now;
        sequence = nextSequence;
        return compose(elapsed, workerId, nextSequence);
    }


    /**
     * Lets the generator issue ids up to {@code ceiling} (epoch milliseconds). A lower value than the current one is
     * ignored: ids up to the current ceiling may already exist.
     */
    public synchronized void raiseCeiling(long ceiling) {
        ceilingMillis = Math.max(ceilingMillis, ceiling);
    }


    public synchronized long ceilingMillis() {
        return ceilingMillis;
    }


    /**
     * The timestamp (epoch milliseconds) of the last id issued, or the floor while none was.
     */
    public synchronized long lastTimestamp() {
        return lastTimestamp;
    }


    /**
     * Stops issuing ids.
     *
     * @return the timestamp of the last id issued, or the floor: no id issued by this generator is later
     */
    public synchronized long close() {
        closed = true;
        return lastTimestamp;
    }


    private long waitForClock(long now) {
        long behind = lastTimestamp - now;
        if (behind > maxBackwardMillis) {
            listener.clockMovedBackwards(behind, false);
            throw new ClockMovedBackwardsException(workerId, behind, maxBackwardMillis);
        }
        listener.clockMovedBackwards(behind, true);
        sleep(behind);
        long caughtUp = clock.getAsLong();
        if (caughtUp < lastTimestamp) {
            throw new ClockMovedBackwardsException(workerId, lastTimestamp - caughtUp, maxBackwardMillis);
        }
        return caughtUp;
    }


    /**
     * The sequence of {@code timestamp} is used up: waits for the next millisecond.
     */
    private long awaitMillisAfter(long timestamp) {
        long now = clock.getAsLong();
        while (now <= timestamp) {
            if (timestamp - now > maxBackwardMillis) {
                throw new ClockMovedBackwardsException(workerId, timestamp - now, maxBackwardMillis);
            }
            Thread.onSpinWait();
            now = clock.getAsLong();
        }
        return now;
    }


    private void sleep(long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IdGeneratorUnavailableException("Interrupted while waiting for the clock to catch up", ex);
        }
    }


    private static int randomSequenceStart() {
        return ThreadLocalRandom.current().nextInt(SEQUENCE_START_BOUND);
    }


    /**
     * @param elapsedMillis milliseconds since {@link #EPOCH_MILLIS}
     */
    public static long compose(long elapsedMillis, int workerId, int sequence) {
        if (elapsedMillis < 0 || elapsedMillis > MAX_ELAPSED_MILLIS) {
            throw new IllegalArgumentException("Timestamp out of range: " + elapsedMillis);
        }
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("Worker id out of range: " + workerId);
        }
        if (sequence < 0 || sequence > MAX_SEQUENCE) {
            throw new IllegalArgumentException("Sequence out of range: " + sequence);
        }
        return (elapsedMillis << (WORKER_ID_BITS + SEQUENCE_BITS))
                | ((long) workerId << SEQUENCE_BITS)
                | sequence;
    }


    /**
     * @return the epoch milliseconds the id was generated at
     */
    public static long timestampMillisOf(long id) {
        return (id >>> (WORKER_ID_BITS + SEQUENCE_BITS)) + EPOCH_MILLIS;
    }


    public static int workerIdOf(long id) {
        return (int) ((id >>> SEQUENCE_BITS) & MAX_WORKER_ID);
    }


    public static int sequenceOf(long id) {
        return (int) (id & MAX_SEQUENCE);
    }


    @FunctionalInterface
    public interface Sleeper {

        Sleeper THREAD_SLEEP = Thread::sleep;

        void sleep(long millis) throws InterruptedException;
    }


    @FunctionalInterface
    public interface Listener {

        Listener NONE = (millis, waited) -> {
        };

        /**
         * @param millis how far the clock was behind the last id
         * @param waited true when the generator waited for the clock, false when it refused
         */
        void clockMovedBackwards(long millis, boolean waited);
    }
}
