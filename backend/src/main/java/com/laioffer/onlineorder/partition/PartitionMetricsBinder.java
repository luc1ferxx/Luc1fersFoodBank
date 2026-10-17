package com.laioffer.onlineorder.partition;


import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;


/**
 * Gauges of the monthly partitions, per table ({@code table} tag), read from the PostgreSQL catalog at most every
 * 30 seconds whatever the number of scrapes and gauges:
 * <ul>
 *     <li>{@code onlineorder.partitions.count}: range partitions (the default partition not counted);</li>
 *     <li>{@code onlineorder.partitions.future}: partitions that start after the current month. The maintenance job
 *     keeps app.partitions.months-ahead (3) of them; the OnlineOrderPartitionsAhead* alerts fire below 2 and at 0,
 *     when next month's rows would fall into the default partition;</li>
 *     <li>{@code onlineorder.partitions.oldest_age} (seconds): how far back the oldest partition starts, which shows
 *     retention at work;</li>
 *     <li>{@code onlineorder.partitions.default_rows}: rows in the default partition (counted up to 10,001), which
 *     should stay 0.</li>
 * </ul>
 * NaN where the table is not partitioned (H2) or the catalog cannot be read.
 */
@Component
public class PartitionMetricsBinder implements MeterBinder {

    static final Duration REFRESH_INTERVAL = Duration.ofSeconds(30);
    static final int DEFAULT_ROWS_CAP = 10_001;

    private static final Logger LOGGER = LoggerFactory.getLogger(PartitionMetricsBinder.class);

    private final PartitionCatalog catalog;
    private final JdbcTemplate jdbcTemplate;
    private final Supplier<LocalDateTime> clock;
    private final Map<PartitionedTable, Snapshot> snapshots = new EnumMap<>(PartitionedTable.class);


    @Autowired
    public PartitionMetricsBinder(PartitionCatalog catalog, JdbcTemplate jdbcTemplate) {
        this(catalog, jdbcTemplate, LocalDateTime::now);
    }


    PartitionMetricsBinder(PartitionCatalog catalog, JdbcTemplate jdbcTemplate, Supplier<LocalDateTime> clock) {
        this.catalog = catalog;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }


    @Override
    public void bindTo(MeterRegistry registry) {
        for (PartitionedTable table : PartitionedTable.values()) {
            gauge(registry, table, "onlineorder.partitions.count",
                    "Monthly range partitions of the table (the default partition not counted)", Status::count);
            gauge(registry, table, "onlineorder.partitions.future",
                    "Partitions that start after the current month; 0 sends next month's rows to the default partition",
                    Status::future);
            gauge(registry, table, "onlineorder.partitions.default_rows",
                    "Rows in the default partition (counted up to 10001); they are not dropped with the months",
                    Status::defaultRows);
            TimeGauge.builder("onlineorder.partitions.oldest_age", () -> status(table).oldestAgeSeconds(),
                            TimeUnit.SECONDS)
                    .description("How far back the oldest partition of the table starts")
                    .tag("table", table.tableName())
                    .register(registry);
        }
    }


    private void gauge(
            MeterRegistry registry,
            PartitionedTable table,
            String name,
            String description,
            ToDoubleFunction<Status> value
    ) {
        Gauge.builder(name, () -> value.applyAsDouble(status(table)))
                .description(description)
                .tag("table", table.tableName())
                .register(registry);
    }


    Status status(PartitionedTable table) {
        LocalDateTime now = clock.get();
        synchronized (snapshots) {
            Snapshot cached = snapshots.get(table);
            if (cached != null && cached.readAt().plus(REFRESH_INTERVAL).isAfter(now)) {
                return cached.status();
            }
            Status status = read(table, now);
            snapshots.put(table, new Snapshot(now, status));
            return status;
        }
    }


    private Status read(PartitionedTable table, LocalDateTime now) {
        try {
            if (!catalog.isPartitioned(table)) {
                return Status.UNKNOWN;
            }
            List<TablePartition> partitions = catalog.partitions(table);
            LocalDateTime nextMonth = now.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay();
            long count = partitions.stream().filter(partition -> !partition.isDefault()).count();
            long future = partitions.stream()
                    .filter(partition -> !partition.isDefault()
                            && partition.from() != null
                            && !partition.from().isBefore(nextMonth))
                    .count();
            double oldestAge = partitions.stream()
                    .filter(partition -> !partition.isDefault() && partition.from() != null)
                    .map(TablePartition::from)
                    .min(LocalDateTime::compareTo)
                    .map(from -> Math.max(0L, Duration.between(from, now).toMillis()) / 1000.0)
                    .orElse(Double.NaN);
            double defaultRows = partitions.stream().anyMatch(TablePartition::isDefault)
                    ? countDefaultRows(table)
                    : 0.0;
            return new Status(count, future, oldestAge, defaultRows);
        } catch (RuntimeException ex) {
            LOGGER.debug("Could not read the partitions of {}", table.tableName(), ex);
            return Status.UNKNOWN;
        }
    }


    private double countDefaultRows(PartitionedTable table) {
        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT 1 FROM " + table.defaultPartitionName() + " LIMIT "
                        + DEFAULT_ROWS_CAP + ") AS capped",
                Long.class
        );
        return rows == null ? 0.0 : rows;
    }


    record Status(double count, double future, double oldestAgeSeconds, double defaultRows) {

        static final Status UNKNOWN = new Status(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }


    private record Snapshot(LocalDateTime readAt, Status status) {
    }
}
