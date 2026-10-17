package db.migration;


import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;


/**
 * Turns the three append-only tables that grow with traffic into tables partitioned by month on PostgreSQL:
 * {@code audit_log} and {@code order_status_history} by {@code occurred_at}, {@code processed_events} by
 * {@code processed_at}. Retention then drops a whole month (PartitionMaintenanceService) instead of deleting rows in
 * batches, and the hot recent month stays small. {@code outbox_events} is the next candidate (docs/adr/0001).
 *
 * <p>Per table: the table is renamed, its keys and indexes are dropped (their names are reused), a partitioned table
 * with the original name is created {@code LIKE} it (every column with its type, NOT NULL and default, so the same id
 * sequence, and the CHECK constraints), with one partition per month that has rows, the current month and the next
 * three, plus a DEFAULT partition for rows outside every range; the rows are copied with their ids, the foreign keys
 * and indexes are created on the partitioned table (and with it on every partition), and the old table is dropped.
 * Copying the columns from the table itself keeps any column an earlier migration added. A table that is already
 * partitioned is skipped.
 *
 * <p>What partitioning changes:
 * <ul>
 *     <li>The primary key must contain the partition key, so it becomes {@code (id, occurred_at)} /
 *     {@code (id, processed_at)}. The ids still come from one sequence and stay unique; no foreign key references
 *     these tables, and the Spring Data entities keep {@code id} as their identifier (rows are only inserted).</li>
 *     <li>{@code order_status_history} keeps its foreign key to {@code orders} (ON DELETE CASCADE) and its checks:
 *     PostgreSQL supports foreign keys from a partitioned table.</li>
 *     <li>{@code processed_events} loses {@code UNIQUE (consumer_name, dedup_key)}: a unique constraint of a
 *     partitioned table must include the partition key, and {@code (consumer_name, dedup_key, processed_at)} would
 *     accept a redelivered event in a later month as new. Deduplication is serialized instead
 *     (ProcessedEventDedupImpl: a transaction-scoped advisory lock on the key, then an insert only if no partition
 *     holds the key), and every partition carries its own {@code UNIQUE (consumer_name, dedup_key)} index, which serves that
 *     lookup and still rejects a duplicate within one month at the database level. The maintenance job gives every
 *     new partition this index.</li>
 *     <li>A query that does not filter on the partition key reads every partition (an index probe each).</li>
 * </ul>
 *
 * <p>This is a Java migration because the partition list depends on the data and on today's date, and because H2,
 * which backs the {@code h2} profile, keeps plain tables (it has no declarative partitioning): there the migration only
 * records its version.
 *
 * <p>Production caveat: the copy runs inside Flyway's transaction and holds an ACCESS EXCLUSIVE lock on each table
 * until the migration commits, so inserts (checkout, status changes, every consumer) wait. That is fine for small
 * tables; for large ones see docs/operations.md section 8 (attach the old table as one partition instead of copying).
 */
public class V10__Partition_append_only_tables extends BaseJavaMigration {

    /**
     * Months after the current one that get a partition right away; the maintenance job keeps this many ahead.
     */
    static final int MONTHS_AHEAD = 3;

    static final List<TableSpec> TABLES = List.of(
            new TableSpec(
                    "audit_log",
                    "occurred_at",
                    List.of(),
                    List.of(
                            "CREATE INDEX idx_audit_log_target_id ON audit_log (target_type, target_id, id)",
                            "CREATE INDEX idx_audit_log_actor_id ON audit_log (actor_customer_id, id)"
                    ),
                    false
            ),
            new TableSpec(
                    "order_status_history",
                    "occurred_at",
                    List.of(
                            "ALTER TABLE order_status_history ADD CONSTRAINT fk_order_status_history_order "
                                    + "FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE"
                    ),
                    List.of(
                            "CREATE INDEX idx_order_status_history_order_id_id ON order_status_history (order_id, id)"
                    ),
                    false
            ),
            new TableSpec(
                    "processed_events",
                    "processed_at",
                    List.of(),
                    List.of(),
                    true
            )
    );

