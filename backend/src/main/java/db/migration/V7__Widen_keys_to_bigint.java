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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


/**
 * Widens every primary key and every column that holds a row id (foreign keys and the id references the fulfilment
 * modules keep without a foreign key) from 32-bit INTEGER to BIGINT, together with the sequences behind the SERIAL
 * keys. V1-V6 created them as SERIAL/INTEGER, which stops at 2,147,483,647: the first insert past that fails, and
 * Java already carries every id as a {@code Long}.
 *
 * <p>This is a Java migration because the sequence part differs: on PostgreSQL a SERIAL column's sequence is a
 * separate object created {@code AS integer} ({@code ALTER SEQUENCE ... AS bigint} lifts its maximum to the bigint
 * maximum), while H2's identity columns keep the INTEGER maximum after {@code SET DATA TYPE BIGINT} until it is raised
 * with {@code ALTER COLUMN ... SET MAXVALUE}. The column changes themselves are the same statement on both.
 *
 * <p>Columns that are already BIGINT are skipped, so the migration also completes on a database where some of them
 * were widened by hand ahead of it (see docs/operations.md section 7 for the online procedure on large tables).
 *
 * <p>Production caveat: on PostgreSQL {@code ALTER COLUMN ... TYPE bigint} rewrites the table and its indexes under an
 * ACCESS EXCLUSIVE lock, and the referencing foreign keys are validated again; Flyway runs this migration in one
 * transaction, so every table stays locked until the whole migration commits. Each table is altered with one
 * statement (one rewrite per table, not one per column). That is fine for small or new databases; for large tables
 * widen them online first (new column, backfill in batches, swap) and let this migration skip them.
 */
public class V7__Widen_keys_to_bigint extends BaseJavaMigration {

    /**
     * Every id column of V1-V6, by table, in an order where referenced tables come first.
     */
    static final Map<String, List<String>> ID_COLUMNS = idColumns();

    private static final Logger LOGGER = LoggerFactory.getLogger(V7__Widen_keys_to_bigint.class);


    private static Map<String, List<String>> idColumns() {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        columns.put("customers", List.of("id"));
        columns.put("restaurants", List.of("id"));
        columns.put("menu_items", List.of("id", "restaurant_id"));
        columns.put("carts", List.of("id", "customer_id"));
        columns.put("order_items", List.of("id", "menu_item_id", "cart_id"));
        columns.put("orders", List.of("id", "customer_id", "restaurant_id"));
        columns.put("order_history_items", List.of("id", "order_id", "menu_item_id", "restaurant_id"));
        columns.put("authorities", List.of("id"));
        columns.put("outbox_events", List.of("id"));
        columns.put("processed_events", List.of("id"));
        columns.put("order_notifications", List.of("id", "order_id", "customer_id"));
        columns.put("dead_letter_events", List.of("id"));
        columns.put("idempotency_requests", List.of("id", "customer_id", "order_id"));
        columns.put("merchant_accounts", List.of("id", "customer_id", "restaurant_id"));
        columns.put("merchant_orders", List.of("id", "order_id", "restaurant_id", "customer_id"));
        columns.put("order_deadlines", List.of("id", "order_id"));
        columns.put("riders", List.of("id"));
        columns.put("deliveries", List.of("id", "order_id", "customer_id", "restaurant_id", "rider_id"));
        columns.put("dispatch_orders", List.of("order_id"));
        return columns;
    }


    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        boolean postgres = "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
        for (Map.Entry<String, List<String>> table : ID_COLUMNS.entrySet()) {
            List<String> narrowColumns = new ArrayList<>();
            for (String column : table.getValue()) {
                if (!"bigint".equals(dataType(connection, table.getKey(), column))) {
                    narrowColumns.add(column);
                }
            }
            if (postgres) {
                widenOnPostgres(connection, table.getKey(), narrowColumns);
            } else {
                widenOnH2(connection, table.getKey(), narrowColumns);
            }
        }
    }


    private static void widenOnPostgres(Connection connection, String table, List<String> narrowColumns)
            throws SQLException {
        if (!narrowColumns.isEmpty()) {
            List<String> clauses = new ArrayList<>();
            for (String column : narrowColumns) {
                clauses.add("ALTER COLUMN " + column + " SET DATA TYPE BIGINT");
            }
            execute(connection, "ALTER TABLE " + table + " " + String.join(", ", clauses));
        }
        // The sequence is checked even when the column was already BIGINT: widening the column by hand leaves it
        // AS integer. dispatch_orders has no id column (its key is the order id), so it has no sequence.
        if (!ID_COLUMNS.get(table).contains("id")) {
            return;
        }
        String sequence = serialSequence(connection, table);
        if (sequence != null && !"bigint".equals(sequenceDataType(connection, sequence))) {
            execute(connection, "ALTER SEQUENCE " + sequence + " AS BIGINT");
        }
    }


    private static void widenOnH2(Connection connection, String table, List<String> narrowColumns)
            throws SQLException {
        for (String column : narrowColumns) {
            execute(connection, "ALTER TABLE " + table + " ALTER COLUMN " + column + " SET DATA TYPE BIGINT");
        }
        IdentityState identity = identityState(connection, table);
        if (identity == null || identity.maximum() == Long.MAX_VALUE) {
            return;
        }
        execute(connection, "ALTER TABLE " + table + " ALTER COLUMN id SET MAXVALUE " + Long.MAX_VALUE);
        if (identity.base() == null) {
            // The identity already ran out of numbers (H2 then reports no base value), and a raised maximum does not
            // revive it: continue right after the old maximum, the last value it handed out.
            execute(connection, "ALTER TABLE " + table + " ALTER COLUMN id RESTART WITH " + (identity.maximum() + 1));
        }
    }


    private static String dataType(Connection connection, String table, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT data_type
                FROM information_schema.columns
                WHERE LOWER(table_name) = ?
                  AND LOWER(column_name) = ?
                  AND table_schema = CURRENT_SCHEMA
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("Column " + table + "." + column + " does not exist");
                }
                return result.getString(1).toLowerCase(Locale.ROOT);
            }
        }
    }


    /**
     * @return the state of H2's identity on {@code table.id}, or null when the table has no identity column
     */
    private static IdentityState identityState(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT identity_base, identity_maximum
                FROM information_schema.columns
                WHERE LOWER(table_name) = ?
                  AND LOWER(column_name) = 'id'
                  AND table_schema = CURRENT_SCHEMA
                  AND is_identity = 'YES'
                """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                long base = result.getLong(1);
                Long nextValue = result.wasNull() ? null : base;
                return new IdentityState(nextValue, result.getLong(2));
            }
        }
    }


    /**
     * @param base    the next value the identity hands out; null once it ran out of numbers
     * @param maximum its maximum value
     */
    private record IdentityState(Long base, long maximum) {
    }


    /**
     * @return the schema-qualified name of the sequence behind {@code table.id} (a SERIAL column), or null
     */
    private static String serialSequence(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_get_serial_sequence(?, 'id')")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }


    private static String sequenceDataType(Connection connection, String sequence) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT format_type(seqtypid, NULL) FROM pg_sequence WHERE seqrelid = ?::regclass")) {
            statement.setString(1, sequence);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("Sequence " + sequence + " does not exist");
                }
                return result.getString(1).toLowerCase(Locale.ROOT);
            }
        }
    }


    private static void execute(Connection connection, String sql) throws SQLException {
        LOGGER.info("{}", sql);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
