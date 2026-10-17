package com.laioffer.onlineorder.datasource;


import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.binder.MeterBinder;


import java.util.concurrent.TimeUnit;


/**
 * Metrics of read/write splitting:
 * <ul>
 *     <li>{@code hikaricp_connections_*{pool}} for every pool: Spring Boot instruments only Hikari pools that are beans,
 *     and the replica pools are not ({@code onlineorder-db} keeps its name, so the existing pool alerts still apply);</li>
 *     <li>{@code onlineorder_db_replica_lag_seconds{replica}}: how old the replica's data may be now (an upper bound of
 *     its lag; a caught-up replica shows the time since its last check), NaN when unknown;</li>
 *     <li>{@code onlineorder_db_replica_routable{replica}}: 1 while the replica gets reads, that is while it is
 *     healthy and that age is below max-lag;</li>
 *     <li>{@code onlineorder_db_routing_reads_total{target, reason}}: read-only transactions per pool that served them.</li>
 * </ul>
 */
public class ReadReplicaMetrics implements MeterBinder {

    private final RoutingDataSources dataSources;
    private final ReplicaRouter router;


    public ReadReplicaMetrics(RoutingDataSources dataSources, ReplicaRouter router) {
        this.dataSources = dataSources;
        this.router = router;
    }


    @Override
    public void bindTo(MeterRegistry registry) {
        instrument(dataSources.primary(), registry);
        dataSources.replicaPools().forEach(pool -> instrument(pool, registry));
        for (ReplicaTarget replica : dataSources.replicas()) {
            TimeGauge.builder("onlineorder.db.replica.lag", replica, TimeUnit.SECONDS, ReplicaTarget::lagSeconds)
                    .description("How old the read replica's data may be: time since the primary was last seen at a "
                            + "position the replica has replayed; NaN when unknown")
                    .tag("replica", replica.name())
                    .register(registry);
            Gauge.builder("onlineorder.db.replica.routable", replica, target -> target.isRoutable() ? 1.0 : 0.0)
                    .description("1 while read-only transactions may be routed to the replica")
                    .tag("replica", replica.name())
                    .register(registry);
        }
        router.bindTo(registry);
    }


    /**
     * The same as Spring Boot does for a Hikari pool bean, and skipped when it already did (the primary pool is
     * reachable through the routing DataSource bean, which Boot unwraps).
     */
    private static void instrument(HikariDataSource pool, MeterRegistry registry) {
        if (pool.getMetricRegistry() == null && pool.getMetricsTrackerFactory() == null) {
            pool.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(registry));
        }
    }
}
