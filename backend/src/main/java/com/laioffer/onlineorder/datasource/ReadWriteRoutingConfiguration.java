package com.laioffer.onlineorder.datasource;


import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;


import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;


/**
 * Read/write splitting, active only when {@code app.datasource.replica.urls} lists a replica (docs/operations.md "Read
 * replicas"). Without one this class contributes nothing and Spring Boot's single Hikari pool serves everything.
 *
 * <p>The application's {@link DataSource} becomes {@code LazyConnectionDataSourceProxy -> ReadWriteRoutingDataSource
 * -> primary pool | replica pools}; Spring Boot's own pool backs off. What never reaches a replica:
 * <ul>
 *     <li>write transactions and everything inside them (locks, idempotency checks, outbox claims), and statements
 *     outside a transaction (login, the health check, Spring Data's implicit CRUD transactions): {@link ReplicaRouter};</li>
 *     <li>Flyway: it migrates over its own unpooled connections to the primary, {@link #flywayConnectionDetails};</li>
 *     <li>Spring Session: it lives in Redis, not in the database.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@Conditional(ReplicasConfiguredCondition.class)
@EnableConfigurationProperties(ReplicaRoutingProperties.class)
public class ReadWriteRoutingConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadWriteRoutingConfiguration.class);


    @Bean(destroyMethod = "close")
    RoutingDataSources routingDataSources(
            DataSourceProperties dataSourceProperties,
            ReplicaRoutingProperties replicaProperties,
            Environment environment
    ) {
        RoutingDataSources dataSources = RoutingDataSources.create(dataSourceProperties, replicaProperties, environment);
        LOGGER.info(
                "Read/write splitting on: primary pool {}, {} read replica pool(s) {}; sticky window {}, max lag {}, "
                        + "lag check every {}",
                dataSources.primary().getPoolName(),
                dataSources.replicas().size(),
                dataSources.replicas().stream().map(ReplicaTarget::name).toList(),
                replicaProperties.stickyWindow(),
                replicaProperties.maxLag(),
                replicaProperties.lagCheckInterval()
        );
        return dataSources;
    }


    @Bean
    ReadYourWritesTracker readYourWritesTracker(ReplicaRoutingProperties replicaProperties) {
        if (replicaProperties.stickyWindow().compareTo(replicaProperties.maxStaleness()) <= 0) {
            LOGGER.warn(
                    "app.datasource.replica.sticky-window ({}) is not longer than max-lag ({}), the most a replica "
                            + "read can lag: a user's read soon after their own write can reach a replica that has not "
                            + "replayed it yet (keep a margin for clock differences between instances)",
                    replicaProperties.stickyWindow(),
                    replicaProperties.maxStaleness()
            );
        }
        return new ReadYourWritesTracker(replicaProperties.stickyWindow());
    }


    /**
     * The router. A session keeps its replica by its cookie: {@code server.servlet.session.cookie.name} when set, else
     * Spring Session's and the servlet container's default names.
     */
    @Bean
    ReplicaRouter replicaRouter(
            RoutingDataSources dataSources,
            ReadYourWritesTracker readYourWritesTracker,
            Environment environment
    ) {
        String cookieName = environment.getProperty("server.servlet.session.cookie.name");
        Set<String> sessionCookies = cookieName == null || cookieName.isBlank()
                ? ReplicaRouter.DEFAULT_SESSION_COOKIES
                : Set.of(cookieName);
        return new ReplicaRouter(dataSources.replicas(), readYourWritesTracker, sessionCookies);
    }


    /**
     * The application's DataSource. The lazy proxy defers taking a physical connection until the first statement, when
     * the transaction's read-only flag is known. Its defaults are stated so that it never opens a connection just to
     * look them up (autocommit as the primary pool hands connections out, PostgreSQL's default isolation).
     */
    @Bean
    DataSource dataSource(RoutingDataSources dataSources, ReplicaRouter replicaRouter) {
        LazyConnectionDataSourceProxy dataSource = new LazyConnectionDataSourceProxy();
        dataSource.setTargetDataSource(new ReadWriteRoutingDataSource(dataSources.primary(), replicaRouter));
        dataSource.setDefaultAutoCommit(dataSources.primary().isAutoCommit());
        String isolation = dataSources.primary().getTransactionIsolation();
        if (isolation != null) {
            dataSource.setDefaultTransactionIsolationName(isolation.replace("TRANSACTION_", "ISOLATION_"));
        } else {
            dataSource.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        }
        return dataSource;
    }


    /**
     * Where Flyway migrates: the primary, over its own unpooled connections, as Spring Boot otherwise derives them from
     * the application DataSource when {@code spring.flyway.user} is set (it cannot derive them from the routing proxy).
     * {@code spring.flyway.url}, {@code user}, {@code password} and {@code driver-class-name} still override the
     * primary's settings. Connection details rather than a DataSource bean, so the health check and pool metrics see
     * only the application DataSource.
     */
    @Bean
    FlywayConnectionDetails flywayConnectionDetails(DataSourceProperties dataSourceProperties, Environment environment) {
        String url = environment.getProperty("spring.flyway.url", dataSourceProperties.determineUrl());
        String username = environment.getProperty("spring.flyway.user", dataSourceProperties.determineUsername());
        String password = environment.getProperty("spring.flyway.password", dataSourceProperties.determinePassword());
        String driverClassName = environment.getProperty(
                "spring.flyway.driver-class-name", dataSourceProperties.determineDriverClassName());
        return new PrimaryFlywayConnectionDetails(url, username, password, driverClassName);
    }


    private record PrimaryFlywayConnectionDetails(String jdbcUrl, String username, String password, String driverClassName)
            implements FlywayConnectionDetails {

        @Override
        public String getJdbcUrl() {
            return jdbcUrl;
        }


        @Override
        public String getUsername() {
            return username;
        }


        @Override
        public String getPassword() {
            return password;
        }


        @Override
        public String getDriverClassName() {
            return driverClassName;
        }


        @Override
        public String toString() {
            return "PrimaryFlywayConnectionDetails[" + jdbcUrl + ", " + username + "]";
        }
    }


    /**
     * The lag monitor. Its connections take each pool's driver properties ({@code spring.datasource.hikari
     * .data-source-properties}, such as SSL settings given outside the URL), so it reaches every server the pools reach.
     */
    @Bean
    ReplicaLagMonitor replicaLagMonitor(ReplicaRoutingProperties replicaProperties, RoutingDataSources dataSources) {
        if (replicaProperties.maxLag().compareTo(replicaProperties.lagCheckInterval().multipliedBy(2)) <= 0) {
            LOGGER.warn(
                    "app.datasource.replica.max-lag ({}) is not above twice lag-check-interval ({}): a replica "
                            + "whose data is only known as of the previous check drops out before the next one, so "
                            + "replicas under write load get few reads",
                    replicaProperties.maxLag(),
                    replicaProperties.lagCheckInterval()
            );
        }
        HikariDataSource primaryPool = dataSources.primary();
        ReplicaLagMonitor.ConnectionSettings primary = ReplicaLagMonitor.ConnectionSettings.of(
                ReplicaRouter.PRIMARY,
                primaryPool.getJdbcUrl(),
                primaryPool.getUsername(),
                primaryPool.getPassword(),
                replicaProperties.lagCheckTimeout(),
                primaryPool.getDataSourceProperties()
        );
        List<ReplicaLagMonitor.MonitoredReplica> replicas = new ArrayList<>();
        for (int i = 0; i < dataSources.replicas().size(); i++) {
            ReplicaTarget replica = dataSources.replicas().get(i);
            HikariDataSource replicaPool = dataSources.replicaPools().get(i);
            replicas.add(new ReplicaLagMonitor.MonitoredReplica(replica, ReplicaLagMonitor.ConnectionSettings.of(
                    replica.name(),
                    replica.url(),
                    replicaPool.getUsername(),
                    replicaPool.getPassword(),
                    replicaProperties.lagCheckTimeout(),
                    replicaPool.getDataSourceProperties()
            )));
        }
        return new ReplicaLagMonitor(primary, replicas, replicaProperties.lagCheckInterval());
    }


    @Bean
    ReadReplicaMetrics readReplicaMetrics(RoutingDataSources dataSources, ReplicaRouter replicaRouter) {
        return new ReadReplicaMetrics(dataSources, replicaRouter);
    }
}
