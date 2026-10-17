package com.laioffer.onlineorder.partition;


import com.laioffer.onlineorder.service.MaintenanceCleanupService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;


import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;


/**
 * The partition maintenance job against PostgreSQL. Tests in this class share one database, so each works in months
 * no other test touches, and drops only run as of the real date (they can only reach months the tests created in the
 * past). Variants of the job are built by hand where a test needs other settings than the context's.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PartitionMaintenancePostgresIntegrationTests {

    @Container
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("onlineorder")
            .withUsername("postgres")
            .withPassword("secret");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.session.store-type", () -> "none");
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.outbox.publisher-enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
        registry.add("app.cleanup.enabled", () -> "true");
        // The tests call the jobs; the schedules must not run in between.
        registry.add("app.cleanup.initial-delay-ms", () -> "3600000");
        registry.add("app.partitions.initial-delay-ms", () -> "3600000");
        registry.add("app.cleanup.audit-log-retention", () -> "400d");
        registry.add("app.cleanup.batch-size", () -> "2");
    }

    @Autowired
    private PartitionMaintenanceService maintenanceService;

    @Autowired
    private MaintenanceCleanupService cleanupService;

    @Autowired
    private PartitionCatalog catalog;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;


    @Test
    void maintain_shouldKeepMonthsAheadOfTheClock() {
        LocalDateTime inThreeYears = LocalDateTime.now().plusYears(3);
        LocalDate month = inThreeYears.toLocalDate().withDayOfMonth(1);
        MeterRegistry registry = new SimpleMeterRegistry();

        withoutCleanup(registry).maintainPartitions(inThreeYears);

        for (PartitionedTable table : PartitionedTable.values()) {
            for (int ahead = 0; ahead <= 3; ahead++) {
                Assertions.assertTrue(catalog.relationExists(table.partitionName(month.plusMonths(ahead))),
                        table.partitionName(month.plusMonths(ahead)));
            }
            Assertions.assertEquals(4.0, registry.counter("onlineorder.partitions.created", "table", table.tableName())
                    .count());
        }
        Assertions.assertTrue(catalog.relationExists("processed_events_p" + month.getYear() + "_"
                + String.format("%02d", month.getMonthValue()) + "_dedup_key"), "new partitions get the dedup index");

        // A second run finds nothing to do.
        withoutCleanup(registry).maintainPartitions(inThreeYears);
        Assertions.assertEquals(4.0, registry.counter("onlineorder.partitions.created", "table", "audit_log").count());
    }


    @Test
    void maintain_shouldKeepAtLeastTwoMonthsAheadWhateverTheSetting() {
        LocalDateTime inFourteenYears = LocalDateTime.now().plusYears(14);
        LocalDate month = inFourteenYears.toLocalDate().withDayOfMonth(1);

        new PartitionMaintenanceService(true, false, 1, Duration.ofSeconds(5), Duration.ofDays(14), Duration.ZERO,
                Duration.ZERO, 1000, catalog, jdbcTemplate, transactionManager, new SimpleMeterRegistry())
                .maintainPartitions(inFourteenYears);

        Assertions.assertTrue(catalog.relationExists(PartitionedTable.AUDIT_LOG.partitionName(month.plusMonths(2))));
        Assertions.assertFalse(catalog.relationExists(PartitionedTable.AUDIT_LOG.partitionName(month.plusMonths(3))));
    }


    @Test
    void maintain_whenDisabled_shouldDoNothing() {
        LocalDateTime inSixteenYears = LocalDateTime.now().plusYears(16);
        PartitionMaintenanceService disabled = new PartitionMaintenanceService(false, true, 3, Duration.ofSeconds(5),
                Duration.ofDays(14), Duration.ZERO, Duration.ZERO, 1000, catalog, jdbcTemplate, transactionManager,
                new SimpleMeterRegistry());

        disabled.maintainPartitions();

        Assertions.assertFalse(catalog.relationExists(PartitionedTable.AUDIT_LOG.partitionName(inSixteenYears.toLocalDate())));
        Assertions.assertEquals(Duration.ofDays(14), disabled.retention(PartitionedTable.PROCESSED_EVENTS));
    }


    @Test
    void maintain_shouldMoveRowsOutOfTheDefaultPartitionWhenTheirMonthGetsOne() {
        LocalDateTime fiveYearsAhead = LocalDateTime.now().plusYears(5).withDayOfMonth(10);
        LocalDate month = fiveYearsAhead.toLocalDate().withDayOfMonth(1);
        // No partition covers that month yet: the row lands in the default partition.
        insertProcessedEvent("move-consumer", "move-key", fiveYearsAhead);
        Assertions.assertEquals("processed_events_default", partitionOf("move-key"));

        withoutCleanup(new SimpleMeterRegistry()).maintainPartitions(fiveYearsAhead.minusMonths(1));

        Assertions.assertEquals(PartitionedTable.PROCESSED_EVENTS.partitionName(month), partitionOf("move-key"));
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events_default WHERE dedup_key = 'move-key'", Integer.class));
        // The attached table got the parent's keys and the per-partition dedup index.
        Assertions.assertTrue(catalog.relationExists(PartitionedTable.PROCESSED_EVENTS.partitionName(month) + "_dedup_key"));
        Assertions.assertEquals(0, jdbcTemplate.update("""
                INSERT INTO processed_events (consumer_name, dedup_key, event_type, processed_at)
                VALUES ('move-consumer', 'move-key', 'order.paid', ?)
                ON CONFLICT DO NOTHING
                """, Timestamp.valueOf(fiveYearsAhead.plusDays(1))));
    }


    @Test
    void maintain_shouldDropWholeMonthsPastTheirRetentionOnly() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime longAgo = now.minusMonths(30).withDayOfMonth(5);
        LocalDate oldMonth = longAgo.toLocalDate().withDayOfMonth(1);
        withoutCleanup(new SimpleMeterRegistry()).maintainPartitions(longAgo);
        long orderId = insertOrder(longAgo);
        insertProcessedEvent("drop-consumer", "drop-old", longAgo);
        insertProcessedEvent("drop-consumer", "drop-new", now);
        jdbcTemplate.update("""
                INSERT INTO audit_log (actor_role, action, target_type, target_id, occurred_at)
                VALUES ('ROLE_ADMIN', 'drop.test', 'order', 'old', ?), ('ROLE_ADMIN', 'drop.test', 'order', 'older', ?)
                """, Timestamp.valueOf(longAgo), Timestamp.valueOf(now.minusMonths(20)));
        jdbcTemplate.update("""
                INSERT INTO order_status_history (order_id, to_status, actor_type, occurred_at)
                VALUES (?, 'PLACED', 'SYSTEM', ?)
                """, orderId, Timestamp.valueOf(longAgo));
        // Rows of a month without a partition, past every retention: they sit in the default partition (three rows,
        // deleted two per statement).
        for (int i = 0; i < 3; i++) {
            insertProcessedEvent("drop-consumer", "drop-default-" + i, LocalDateTime.of(2001, 1, 1, 0, 0));
        }

        maintenanceService.maintainPartitions(now);

        // processed_events (14 days): the old months are gone, rows and all; the current one stays.
        Assertions.assertFalse(catalog.relationExists(PartitionedTable.PROCESSED_EVENTS.partitionName(oldMonth)));
        Assertions.assertEquals(List.of("drop-new"), jdbcTemplate.queryForList(
                "SELECT dedup_key FROM processed_events WHERE consumer_name = 'drop-consumer'", String.class));
        // The 2001 row had no partition: it is deleted from the default partition instead.
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events_default WHERE dedup_key LIKE 'drop-default-%'", Integer.class));
        // audit_log (400 days): the 30-month-old partition goes; the 20-month-old row, which had no partition of its
        // own, is deleted from the default partition; the current month stays.
        Assertions.assertFalse(catalog.relationExists(PartitionedTable.AUDIT_LOG.partitionName(oldMonth)));
        Assertions.assertEquals(List.of(), jdbcTemplate.queryForList(
                "SELECT target_id FROM audit_log WHERE action = 'drop.test'", String.class));
        Assertions.assertTrue(catalog.relationExists(PartitionedTable.AUDIT_LOG.partitionName(now.toLocalDate())));
        // order_status_history keeps everything without a retention.
        Assertions.assertTrue(catalog.relationExists(PartitionedTable.ORDER_STATUS_HISTORY.partitionName(oldMonth)));
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_status_history WHERE order_id = ?", Integer.class, orderId));
    }


    @Test
    void maintain_shouldSkipATableWhileAnotherInstanceHoldsItsLock() throws Exception {
        LocalDateTime inEightYears = LocalDateTime.now().plusYears(8);
        String auditPartition = PartitionedTable.AUDIT_LOG.partitionName(inEightYears.toLocalDate());
        String historyPartition = PartitionedTable.ORDER_STATUS_HISTORY.partitionName(inEightYears.toLocalDate());
        try (Connection otherInstance = dataSource.getConnection()) {
            otherInstance.setAutoCommit(false);
            try (PreparedStatement lock = otherInstance.prepareStatement(
                    "SELECT pg_advisory_xact_lock(?, hashtext('audit_log'))")) {
                lock.setInt(1, PartitionMaintenanceService.ADVISORY_LOCK_NAMESPACE);
                lock.execute();
            }

            withoutCleanup(new SimpleMeterRegistry()).maintainPartitions(inEightYears);

            Assertions.assertFalse(catalog.relationExists(auditPartition), "audit_log is skipped while locked");
            Assertions.assertTrue(catalog.relationExists(historyPartition), "the other tables still run");
            otherInstance.rollback();
        }

        withoutCleanup(new SimpleMeterRegistry()).maintainPartitions(inEightYears);
        Assertions.assertTrue(catalog.relationExists(auditPartition));
    }


    @Test
    void maintain_whenAStepFails_shouldCountItAndContinueWithTheOtherTables() {
        LocalDateTime inTenYears = LocalDateTime.now().plusYears(10);
        // A table in the way of the audit_log partition's name.
        jdbcTemplate.execute("CREATE TABLE " + PartitionedTable.AUDIT_LOG.partitionName(inTenYears.toLocalDate())
                + " (unrelated INTEGER)");
        MeterRegistry registry = new SimpleMeterRegistry();

        withoutCleanup(registry).maintainPartitions(inTenYears);

        Assertions.assertEquals(1.0, registry.counter("onlineorder.partitions.maintenance.failures", "table", "audit_log")
                .count());
        Assertions.assertTrue(catalog.relationExists(
                PartitionedTable.PROCESSED_EVENTS.partitionName(inTenYears.toLocalDate())));
    }


    @Test
    void maintain_shouldGiveAHandMadePartitionItsDedupIndex() {
        LocalDate month = LocalDate.now().plusYears(12).withDayOfMonth(1);
        String partition = PartitionedTable.PROCESSED_EVENTS.partitionName(month);
        jdbcTemplate.execute("CREATE TABLE " + partition + " PARTITION OF processed_events FOR VALUES FROM ('"
                + month + " 00:00:00') TO ('" + month.plusMonths(1) + " 00:00:00')");
        Assertions.assertFalse(catalog.relationExists(partition + "_dedup_key"));

        withoutCleanup(new SimpleMeterRegistry()).maintainPartitions(LocalDateTime.now());

        Assertions.assertTrue(catalog.relationExists(partition + "_dedup_key"));
    }


    @Test
    void cleanup_shouldLeaveThePartitionedTablesToThePartitionJob() {
        LocalDateTime seventyDaysAgo = LocalDateTime.now().minusDays(70);
        withoutCleanup(new SimpleMeterRegistry()).maintainPartitions(seventyDaysAgo);
        insertProcessedEvent("cleanup-consumer", "cleanup-key", seventyDaysAgo);

        cleanupService.cleanupExpiredRecords();

        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE dedup_key = 'cleanup-key'", Integer.class),
                "no row-by-row delete on a partitioned table");
        Assertions.assertTrue(catalog.isPartitioned(PartitionedTable.PROCESSED_EVENTS));
    }


    @Test
    void metrics_shouldReportPartitionsAheadOldestMonthAndDefaultRows() {
        AtomicReference<LocalDateTime> clock = new AtomicReference<>(LocalDateTime.now());
        PartitionMetricsBinder binder = new PartitionMetricsBinder(catalog, jdbcTemplate, clock::get);
        MeterRegistry registry = new SimpleMeterRegistry();
        binder.bindTo(registry);

        double future = registry.get("onlineorder.partitions.future").tag("table", "order_status_history").gauge().value();
        double count = registry.get("onlineorder.partitions.count").tag("table", "order_status_history").gauge().value();
        Assertions.assertTrue(future >= 3, "the migration created three months ahead: " + future);
        Assertions.assertTrue(count >= 4, "count " + count);
        Assertions.assertTrue(registry.get("onlineorder.partitions.oldest_age").tag("table", "order_status_history")
                .timeGauge().value() > 0);
        double defaultRows = registry.get("onlineorder.partitions.default_rows").tag("table", "audit_log").gauge().value();

        jdbcTemplate.update("""
                INSERT INTO audit_log (actor_role, action, target_type, target_id, occurred_at)
                VALUES ('ROLE_ADMIN', 'metrics.test', 'order', 'stray', TIMESTAMP '1999-12-31 00:00:00')
                """);
        Assertions.assertEquals(defaultRows, registry.get("onlineorder.partitions.default_rows")
                .tag("table", "audit_log").gauge().value(), "read at most every 30 seconds");
        clock.set(clock.get().plusSeconds(31));
        Assertions.assertEquals(defaultRows + 1, registry.get("onlineorder.partitions.default_rows")
                .tag("table", "audit_log").gauge().value());
        jdbcTemplate.update("DELETE FROM audit_log WHERE action = 'metrics.test'");
    }


    private PartitionMaintenanceService withoutCleanup(MeterRegistry registry) {
        return new PartitionMaintenanceService(true, false, 3, Duration.ofSeconds(5), Duration.ofDays(14),
                Duration.ZERO, Duration.ZERO, 1000, catalog, jdbcTemplate, transactionManager, registry);
    }


    private void insertProcessedEvent(String consumer, String dedupKey, LocalDateTime processedAt) {
        jdbcTemplate.update("""
                INSERT INTO processed_events (consumer_name, event_id, dedup_key, event_type, processed_at)
                VALUES (?, ?, ?, 'order.paid', ?)
                """, consumer, dedupKey, dedupKey, Timestamp.valueOf(processedAt));
    }


    private String partitionOf(String dedupKey) {
        return jdbcTemplate.queryForObject(
                "SELECT tableoid::regclass::text FROM processed_events WHERE dedup_key = ?", String.class, dedupKey);
    }


    private long insertOrder(LocalDateTime createdAt) {
        Long customerId = jdbcTemplate.queryForObject("""
                INSERT INTO customers (email, password) VALUES (?, 'x') RETURNING id
                """, Long.class, "partition-" + System.nanoTime() + "@example.com");
        return jdbcTemplate.queryForObject("""
                INSERT INTO orders (customer_id, total_price, status, created_at, order_no)
                VALUES (?, 5, 'PLACED', ?, (SELECT COALESCE(MAX(order_no), 0) + 1 FROM orders))
                RETURNING id
                """, Long.class, customerId, Timestamp.valueOf(createdAt));
    }
}
