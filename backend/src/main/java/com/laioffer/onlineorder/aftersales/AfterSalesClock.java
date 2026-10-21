package com.laioffer.onlineorder.aftersales;


import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.Supplier;


/**
 * The after-sales module's time, in microseconds: what its timestamp columns keep, as for an order's transition. A
 * Linux clock has nanoseconds, which the database rounds when it stores them, so a time taken in full would be up to a
 * microsecond off the row it wrote, both in the event that carries it (occurred_at, data.opened_at, data.deadline_at)
 * and in what the module keeps of the row (a refund's lease end). Every service of the module reads its clock, the
 * system's or a test's, through {@link #inMicros(Supplier)}; the single writer also truncates the time a caller hands
 * it.
 */
final class AfterSalesClock {

    private AfterSalesClock() {
    }


    /**
     * {@code clock}, read in microseconds.
     */
    static Supplier<LocalDateTime> inMicros(Supplier<LocalDateTime> clock) {
        Objects.requireNonNull(clock, "clock");
        return () -> inMicros(clock.get());
    }


    /**
     * {@code time} without what is below the microsecond.
     */
    static LocalDateTime inMicros(LocalDateTime time) {
        return time.truncatedTo(ChronoUnit.MICROS);
    }
}
