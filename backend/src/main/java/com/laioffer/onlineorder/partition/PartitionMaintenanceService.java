package com.laioffer.onlineorder.partition;


import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;


import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;


/**
 * Keeps the monthly partitions of {@link PartitionedTable} (V10, PostgreSQL) in shape, every
 * app.partitions.fixed-delay-ms on the shared scheduler:
 * <ol>
 *     <li>creates the partitions of the current month and of the next app.partitions.months-ahead months (default 3),
 *     so inserts never fall into the DEFAULT partition. Rows that did land there for a month that now gets its
 *     partition are moved into it in the same transaction (PostgreSQL refuses to create a partition whose rows sit in
 *     the default partition);</li>
 *     <li>gives every processed_events partition its UNIQUE (consumer_name, dedup_key) index, also one created by
 *     hand;</li>
 *     <li>when app.cleanup.enabled, drops whole partitions whose upper bound is older than the table's retention
 *     (app.cleanup.processed-event-retention, audit-log-retention, order-status-history-retention; 0 keeps everything)
 *     instead of deleting rows, and deletes expired rows that sit in the DEFAULT partition in batches. A row is kept
 *     for at least its retention and at most a month longer.</li>
 * </ol>
 *
 * <p>Several instances run the job. Each DDL step runs in its own transaction that first takes
 * {@code pg_try_advisory_xact_lock(namespace, hashtext(table))}: an instance that does not get it skips the table
 * until the next run, and every step re-reads the catalog under the lock, so steps are idempotent. DDL takes an ACCESS
 * EXCLUSIVE lock on the parent table for a moment; a {@code lock_timeout} (app.partitions.lock-timeout) makes the step
 * give up instead of queueing every insert behind a long-running query, and the next run retries. On H2 nothing is
 * partitioned and the job does nothing; MaintenanceCleanupService deletes rows there.
 *
 * <p>Meters: {@code onlineorder.partitions.created}, {@code onlineorder.partitions.dropped} and
 * {@code onlineorder.partitions.maintenance.failures}, tagged with the table; the partition gauges are in
 * {@link PartitionMetricsBinder}.
 */
@Service
public class PartitionMaintenanceService {

    /**
     * First key of the two-key advisory lock: "part".
     */
    static final int ADVISORY_LOCK_NAMESPACE = 0x70617274;
    /**
     * Fewer would let the partition alerts (deploy/observability/prometheus/alerts.yml) fire on a healthy schedule.
     */
    static final int MIN_MONTHS_AHEAD = 2;

    private static final Logger LOGGER = LoggerFactory.getLogger(PartitionMaintenanceService.class);

    private final boolean maintenanceEnabled;
    private final boolean cleanupEnabled;
    private final int monthsAhead;
    private final Duration lockTimeout;
    private final Duration processedEventRetention;
    private final Duration auditLogRetention;
    private final Duration orderStatusHistoryRetention;
    private final int batchSize;
    private final PartitionCatalog catalog;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;


    public PartitionMaintenanceService(
            @Value("${app.partitions.maintenance-enabled:true}") boolean maintenanceEnabled,
            @Value("${app.cleanup.enabled:true}") boolean cleanupEnabled,
            @Value("${app.partitions.months-ahead:3}") int monthsAhead,
            @Value("${app.partitions.lock-timeout:5s}") Duration lockTimeout,
            @Value("${app.cleanup.processed-event-retention:14d}") Duration processedEventRetention,
            @Value("${app.cleanup.audit-log-retention:0d}") Duration auditLogRetention,
            @Value("${app.cleanup.order-status-history-retention:0d}") Duration orderStatusHistoryRetention,
            @Value("${app.cleanup.batch-size:5000}") int batchSize,
            PartitionCatalog catalog,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry
    ) {
        this.maintenanceEnabled = maintenanceEnabled;
        this.cleanupEnabled = cleanupEnabled;
        if (monthsAhead < MIN_MONTHS_AHEAD) {
            LOGGER.warn("app.partitions.months-ahead={} raised to {}", monthsAhead, MIN_MONTHS_AHEAD);
        }
        this.monthsAhead = Math.max(MIN_MONTHS_AHEAD, monthsAhead);
        this.lockTimeout = lockTimeout;
        this.processedEventRetention = processedEventRetention;
        this.auditLogRetention = auditLogRetention;
        this.orderStatusHistoryRetention = orderStatusHistoryRetention;
        this.batchSize = Math.max(1, batchSize);
        this.catalog = catalog;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.meterRegistry = meterRegistry;
    }


