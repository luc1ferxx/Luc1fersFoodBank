package com.laioffer.onlineorder.cache;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


import java.time.Duration;
import java.util.LongSummaryStatistics;
import java.util.stream.IntStream;


class TtlJitterTests {

    private static final Duration BASE = Duration.ofMinutes(10);


    @Test
    void apply_shouldStayWithinPlusOrMinusTheRatio() {
        TtlJitter jitter = new TtlJitter(0.15);

        LongSummaryStatistics millis = IntStream.range(0, 20_000)
                .mapToLong(i -> jitter.apply(BASE).toMillis())
                .summaryStatistics();

        Assertions.assertTrue(millis.getMin() >= Duration.ofSeconds(510).toMillis(), "min " + millis.getMin());
        Assertions.assertTrue(millis.getMax() <= Duration.ofSeconds(690).toMillis(), "max " + millis.getMax());
        // Spread across the range rather than clustering on the base TTL.
        Assertions.assertTrue(millis.getMin() < Duration.ofSeconds(530).toMillis(), "min " + millis.getMin());
        Assertions.assertTrue(millis.getMax() > Duration.ofSeconds(670).toMillis(), "max " + millis.getMax());
    }


    @Test
    void apply_shouldMapTheRandomRangeOntoExactBounds() {
        Assertions.assertEquals(Duration.ofSeconds(510), new TtlJitter(0.15, () -> 0.0).apply(BASE));
        Assertions.assertEquals(BASE, new TtlJitter(0.15, () -> 0.5).apply(BASE));
        Assertions.assertEquals(Duration.ofSeconds(690), new TtlJitter(0.15, () -> 1.0).apply(BASE));
    }


    @Test
    void apply_withZeroRatio_shouldKeepTheBaseTtl() {
        Assertions.assertEquals(BASE, new TtlJitter(0).apply(BASE));
    }


    @Test
    void constructor_shouldRejectRatiosOutsideZeroToOne() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new TtlJitter(-0.1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new TtlJitter(1.0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new TtlJitter(Double.NaN));
    }
}
