package com.laioffer.onlineorder;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;


/**
 * The server runs in UTC: every timestamp is a {@code LocalDateTime} written without an offset, and the browser reads
 * it as UTC. {@code main} sets the zone before Spring starts; the PostgreSQL sessions are covered by
 * {@link ServerTimeZonePostgresTests}.
 */
class ServerTimeZoneTests {


    @Test
    void startup_shouldMakeUtcTheDefaultZoneWhateverTheHostSays() {
        TimeZone defaultZone = TimeZone.getDefault();
        String zoneProperty = System.getProperty("user.timezone");
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            System.setProperty("user.timezone", "America/New_York");

            OnlineOrderApplication.useServerTimeZone();

            Assertions.assertEquals("UTC", TimeZone.getDefault().getID());
            Assertions.assertEquals(ZoneOffset.UTC, ZoneId.systemDefault().normalized());
            Assertions.assertEquals("UTC", System.getProperty("user.timezone"));
            // What the application stores and returns is UTC wall-clock time.
            Duration difference = Duration.between(LocalDateTime.now(), LocalDateTime.now(ZoneOffset.UTC)).abs();
            Assertions.assertTrue(difference.compareTo(Duration.ofSeconds(5)) < 0, "LocalDateTime.now() is off by " + difference);
        } finally {
            TimeZone.setDefault(defaultZone);
            if (zoneProperty == null) {
                System.clearProperty("user.timezone");
            } else {
                System.setProperty("user.timezone", zoneProperty);
            }
        }
    }


    @Test
    void testJvm_shouldRunInUtcLikeTheServer() {
        // Tests do not run main: the Gradle test task starts their JVM with -Duser.timezone=UTC (backend/build.gradle).
        // Run them from an IDE with the same option.
        Assertions.assertEquals("UTC", TimeZone.getDefault().getID(), "The test JVM runs in " + TimeZone.getDefault().getID());
    }
}
