package com.laioffer.onlineorder.ids;


import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;


import java.time.Duration;
import java.util.UUID;


/**
 * The {@link DistributedIdGenerator} of this instance, chosen by app.ids.worker-id-source:
 * <ul>
 *     <li>{@code static} (default): app.ids.worker-id (APP_IDS_WORKER_ID, default 0);</li>
 *     <li>{@code lease}: a worker id leased from {@code snowflake_worker_leases} ({@link WorkerIdLeaseManager}),
 *     the Kubernetes setting.</li>
 * </ul>
 * Any other value leaves the application without a generator, and startup fails.
 *
 * <p>Meters: {@code onlineorder.ids.worker_id} (the worker id in use, -1 while none is leased),
 * {@code onlineorder.ids.clock_backward{outcome=waited|refused}} and {@code onlineorder.ids.lease.lost}.
 */
@Configuration
public class IdGeneratorConfig {

    static final String WORKER_ID_SOURCE = "app.ids.worker-id-source";


    @Bean
    @ConditionalOnProperty(name = WORKER_ID_SOURCE, havingValue = "static", matchIfMissing = true)
    StaticWorkerIdGenerator staticWorkerIdGenerator(
            @Value("${app.ids.worker-id:0}") int workerId,
            @Value("${app.ids.max-clock-backward:50ms}") Duration maxClockBackward,
            MeterRegistry meterRegistry
    ) {
        StaticWorkerIdGenerator generator = new StaticWorkerIdGenerator(
                new SnowflakeIdGenerator(workerId, maxClockBackward, clockListener(meterRegistry))
        );
        registerWorkerIdGauge(meterRegistry, generator);
        return generator;
    }


    @Bean
    @ConditionalOnProperty(name = WORKER_ID_SOURCE, havingValue = "lease")
    WorkerIdLeaseManager workerIdLeaseManager(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            @Value("${app.ids.lease.ttl:60s}") Duration ttl,
            @Value("${app.ids.lease.renew-interval:15s}") Duration renewInterval,
            @Value("${app.ids.max-clock-backward:50ms}") Duration maxClockBackward,
            @Value("${app.ids.lease.owner:${HOSTNAME:local}}") String ownerPrefix,
            MeterRegistry meterRegistry
    ) {
        Counter leaseLost = Counter.builder("onlineorder.ids.lease.lost")
                .description("Snowflake worker id leases that were taken over after expiring")
                .register(meterRegistry);
        // A random suffix, so that a restarted pod with the same name does not mistake its predecessor's lease for its own.
        String owner = ownerPrefix + "/" + UUID.randomUUID().toString().substring(0, 8);
        WorkerIdLeaseManager manager = new WorkerIdLeaseManager(
                jdbcTemplate,
                transactionManager,
                ttl,
                renewInterval,
                maxClockBackward,
                owner,
                clockListener(meterRegistry),
                leaseLost::increment
        );
        registerWorkerIdGauge(meterRegistry, manager);
        return manager;
    }


    private static SnowflakeIdGenerator.Listener clockListener(MeterRegistry meterRegistry) {
        Counter waited = clockBackwardCounter(meterRegistry, "waited");
        Counter refused = clockBackwardCounter(meterRegistry, "refused");
        return (millis, wasWaited) -> (wasWaited ? waited : refused).increment();
    }


    private static Counter clockBackwardCounter(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder("onlineorder.ids.clock_backward")
                .description("Times the clock was behind the last Snowflake id: waited out, or refused (503)")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }


    private static void registerWorkerIdGauge(MeterRegistry meterRegistry, DistributedIdGenerator generator) {
        Gauge.builder("onlineorder.ids.worker_id", generator, DistributedIdGenerator::workerId)
                .description("Snowflake worker id this instance issues ids with; -1 while it holds no lease")
                .register(meterRegistry);
    }
}
