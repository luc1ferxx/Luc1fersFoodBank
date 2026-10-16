package com.laioffer.onlineorder.cache;


import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;


/**
 * Spreads Redis expiry times so entries written together (a cold start, a deploy, a bulk reload) do not
 * all expire in the same instant and send every reader to the database at once (cache avalanche).
 * A ratio of 0.15 turns a 10 minute TTL into a uniformly random TTL in [8.5m, 11.5m].
 */
public final class TtlJitter {

    private final double ratio;
    private final DoubleSupplier random;


    public TtlJitter(double ratio) {
        this(ratio, () -> ThreadLocalRandom.current().nextDouble());
    }


    TtlJitter(double ratio, DoubleSupplier random) {
        if (Double.isNaN(ratio) || ratio < 0 || ratio >= 1) {
            throw new IllegalArgumentException("TTL jitter ratio must be in [0, 1): " + ratio);
        }
        this.ratio = ratio;
        this.random = random;
    }


    public double ratio() {
        return ratio;
    }


    /**
     * Returns {@code base} scaled by a random factor in [1 - ratio, 1 + ratio], never shorter than 1ms.
     */
    public Duration apply(Duration base) {
        if (ratio == 0 || base.isZero() || base.isNegative()) {
            return base;
        }
        double factor = 1 + ratio * (2 * random.getAsDouble() - 1);
        long millis = Math.round(base.toMillis() * factor);
        return Duration.ofMillis(Math.max(1, millis));
    }
}
