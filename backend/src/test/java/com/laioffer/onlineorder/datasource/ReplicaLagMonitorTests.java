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
import java.util.Properties;


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
        replica.measured(new WalPositionHistory.Freshness(System.nanoTime(), true));
        Assertions.assertTrue(replica.isRoutable());
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
                Duration.ofMillis(50)
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


    @Test
    void connectionSettingsStartFromThePoolsDriverProperties() {
        // As spring.datasource.hikari.data-source-properties and the replica pool's own settings leave them.
        Properties pool = new Properties();
        pool.setProperty("sslmode", "verify-full");
        pool.setProperty("sslrootcert", "/etc/ssl/certs/db-root.crt");
        pool.put("tcpKeepAlive", Boolean.TRUE);
        pool.setProperty("socketTimeout", "15");
        pool.setProperty("ApplicationName", "onlineorder-db-replica-1");
        pool.setProperty("user", "pool-user");

        ReplicaLagMonitor.ConnectionSettings settings = ReplicaLagMonitor.ConnectionSettings.of(
                "replica-1", REFUSED, "reader", "pw", Duration.ofSeconds(1), pool);

        Assertions.assertEquals("verify-full", settings.properties().getProperty("sslmode"));
        Assertions.assertEquals("/etc/ssl/certs/db-root.crt", settings.properties().getProperty("sslrootcert"));
        Assertions.assertEquals("true", settings.properties().getProperty("tcpKeepAlive"));
        Assertions.assertEquals("1", settings.properties().getProperty("socketTimeout"), "the monitor's own timeout");
        Assertions.assertEquals(ReplicaLagMonitor.APPLICATION_NAME, settings.properties().getProperty("ApplicationName"));
        Assertions.assertEquals("reader", settings.properties().getProperty("user"));
        Assertions.assertEquals("15", pool.getProperty("socketTimeout"), "the pool's own properties are left alone");

        ReplicaLagMonitor.ConnectionSettings withoutLogin = ReplicaLagMonitor.ConnectionSettings.of(
                "replica-1", REFUSED, null, null, Duration.ofSeconds(1), pool);
        Assertions.assertNull(withoutLogin.properties().getProperty("user"), "no login but the one given");
    }


    @Test
    void aMonitorConnectionUsesThePoolsDriverProperties() {
        // The pools would require TLS, which this server does not offer: the monitor's connection must fail the same
        // way rather than reach the server without it (the same server is NOT_STANDBY over a plain connection).
        Properties pool = new Properties();
        pool.setProperty("sslmode", "require");
        ReplicaTarget replica = target("replica-1");
        ReplicaLagMonitor monitor = new ReplicaLagMonitor(
                settings("primary", POSTGRES.getJdbcUrl()),
                List.of(new ReplicaLagMonitor.MonitoredReplica(replica, ReplicaLagMonitor.ConnectionSettings.of(
                        "replica-1", POSTGRES.getJdbcUrl(), "postgres", "secret", TIMEOUT, pool))),
                Duration.ofHours(1)
        );

        monitor.checkNow();

        Assertions.assertEquals(ReplicaTarget.State.UNREACHABLE, replica.state());
        monitor.stop();
    }


    private static ReplicaLagMonitor monitor(ReplicaLagMonitor.ConnectionSettings primary, ReplicaTarget replica, String url) {
        return new ReplicaLagMonitor(
                primary,
                List.of(new ReplicaLagMonitor.MonitoredReplica(replica, settings(replica.name(), url))),
                Duration.ofHours(1)
        );
    }


    private static ReplicaLagMonitor.ConnectionSettings settings(String key, String url) {
        return ReplicaLagMonitor.ConnectionSettings.of(key, url, "postgres", "secret", TIMEOUT);
    }


    private static ReplicaTarget target(String name) {
        return new ReplicaTarget(name, "unused", Mockito.mock(DataSource.class), Duration.ofSeconds(1));
    }
}
