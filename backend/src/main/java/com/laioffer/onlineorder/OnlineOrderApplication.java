package com.laioffer.onlineorder;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;


import java.util.TimeZone;


@SpringBootApplication
@EnableCaching
@EnableScheduling
public class OnlineOrderApplication {


    /** The zone the server runs in, whatever the host, the image or {@code -Duser.timezone} say. */
    static final String SERVER_TIME_ZONE = "UTC";


    public static void main(String[] args) {
        useServerTimeZone();
        SpringApplication.run(OnlineOrderApplication.class, args);
    }


    /**
     * Makes UTC the JVM's default zone. Every timestamp is a {@code LocalDateTime} from {@code LocalDateTime.now()},
     * stored in {@code TIMESTAMP} columns and written to JSON without an offset, so it is UTC wall-clock time only if
     * the default zone is UTC: the browser reads such a timestamp as UTC. It runs before anything reads the zone: the
     * PostgreSQL driver sends it as the session {@code TimeZone} when it connects (so {@code CURRENT_TIMESTAMP} stored
     * into a {@code TIMESTAMP} column is UTC too), H2 and Flyway take it, and so do the log timestamps. Tests do not
     * run {@code main}; the Gradle {@code test} task starts their JVM with {@code -Duser.timezone=UTC}.
     */
    static void useServerTimeZone() {
        System.setProperty("user.timezone", SERVER_TIME_ZONE);
        TimeZone.setDefault(TimeZone.getTimeZone(SERVER_TIME_ZONE));
    }


}