    private static final Logger LOGGER = LoggerFactory.getLogger(V10__Partition_append_only_tables.class);


    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        if (!"PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
            LOGGER.info("Skipping {}: tables are only partitioned on PostgreSQL",
                    V10__Partition_append_only_tables.class.getSimpleName());
            return;
        }
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        for (TableSpec table : TABLES) {
            if (isPartitioned(connection, table.name())) {
                LOGGER.info("{} is already partitioned; skipping it", table.name());
                continue;
            }
            partition(connection, table, currentMonth);
        }
    }


    private static void partition(Connection connection, TableSpec table, LocalDate currentMonth) throws SQLException {
        String legacy = table.name() + "_unpartitioned";
        execute(connection, "ALTER TABLE " + table.name() + " RENAME TO " + legacy);
        dropKeysAndIndexes(connection, legacy);
        String sequence = serialSequence(connection, legacy);
        execute(connection, "ALTER SEQUENCE " + sequence + " OWNED BY NONE");

        execute(connection, "CREATE TABLE " + table.name() + " (LIKE " + legacy
                + " INCLUDING DEFAULTS INCLUDING CONSTRAINTS, CONSTRAINT " + table.name()
                + "_pkey PRIMARY KEY (id, " + table.partitionColumn() + ")) PARTITION BY RANGE ("
                + table.partitionColumn() + ")");
        execute(connection, "ALTER SEQUENCE " + sequence + " OWNED BY " + table.name() + ".id");

        TreeSet<LocalDate> months = monthsWithRows(connection, legacy, table.partitionColumn());
        for (int ahead = 0; ahead <= MONTHS_AHEAD; ahead++) {
            months.add(currentMonth.plusMonths(ahead));
        }
        List<String> partitions = new ArrayList<>();
        for (LocalDate month : months) {
            String partition = partitionName(table.name(), month);
            execute(connection, "CREATE TABLE " + partition + " PARTITION OF " + table.name()
                    + " FOR VALUES FROM ('" + month + " 00:00:00') TO ('" + month.plusMonths(1) + " 00:00:00')");
            partitions.add(partition);
        }
        String defaultPartition = table.name() + "_default";
        execute(connection, "CREATE TABLE " + defaultPartition + " PARTITION OF " + table.name() + " DEFAULT");
        partitions.add(defaultPartition);

        // LIKE gave the two tables the same columns in the same order.
        execute(connection, "INSERT INTO " + table.name() + " SELECT * FROM " + legacy);
        for (String foreignKey : table.foreignKeys()) {
            execute(connection, foreignKey);
        }
        for (String index : table.indexes()) {
            execute(connection, index);
        }
        if (table.dedupKeyPerPartition()) {
            for (String partition : partitions) {
                execute(connection, "CREATE UNIQUE INDEX " + partition + "_dedup_key ON " + partition
                        + " (consumer_name, dedup_key)");
            }
        }
        execute(connection, "DROP TABLE " + legacy);
        LOGGER.info("Partitioned {} by month on {}: {} partitions", table.name(), table.partitionColumn(),
                partitions.size());
    }


    /**
     * Drops the primary key, unique constraints and indexes of the renamed table, so the partitioned table can take
     * their names (and LIKE copies none of them). Its foreign keys and checks keep table-scoped names and go with the
     * table.
     */
    private static void dropKeysAndIndexes(Connection connection, String legacy) throws SQLException {
        List<String> constraints = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conname
                FROM pg_constraint
                WHERE conrelid = to_regclass(?)
                  AND contype IN ('p', 'u')
                ORDER BY conname
                """)) {
            statement.setString(1, legacy);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    constraints.add(result.getString(1));
                }
            }
        }
        for (String constraint : constraints) {
            execute(connection, "ALTER TABLE " + legacy + " DROP CONSTRAINT " + constraint);
        }
        List<String> indexes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT index_class.relname
                FROM pg_index
                JOIN pg_class index_class ON index_class.oid = pg_index.indexrelid
                WHERE pg_index.indrelid = to_regclass(?)
                ORDER BY index_class.relname
                """)) {
            statement.setString(1, legacy);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    indexes.add(result.getString(1));
                }
            }
        }
        for (String index : indexes) {
            execute(connection, "DROP INDEX " + index);
        }
    }


    private static TreeSet<LocalDate> monthsWithRows(Connection connection, String legacy, String column)
            throws SQLException {
        TreeSet<LocalDate> months = new TreeSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT DISTINCT CAST(date_trunc('month', " + column
                     + ") AS DATE) FROM " + legacy)) {
            while (result.next()) {
                months.add(result.getDate(1).toLocalDate());
            }
        }
        return months;
    }


    static String partitionName(String table, LocalDate month) {
        return String.format(Locale.ROOT, "%s_p%04d_%02d", table, month.getYear(), month.getMonthValue());
    }


    private static boolean isPartitioned(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM pg_partitioned_table WHERE partrelid = to_regclass(?)")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }


    private static String serialSequence(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_get_serial_sequence(?, 'id')")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getString(1) == null) {
                    throw new IllegalStateException(table + ".id has no sequence");
                }
                return result.getString(1);
            }
        }
    }


    private static void execute(Connection connection, String sql) throws SQLException {
        LOGGER.debug("{}", sql);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }


    /**
     * @param foreignKeys          ALTER TABLE statements that give the partitioned table the old table's foreign keys
     *                             (LIKE copies CHECK constraints only)
     * @param indexes              the old table's indexes other than its keys, created on the partitioned table
     * @param dedupKeyPerPartition give every partition UNIQUE (consumer_name, dedup_key) (processed_events)
     */
    record TableSpec(
            String name,
            String partitionColumn,
            List<String> foreignKeys,
            List<String> indexes,
            boolean dedupKeyPerPartition
    ) {
    }
}
