package com.laioffer.onlineorder.datasource;


import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.core.env.Environment;


import java.util.ArrayList;
import java.util.List;


/**
 * The pools behind read/write splitting: the primary pool, configured exactly as Spring Boot configures its single
 * pool ({@code spring.datasource.*} and {@code spring.datasource.hikari.*}, pool name {@code onlineorder-db}), and one
 * pool per replica ({@code onlineorder-db-replica-1}, ...). None of them is a bean of its own; this holder closes them
 * when the context closes.
 *
 * <p>Replica pools take the primary's Hikari settings (notably {@code connection-init-sql} with the statement and lock
 * timeouts) and override what differs: URL, login, size, a shorter connection timeout so a read falls back to the
 * primary quickly, read-only connections, a socket timeout, and an {@code application_name} naming the pool. They start
 * when the application starts, without failing it when a replica is down (they keep retrying in the background).
 */
public final class RoutingDataSources implements AutoCloseable {

    static final String PRIMARY_HIKARI_PREFIX = "spring.datasource.hikari";
    /** Hikari refuses a validation timeout below 250ms. */
    private static final long MIN_VALIDATION_TIMEOUT_MILLIS = 250L;

    private final HikariDataSource primary;
    private final List<HikariDataSource> replicaPools;
    private final List<ReplicaTarget> replicas;


    private RoutingDataSources(HikariDataSource primary, List<HikariDataSource> replicaPools, List<ReplicaTarget> replicas) {
        this.primary = primary;
        this.replicaPools = List.copyOf(replicaPools);
        this.replicas = List.copyOf(replicas);
    }


    static RoutingDataSources create(
            DataSourceProperties primaryProperties,
            ReplicaRoutingProperties replicaProperties,
            Environment environment
    ) {
        Binder binder = Binder.get(environment);
        HikariDataSource primary = primaryProperties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        binder.bind(PRIMARY_HIKARI_PREFIX, Bindable.ofInstance(primary));

        List<HikariDataSource> replicaPools = new ArrayList<>();
        List<ReplicaTarget> replicas = new ArrayList<>();
        try {
            List<String> urls = replicaProperties.configuredUrls();
            for (int i = 0; i < urls.size(); i++) {
                String name = "replica-" + (i + 1);
                HikariConfig config = replicaConfig(binder, primary, primaryProperties, replicaProperties, name, urls.get(i));
                HikariDataSource pool = new HikariDataSource(config);
                replicaPools.add(pool);
                replicas.add(new ReplicaTarget(name, urls.get(i), pool, replicaProperties.maxLag()));
            }
        } catch (RuntimeException ex) {
            replicaPools.forEach(HikariDataSource::close);
            primary.close();
            throw ex;
        }
        return new RoutingDataSources(primary, replicaPools, replicas);
    }


    private static HikariConfig replicaConfig(
            Binder binder,
            HikariDataSource primary,
            DataSourceProperties primaryProperties,
            ReplicaRoutingProperties replicaProperties,
            String name,
            String url
    ) {
        HikariConfig config = new HikariConfig();
        binder.bind(PRIMARY_HIKARI_PREFIX, Bindable.ofInstance(config));
        String poolName = primary.getPoolName() + "-" + name;
        config.setPoolName(poolName);
        config.setJdbcUrl(url);
        config.setDriverClassName(primaryProperties.determineDriverClassName());
        config.setUsername(orDefault(replicaProperties.username(), primaryProperties.determineUsername()));
        config.setPassword(orDefault(replicaProperties.password(), primaryProperties.determinePassword()));
        config.setMaximumPoolSize(replicaProperties.pool().maximumSize());
        long connectionTimeout = replicaProperties.pool().connectionTimeout().toMillis();
        config.setConnectionTimeout(connectionTimeout);
        // Hikari wants validation-timeout below connection-timeout; the primary's (1s) can exceed the replicas' timeout.
        config.setValidationTimeout(Math.max(
                MIN_VALIDATION_TIMEOUT_MILLIS,
                Math.min(config.getValidationTimeout(), connectionTimeout / 2)
        ));
        config.setReadOnly(true);
        // A replica that is down when the application starts must not fail the start: the pool keeps retrying.
        config.setInitializationFailTimeout(-1L);
        long socketTimeoutSeconds = Math.max(1L, replicaProperties.pool().socketTimeout().toSeconds());
        config.addDataSourceProperty("socketTimeout", Long.toString(socketTimeoutSeconds));
        config.addDataSourceProperty("ApplicationName", poolName);
        return config;
    }


    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }


    public HikariDataSource primary() {
        return primary;
    }


    public List<ReplicaTarget> replicas() {
        return replicas;
    }


    /**
     * The replicas' pools, in the order of {@link #replicas()}.
     */
    public List<HikariDataSource> replicaPools() {
        return replicaPools;
    }


    @Override
    public void close() {
        replicaPools.forEach(HikariDataSource::close);
        primary.close();
    }
}
