package com.laioffer.onlineorder.datasource;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;


import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;


/**
 * The lag monitor's verdicts on servers that are not a healthy replica, on one real PostgreSQL that is not in recovery.
 * The healthy, lagging, stopped and hung replica cases run on a real streaming replica in
 * {@link ReadReplicaRoutingIntegrationTests}.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReplicaLagMonitorTests {

    private static final String REFUSED = "jdbc:postgresql://127.0.0.1:1/onlineorder";
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    @Container
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("onlineorder")
            .withUsername("postgres")
            .withPassword("secret");


    @Test
    void aServerThatIsNotInRecoveryIsNoReplica() {
        ReplicaTarget notAStandby = target("replica-1");
        ReplicaLagMonitor monitor = monitor(settings("primary", POSTGRES.getJdbcUrl()), notAStandby, POSTGRES.getJdbcUrl());

        monitor.checkNow();

        Assertions.assertEquals(ReplicaTarget.State.NOT_STANDBY, notAStandby.state());
        Assertions.assertFalse(notAStandby.isRoutable());
        Assertions.assertTrue(Double.isNaN(notAStandby.lagSeconds()));
        monitor.stop();
    }


    @Test
    void anUnreachableReplicaIsMarkedSo_andThePrimaryConnectionIsKept() {
        ReplicaTarget down = target("replica-1");
        ReplicaLagMonitor monitor = monitor(settings("primary", POSTGRES.getJdbcUrl()), down, REFUSED);

        monitor.checkNow();
        monitor.checkNow();

        Assertions.assertEquals(ReplicaTarget.State.UNREACHABLE, down.state());
        monitor.stop();
    }


    @Test
    void withoutThePrimarysPositionNoReplicaIsRoutable() {
        ReplicaTarget replica = target("replica-1");
        replica.lagMeasured(0.0, true, 1.0);
        ReplicaLagMonitor monitor = monitor(settings("primary", REFUSED), replica, POSTGRES.getJdbcUrl());

        monitor.checkNow();

        Assertions.assertEquals(ReplicaTarget.State.PRIMARY_UNKNOWN, replica.state());
        Assertions.assertFalse(replica.isRoutable());
    }


    @Test
    void startsOnItsOwnThreadAndStopsAgain() throws InterruptedException {
        ReplicaTarget notAStandby = target("replica-1");
        ReplicaLagMonitor monitor = new ReplicaLagMonitor(
                settings("primary", POSTGRES.getJdbcUrl()),
                List.of(new ReplicaLagMonitor.MonitoredReplica(notAStandby, settings("replica-1", POSTGRES.getJdbcUrl()))),
                Duration.ofMillis(50),
                Duration.ofSeconds(1)
        );

        monitor.start();
        monitor.start();
        Assertions.assertTrue(monitor.isRunning());
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (notAStandby.state() != ReplicaTarget.State.NOT_STANDBY) {
            Assertions.assertTrue(System.nanoTime() < deadline, "the first check ran");
            Thread.sleep(20);
        }
        monitor.stop();
        monitor.stop();
        Assertions.assertFalse(monitor.isRunning());
    }


    @Test
    void connectionSettingsCarryTheLoginTimeoutsAndApplicationName() {
        ReplicaLagMonitor.ConnectionSettings settings =
                ReplicaLagMonitor.ConnectionSettings.of("replica-1", REFUSED, "reader", "pw", Duration.ofMillis(1500));
        Assertions.assertEquals("reader", settings.properties().getProperty("user"));
        Assertions.assertEquals("pw", settings.properties().getProperty("password"));
        Assertions.assertEquals("2", settings.properties().getProperty("socketTimeout"));
        Assertions.assertEquals("2", settings.properties().getProperty("connectTimeout"));
        Assertions.assertEquals(ReplicaLagMonitor.APPLICATION_NAME, settings.properties().getProperty("ApplicationName"));
        Assertions.assertFalse(settings.toString().contains("pw"));

        ReplicaLagMonitor.ConnectionSettings anonymous =
                ReplicaLagMonitor.ConnectionSettings.of("replica-1", REFUSED, null, null, Duration.ZERO);
        Assertions.assertNull(anonymous.properties().getProperty("user"));
        Assertions.assertEquals("1", anonymous.properties().getProperty("socketTimeout"));
    }


    private static ReplicaLagMonitor monitor(ReplicaLagMonitor.ConnectionSettings primary, ReplicaTarget replica, String url) {
        return new ReplicaLagMonitor(
                primary,
                List.of(new ReplicaLagMonitor.MonitoredReplica(replica, settings(replica.name(), url))),
                Duration.ofHours(1),
                Duration.ofSeconds(1)
        );
    }


    private static ReplicaLagMonitor.ConnectionSettings settings(String key, String url) {
        return ReplicaLagMonitor.ConnectionSettings.of(key, url, "postgres", "secret", TIMEOUT);
    }


    private static ReplicaTarget target(String name) {
        return new ReplicaTarget(name, "unused", Mockito.mock(DataSource.class));
    }
}
