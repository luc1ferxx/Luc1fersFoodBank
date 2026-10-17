package com.laioffer.onlineorder.datasource;


import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;


import javax.sql.DataSource;
import java.time.Duration;


/**
 * How read/write splitting is wired from configuration, without a database: application.yml defaults, the pools, the
 * DataSource Spring Boot's own auto-configuration backs off for, Flyway's target and the metrics.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReadWriteRoutingConfigurationTests {

    /** Nothing listens on port 1: every connection attempt is refused at once. */
    private static final String UNREACHABLE = "jdbc:postgresql://127.0.0.1:1/";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withUserConfiguration(ReadWriteRoutingConfiguration.class)
            .withPropertyValues(
                    "spring.datasource.url=" + UNREACHABLE + "onlineorder",
                    // One check at start-up, none while a test looks at the state.
                    "app.datasource.replica.lag-check-interval=1h"
            );


    @Test
    void withoutReplicaUrls_springBootsSinglePoolServesEverything() {
        contextRunner.run(context -> {
            Assertions.assertInstanceOf(HikariDataSource.class, context.getBean(DataSource.class));
            Assertions.assertTrue(context.getBeansOfType(ReplicaRouter.class).isEmpty());
            Assertions.assertTrue(context.getBeansOfType(ReplicaLagMonitor.class).isEmpty());
            Assertions.assertTrue(context.getBeansOfType(ReplicaRoutingProperties.class).isEmpty());
        });
        contextRunner.withPropertyValues("DATABASE_REPLICA_URLS= , ").run(context ->
                Assertions.assertInstanceOf(HikariDataSource.class, context.getBean(DataSource.class)));
    }


    @Test
    void withReplicaUrls_theDataSourceRoutesBetweenThePrimaryPoolAndOnePoolPerReplica() {
        contextRunner
                .withPropertyValues("DATABASE_REPLICA_URLS=" + UNREACHABLE + "replica1, " + UNREACHABLE + "replica2")
                .run(context -> {
                    DataSource dataSource = context.getBean(DataSource.class);
                    Assertions.assertInstanceOf(LazyConnectionDataSourceProxy.class, dataSource);
                    RoutingDataSources pools = context.getBean(RoutingDataSources.class);

                    // The primary pool is configured exactly like Spring Boot's single pool.
                    HikariDataSource primary = pools.primary();
                    Assertions.assertEquals("onlineorder-db", primary.getPoolName());
                    Assertions.assertEquals(30, primary.getMaximumPoolSize());
                    Assertions.assertEquals(2000L, primary.getConnectionTimeout());
                    Assertions.assertEquals(
                            "SET statement_timeout = 10000; SET lock_timeout = 3000", primary.getConnectionInitSql());
                    Assertions.assertSame(primary, dataSource.unwrap(HikariDataSource.class),
                            "Boot's health check and pool metadata see the primary");

                    Assertions.assertEquals(2, pools.replicaPools().size());
                    HikariDataSource replica = pools.replicaPools().get(1);
                    Assertions.assertEquals("onlineorder-db-replica-2", replica.getPoolName());
                    Assertions.assertEquals(UNREACHABLE + "replica2", replica.getJdbcUrl());
                    Assertions.assertEquals(20, replica.getMaximumPoolSize());
                    Assertions.assertEquals(1000L, replica.getConnectionTimeout());
                    Assertions.assertEquals(500L, replica.getValidationTimeout());
                    Assertions.assertTrue(replica.isReadOnly());
                    Assertions.assertEquals(primary.getConnectionInitSql(), replica.getConnectionInitSql());
                    Assertions.assertEquals("postgres", replica.getUsername());
                    Assertions.assertEquals("secret", replica.getPassword());
                    Assertions.assertEquals("15", replica.getDataSourceProperties().getProperty("socketTimeout"));
                    Assertions.assertEquals("onlineorder-db-replica-2",
                            replica.getDataSourceProperties().getProperty("ApplicationName"));
                    Assertions.assertEquals(
                            java.util.List.of("replica-1", "replica-2"),
                            pools.replicas().stream().map(ReplicaTarget::name).toList());

                    // Flyway migrates the primary over its own connections.
                    FlywayConnectionDetails flyway = context.getBean(FlywayConnectionDetails.class);
                    Assertions.assertEquals(UNREACHABLE + "onlineorder", flyway.getJdbcUrl());
                    Assertions.assertEquals("postgres", flyway.getUsername());
                    Assertions.assertEquals("secret", flyway.getPassword());
                    Assertions.assertEquals("org.postgresql.Driver", flyway.getDriverClassName());
                    Assertions.assertFalse(flyway.toString().contains("secret"));

                    // The monitor ran once: with the primary unreachable no lag can be measured.
                    ReplicaRouter router = context.getBean(ReplicaRouter.class);
                    awaitState(router.replicas().get(0), ReplicaTarget.State.PRIMARY_UNKNOWN);
                    Assertions.assertTrue(context.getBean(ReplicaLagMonitor.class).isRunning());
                });
    }


    @Test
    void replicaCredentialsPoolSettingsAndFlywaysTargetCanBeOverridden() {
        contextRunner
                .withPropertyValues(
                        "DATABASE_REPLICA_URLS=" + UNREACHABLE + "replica1",
                        "DATABASE_REPLICA_USERNAME=reader",
                        "DATABASE_REPLICA_PASSWORD=reader-secret",
                        "DATABASE_REPLICA_POOL_MAX_SIZE=4",
                        "DATABASE_REPLICA_POOL_CONNECTION_TIMEOUT=300ms",
                        "DATABASE_REPLICA_SOCKET_TIMEOUT=30s",
                        "spring.flyway.url=" + UNREACHABLE + "migrations",
                        "spring.flyway.user=owner",
                        "spring.flyway.password=owner-secret"
                )
                .run(context -> {
                    HikariDataSource replica = context.getBean(RoutingDataSources.class).replicaPools().get(0);
                    Assertions.assertEquals("reader", replica.getUsername());
                    Assertions.assertEquals("reader-secret", replica.getPassword());
                    Assertions.assertEquals(4, replica.getMaximumPoolSize());
                    Assertions.assertEquals(300L, replica.getConnectionTimeout());
                    Assertions.assertEquals(250L, replica.getValidationTimeout(), "Hikari's minimum");
                    Assertions.assertEquals("30", replica.getDataSourceProperties().getProperty("socketTimeout"));

                    FlywayConnectionDetails flyway = context.getBean(FlywayConnectionDetails.class);
                    Assertions.assertEquals(UNREACHABLE + "migrations", flyway.getJdbcUrl());
                    Assertions.assertEquals("owner", flyway.getUsername());
                    Assertions.assertEquals("owner-secret", flyway.getPassword());

                    ReplicaRoutingProperties properties = context.getBean(ReplicaRoutingProperties.class);
                    Assertions.assertFalse(properties.toString().contains("reader-secret"));
                    Assertions.assertTrue(properties.toString().contains("reader"));
                });
    }


    @Test
    void metricsCoverEveryPoolTheReplicasAndTheReads() {
        contextRunner
                .withPropertyValues("DATABASE_REPLICA_URLS=" + UNREACHABLE + "replica1")
                .run(context -> {
                    SimpleMeterRegistry registry = new SimpleMeterRegistry();
                    context.getBean(ReadReplicaMetrics.class).bindTo(registry);
                    // Binding twice (Spring Boot also instruments the primary it unwraps) is harmless.
                    context.getBean(ReadReplicaMetrics.class).bindTo(new SimpleMeterRegistry());

                    Assertions.assertNotNull(registry.find("hikaricp.connections")
                            .tag("pool", "onlineorder-db-replica-1").gauge(), "replica pool metrics");
                    Assertions.assertNotNull(context.getBean(RoutingDataSources.class).primary().getMetricsTrackerFactory());
                    Assertions.assertEquals(0.0, registry.get("onlineorder.db.replica.routable")
                            .tag("replica", "replica-1").gauge().value());
                    Assertions.assertTrue(Double.isNaN(registry.get("onlineorder.db.replica.lag")
                            .tag("replica", "replica-1").timeGauge().value(java.util.concurrent.TimeUnit.SECONDS)));
                    Assertions.assertEquals(4, registry.find(ReplicaRouter.READS_METRIC).counters().size());
                });
    }


    @Test
    void aStickyWindowNotAboveMaxLagAndAMaxLagNotAboveTwoCheckIntervalsAreReported(CapturedOutput output) {
        contextRunner
                .withPropertyValues(
                        "DATABASE_REPLICA_URLS=" + UNREACHABLE + "replica1",
                        "DATABASE_REPLICA_STICKY_WINDOW=2s",
                        "app.datasource.replica.lag-check-interval=1s"
                )
                .run(context -> {
                    Assertions.assertNotNull(context.getBean(ReadYourWritesTracker.class));
                    Assertions.assertTrue(output.getOut().contains(
                            "app.datasource.replica.sticky-window (PT2S) is not longer than max-lag (PT2S)"), output.getOut());
                    Assertions.assertTrue(output.getOut().contains(
                            "app.datasource.replica.max-lag (PT2S) is not above twice lag-check-interval (PT1S)"),
                            output.getOut());
                });
    }


    @Test
    void theDefaultsAreConsistent(CapturedOutput output) {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withUserConfiguration(ReadWriteRoutingConfiguration.class)
                .withPropertyValues(
                        "spring.datasource.url=" + UNREACHABLE + "onlineorder",
                        "DATABASE_REPLICA_URLS=" + UNREACHABLE + "replica1"
                )
                .run(context -> {
                    ReplicaRoutingProperties properties = context.getBean(ReplicaRoutingProperties.class);
                    Assertions.assertEquals(Duration.ofSeconds(5), properties.stickyWindow());
                    Assertions.assertEquals(Duration.ofSeconds(2), properties.maxLag());
                    Assertions.assertEquals(Duration.ofSeconds(2), properties.maxStaleness(), "max-lag bounds every read");
                    Assertions.assertEquals(Duration.ofMillis(500), properties.lagCheckInterval());
                    Assertions.assertFalse(output.getOut().contains("is not longer than max-lag"), output.getOut());
                    Assertions.assertFalse(output.getOut().contains("is not above twice"), output.getOut());
                });
    }


    private static void awaitState(ReplicaTarget replica, ReplicaTarget.State state) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (replica.state() != state) {
            Assertions.assertTrue(System.nanoTime() < deadline, "state " + replica.state());
            Thread.sleep(20);
        }
    }
}