    @Scheduled(
            fixedDelayString = "${app.partitions.fixed-delay-ms:3600000}",
            initialDelayString = "${app.partitions.initial-delay-ms:60000}"
    )
    public void maintainPartitions() {
        if (maintenanceEnabled) {
            maintainPartitions(LocalDateTime.now());
        }
    }


    /**
     * One run as of {@code now} (the time the application writes into the partition columns: LocalDateTime.now()).
     * A failure on one table is logged and counted, and the other tables still run.
     */
    public void maintainPartitions(LocalDateTime now) {
        for (PartitionedTable table : PartitionedTable.values()) {
            try {
                if (catalog.isPartitioned(table)) {
                    maintain(table, now);
                }
            } catch (RuntimeException ex) {
                meterRegistry.counter("onlineorder.partitions.maintenance.failures", "table", table.tableName())
                        .increment();
                LOGGER.warn("Partition maintenance of {} failed; the next run retries", table.tableName(), ex);
            }
        }
    }


    /**
     * @return the retention of {@code table}; zero or negative keeps its rows forever
     */
    Duration retention(PartitionedTable table) {
        return switch (table) {
            case PROCESSED_EVENTS -> processedEventRetention;
            case AUDIT_LOG -> auditLogRetention;
            case ORDER_STATUS_HISTORY -> orderStatusHistoryRetention;
        };
    }


    private void maintain(PartitionedTable table, LocalDateTime now) {
        LocalDate currentMonth = now.toLocalDate().withDayOfMonth(1);
        for (int ahead = 0; ahead <= monthsAhead; ahead++) {
            LocalDate month = currentMonth.plusMonths(ahead);
            inLockedTransaction(table, () -> createPartition(table, month));
        }
        if (table.dedupKeyPerPartition()) {
            inLockedTransaction(table, () -> createMissingDedupIndexes(table));
        }
        Duration retention = retention(table);
        if (!cleanupEnabled || retention.isZero() || retention.isNegative()) {
            return;
        }
        LocalDateTime cutoff = now.minus(retention);
        for (TablePartition partition : catalog.partitions(table)) {
            if (!partition.isDefault() && partition.to() != null && !partition.to().isAfter(cutoff)) {
                inLockedTransaction(table, () -> dropPartition(table, partition.name(), cutoff));
            }
        }
        purgeDefaultPartition(table, cutoff);
    }


    /**
     * Creates the partition of {@code month} unless a partition already covers part of it.
     */
    private boolean createPartition(PartitionedTable table, LocalDate month) {
        LocalDateTime from = month.atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atStartOfDay();
        List<TablePartition> partitions = catalog.partitions(table);
        if (partitions.stream().anyMatch(partition -> partition.overlaps(from, to))) {
            return false;
        }
        String name = table.partitionName(month);
        String bounds = "FOR VALUES FROM ('" + from.toLocalDate() + " 00:00:00') TO ('" + to.toLocalDate()
                + " 00:00:00')";
        Optional<TablePartition> defaultPartition = partitions.stream().filter(TablePartition::isDefault).findFirst();
        if (defaultPartition.isPresent() && defaultHasRowsIn(table, defaultPartition.get().name(), from, to)) {
            // PostgreSQL refuses CREATE ... PARTITION OF while the default partition holds rows of the new range:
            // build the partition as a plain table, move those rows over, then attach it.
            String defaultName = defaultPartition.get().name();
            String range = table.partitionColumn() + " >= ? AND " + table.partitionColumn() + " < ?";
            jdbcTemplate.execute("CREATE TABLE " + name + " (LIKE " + table.tableName()
                    + " INCLUDING DEFAULTS INCLUDING CONSTRAINTS)");
            int moved = jdbcTemplate.update("INSERT INTO " + name + " SELECT * FROM " + defaultName + " WHERE " + range,
                    Timestamp.valueOf(from), Timestamp.valueOf(to));
            jdbcTemplate.update("DELETE FROM " + defaultName + " WHERE " + range,
                    Timestamp.valueOf(from), Timestamp.valueOf(to));
            jdbcTemplate.execute("ALTER TABLE " + table.tableName() + " ATTACH PARTITION " + name + " " + bounds);
            LOGGER.warn("Created partition {} and moved {} row(s) of its month out of {}", name, moved, defaultName);
        } else {
            jdbcTemplate.execute("CREATE TABLE " + name + " PARTITION OF " + table.tableName() + " " + bounds);
            LOGGER.info("Created partition {}", name);
        }
        if (table.dedupKeyPerPartition()) {
            createDedupIndex(name);
        }
        meterRegistry.counter("onlineorder.partitions.created", "table", table.tableName()).increment();
        return true;
    }


