package com.laioffer.onlineorder.cache;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.serializer.SerializationException;


import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;


/**
 * Keeps a down, hung or very slow Redis off the cache read path. Without it, every L1 miss waits for the
 * Redis read (and the write-back) to fail, up to {@code spring.data.redis.timeout} each, and a response
 * composed of N cached entries pays that N times.
 * <ul>
 *     <li>Closed: reads and write-backs go to Redis.</li>
 *     <li>Any Redis failure, or {@value #SLOW_CALLS_TO_OPEN} consecutive calls slower than the slow-call
 *     threshold, opens the breaker. While it is open, reads skip Redis (L1, then the database) and
 *     write-backs are dropped.</li>
 *     <li>When the open period ends, one caller probes Redis while everyone else keeps skipping it. A
 *     successful probe closes the breaker; a failed one starts another open period.</li>
 * </ul>
 * An entry that cannot be deserialized is not a Redis failure: Redis answered, so it does not open the
 * breaker. Deletes, clears, explicit puts and invalidation broadcasts are never skipped, because they keep
 * Redis consistent with the database and are rare; their outcomes still feed the breaker.
 * <p>
 * One breaker is shared by all caches on the same Redis.
 */
public class RemoteCacheCircuitBreaker {

    static final int SLOW_CALLS_TO_OPEN = 3;

    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteCacheCircuitBreaker.class);
    private static final State CLOSED = new State(false, 0L);

    private final Duration openDuration;
    private final long openNanos;
    private final long slowCallNanos;
    private final LongSupplier nanoClock;
    private final AtomicReference<State> state = new AtomicReference<>(CLOSED);
    private final AtomicInteger consecutiveSlowCalls = new AtomicInteger();


    /**
     * @param openDuration      how long Redis is skipped after it fails; zero or negative disables the breaker
     * @param slowCallThreshold a call taking at least this long counts as slow; zero or negative disables
     *                          slow-call detection
     */
    public RemoteCacheCircuitBreaker(Duration openDuration, Duration slowCallThreshold) {
        this(openDuration, slowCallThreshold, System::nanoTime);
    }


    RemoteCacheCircuitBreaker(Duration openDuration, Duration slowCallThreshold, LongSupplier nanoClock) {
        this.openDuration = openDuration;
        this.openNanos = Math.max(0, openDuration.toNanos());
        this.slowCallNanos = Math.max(0, slowCallThreshold.toNanos());
        this.nanoClock = nanoClock;
    }


    /**
     * A breaker that never opens.
     */
    public static RemoteCacheCircuitBreaker disabled() {
        return new RemoteCacheCircuitBreaker(Duration.ZERO, Duration.ZERO);
    }


    /**
     * Whether a skippable Redis call (a read or a write-back) should be made now. Returns true while closed,
     * false while open, and true for exactly one caller once the open period has ended (the probe).
     */
    public boolean allowRequest() {
        State current = state.get();
        if (!current.open()) {
            return true;
        }
        long now = nanoClock.getAsLong();
        if (now - current.retryAtNanos() < 0) {
            return false;
        }
        // Only the caller that wins the swap probes. It also starts the next open period, so if the probe
        // hangs or never reports back, another caller probes once that period ends.
        return state.compareAndSet(current, new State(true, now + openNanos));
    }


    public void onSuccess(long elapsedNanos) {
        if (slowCallNanos > 0 && elapsedNanos >= slowCallNanos) {
            int slowCalls = consecutiveSlowCalls.incrementAndGet();
            if (slowCalls >= SLOW_CALLS_TO_OPEN) {
                open(slowCalls + " consecutive Redis calls took longer than " + Duration.ofNanos(slowCallNanos));
            }
            return;
        }
        consecutiveSlowCalls.set(0);
        State current = state.get();
        if (current.open() && state.compareAndSet(current, CLOSED)) {
            LOGGER.info("Redis cache calls succeed again; reading the L2 cache again");
        }
    }


    public void onError(RuntimeException exception, long elapsedNanos) {
        if (exception instanceof SerializationException) {
            onSuccess(elapsedNanos);
            return;
        }
        open(exception.toString());
    }


    public boolean isOpen() {
        return state.get().open();
    }


    /**
     * Closes the breaker and forgets earlier slow calls.
     */
    public void reset() {
        consecutiveSlowCalls.set(0);
        state.set(CLOSED);
    }


    private void open(String reason) {
        if (openNanos == 0) {
            return;
        }
        consecutiveSlowCalls.set(0);
        State previous = state.getAndSet(new State(true, nanoClock.getAsLong() + openNanos));
        if (!previous.open()) {
            LOGGER.warn("Skipping the Redis cache for {} after: {}. Reads are served from L1 and the database.", openDuration, reason);
        }
    }


    private record State(boolean open, long retryAtNanos) {
    }
}
