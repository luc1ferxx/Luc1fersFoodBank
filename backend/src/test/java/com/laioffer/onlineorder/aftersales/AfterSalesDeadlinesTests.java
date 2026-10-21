package com.laioffer.onlineorder.aftersales;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;


/**
 * The after-sales time limits of ADR 0002 section 6: the deadline each status gets when a case enters it, the
 * request window, and positive settings only.
 */
class AfterSalesDeadlinesTests {

    private static final LocalDateTime ENTERED = LocalDateTime.of(2026, 10, 21, 12, 0, 30);


    @Test
    void deadlineAt_shouldBeTheTimeoutOfTheOpenStatusAndNoneOtherwise() {
        AfterSalesDeadlines deadlines = new AfterSalesDeadlines(Duration.ofHours(2), Duration.ofMinutes(2),
                Duration.ofMinutes(3), Duration.ofMinutes(5));
        Map<AfterSalesStatus, LocalDateTime> expected = new EnumMap<>(AfterSalesStatus.class);
        expected.put(AfterSalesStatus.PENDING_MERCHANT, ENTERED.plusMinutes(2));
        expected.put(AfterSalesStatus.COUNTER_OFFERED, ENTERED.plusMinutes(3));
        expected.put(AfterSalesStatus.REJECTED, ENTERED.plusMinutes(3));
        expected.put(AfterSalesStatus.IN_ARBITRATION, ENTERED.plusMinutes(5));

        for (AfterSalesStatus status : AfterSalesStatus.values()) {
            Assertions.assertEquals(expected.get(status), deadlines.deadlineAt(status, ENTERED), status.name());
            Assertions.assertEquals(status.isOpen(), deadlines.deadlineAt(status, ENTERED) != null, status.name());
        }
        Assertions.assertEquals(Duration.ofHours(2), deadlines.requestWindow());
        Assertions.assertEquals(ENTERED.plusHours(2), deadlines.requestDeadline(ENTERED));
        Assertions.assertThrows(NullPointerException.class,
                () -> deadlines.deadlineAt(AfterSalesStatus.CLOSED, null));
    }


    @Test
    void settings_shouldBePositive() {
        Duration hour = Duration.ofHours(1);
        for (Duration invalid : new Duration[]{null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new AfterSalesDeadlines(invalid, hour, hour, hour));
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new AfterSalesDeadlines(hour, invalid, hour, hour));
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new AfterSalesDeadlines(hour, hour, invalid, hour));
            IllegalArgumentException refused = Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new AfterSalesDeadlines(hour, hour, hour, invalid));
            Assertions.assertEquals("app.after-sales.arbitration-timeout must be positive, got " + invalid,
                    refused.getMessage());
        }
    }
}