    private boolean createMissingDedupIndexes(PartitionedTable table) {
        boolean created = false;
        for (TablePartition partition : catalog.partitions(table)) {
            if (!catalog.relationExists(PartitionedTable.dedupIndexName(partition.name()))) {
                LOGGER.warn("Partition {} had no unique dedup index; creating it", partition.name());
                createDedupIndex(partition.name());
                created = true;
            }
        }
        return created;
    }


    private void createDedupIndex(String partition) {
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + PartitionedTable.dedupIndexName(partition)
                + " ON " + partition + " (consumer_name, dedup_key)");
    }


    /**
     * Drops the partition if it still exists and still ends at or before {@code cutoff} (re-read under the lock).
     */
    private boolean dropPartition(PartitionedTable table, String name, LocalDateTime cutoff) {
        boolean expired = catalog.partitions(table).stream()
                .anyMatch(partition -> partition.name().equals(name)
                        && !partition.isDefault()
                        && partition.to() != null
                        && !partition.to().isAfter(cutoff));
        if (!expired) {
            return false;
        }
        jdbcTemplate.execute("DROP TABLE " + name);
        meterRegistry.counter("onlineorder.partitions.dropped", "table", table.tableName()).increment();
        LOGGER.info("Dropped partition {}: every row in it is older than the retention", name);
        return true;
    }


    /**
     * Deletes expired rows from the default partition in statements of at most app.cleanup.batch-size rows. Plain
     * DML: it needs no advisory lock and takes no lock on the parent table.
     */
    private void purgeDefaultPartition(PartitionedTable table, LocalDateTime cutoff) {
        String defaultName = table.defaultPartitionName();
        if (!catalog.relationExists(defaultName)) {
            return;
        }
        String sql = "DELETE FROM " + defaultName + " WHERE ctid IN (SELECT ctid FROM " + defaultName + " WHERE "
                + table.partitionColumn() + " < ? LIMIT ?)";
        int purged = 0;
        while (true) {
            int deleted = jdbcTemplate.update(sql, Timestamp.valueOf(cutoff), batchSize);
            purged += deleted;
            if (deleted < batchSize || Thread.currentThread().isInterrupted()) {
                break;
            }
        }
        if (purged > 0) {
            LOGGER.info("Deleted {} expired row(s) from {}", purged, defaultName);
        }
    }


    private boolean defaultHasRowsIn(PartitionedTable table, String defaultName, LocalDateTime from, LocalDateTime to) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM " + defaultName + " WHERE " + table.partitionColumn() + " >= ? AND "
                        + table.partitionColumn() + " < ?)",
                Boolean.class,
                Timestamp.valueOf(from),
                Timestamp.valueOf(to)
        ));
    }


    /**
     * Runs {@code step} in a transaction that holds this table's maintenance lock, or skips it when another instance
     * holds the lock.
     */
    private void inLockedTransaction(PartitionedTable table, Supplier<Boolean> step) {
        transactionTemplate.executeWithoutResult(status -> {
            Boolean locked = jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_xact_lock(?, hashtext(?))",
                    Boolean.class,
                    ADVISORY_LOCK_NAMESPACE,
                    table.tableName()
            );
            if (!Boolean.TRUE.equals(locked)) {
                LOGGER.debug("Partition maintenance of {} runs on another instance; skipping", table.tableName());
                return;
            }
            jdbcTemplate.queryForObject("SELECT set_config('lock_timeout', ?, true)", String.class,
                    lockTimeout.toMillis() + "ms");
            step.get();
        });
    }
}
