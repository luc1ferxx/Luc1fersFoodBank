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
import java.sql.Timestamp;


/**
 * Adds {@code orders.order_no}, the public order number: a 64-bit Snowflake id (SnowflakeIdGenerator) that the
 * checkout assigns from then on. Customers see it instead of the internal {@code id}, which counts orders and stays the
 * key every table and API path uses.
 *
 * <p>Existing orders get a number in the same layout, built from their {@code created_at}, with worker id 1023, which
 * no running instance uses (live worker ids are 0-1022). The numbers are assigned in id order and strictly increase,
 * so they are unique among themselves and cannot collide with any number a live instance issues. Orders that already
 * have a number are left alone, and new numbers start after the highest existing one, so the migration can resume
 * after a manual partial backfill (docs/operations.md section 9). Then the column becomes NOT NULL and UNIQUE
 * ({@code uq_orders_order_no}, skipped if a constraint of that name exists).
 *
 * <p>This is a Java migration because the backfill computes the bit layout row by row; the statements run on
 * PostgreSQL and H2 alike. The layout constants mirror SnowflakeIdGenerator's ({@code ScaleMigration*Tests} and
 * {@code BackfillSequenceTests} decode the backfilled numbers with it) and must not change with it: a migration
 * describes the past.
 */
public class V10_1__Add_order_numbers extends BaseJavaMigration {

    static final long EPOCH_MILLIS = 1_577_836_800_000L;
    static final int WORKER_ID_BITS = 10;
    static final int SEQUENCE_BITS = 12;
    static final int MAX_SEQUENCE = (1 << SEQUENCE_BITS) - 1;
    static final long BACKFILL_WORKER_ID = 1023L;
    static final int BATCH_SIZE = 1000;

    private static final Logger LOGGER = LoggerFactory.getLogger(V10_1__Add_order_numbers.class);


    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        execute(connection, "ALTER TABLE orders ADD COLUMN IF NOT EXISTS order_no BIGINT");
        int backfilled = backfill(connection);
        execute(connection, "ALTER TABLE orders ALTER COLUMN order_no SET NOT NULL");
        if (!constraintExists(connection)) {
            execute(connection, "ALTER TABLE orders ADD CONSTRAINT uq_orders_order_no UNIQUE (order_no)");
        }
        LOGGER.info("Assigned order numbers to {} existing order(s)", backfilled);
    }


    private static int backfill(Connection connection) throws SQLException {
        BackfillSequence numbers = new BackfillSequence(highestOrderNo(connection));
        int backfilled = 0;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, created_at FROM orders WHERE order_no IS NULL ORDER BY id");
             PreparedStatement update = connection.prepareStatement("UPDATE orders SET order_no = ? WHERE id = ?")) {
            select.setFetchSize(BATCH_SIZE);
            try (ResultSet rows = select.executeQuery()) {
                int pending = 0;
                while (rows.next()) {
                    Timestamp createdAt = rows.getTimestamp(2);
                    update.setLong(1, numbers.next(createdAt.getTime()));
                    update.setLong(2, rows.getLong(1));
                    update.addBatch();
                    backfilled++;
                    if (++pending == BATCH_SIZE) {
                        update.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    update.executeBatch();
                }
            }
        }
        return backfilled;
    }


    private static Long highestOrderNo(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT MAX(order_no) FROM orders")) {
            result.next();
            long value = result.getLong(1);
            return result.wasNull() ? null : value;
        }
    }


    private static boolean constraintExists(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM information_schema.table_constraints
                     WHERE LOWER(table_name) = 'orders'
                       AND LOWER(constraint_name) = 'uq_orders_order_no'
                     """)) {
            result.next();
            return result.getLong(1) > 0;
        }
    }


    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }


    /**
     * Strictly increasing backfill numbers: the creation millisecond (never earlier than the previous number's), worker
     * id 1023, and a sequence that counts up within a millisecond and moves to the next one when it runs out.
     */
    static final class BackfillSequence {

        private long lastElapsed;
        private int lastSequence;


        /**
         * @param after numbers start in a later millisecond than this existing number; null when there is none
         */
        BackfillSequence(Long after) {
            if (after == null) {
                lastElapsed = -1L;
                lastSequence = MAX_SEQUENCE;
            } else {
                lastElapsed = after >>> (WORKER_ID_BITS + SEQUENCE_BITS);
                lastSequence = MAX_SEQUENCE;
            }
        }


        long next(long createdAtEpochMillis) {
            long elapsed = Math.max(0L, createdAtEpochMillis - EPOCH_MILLIS);
            int sequence;
            if (elapsed > lastElapsed) {
                sequence = 0;
            } else if (lastSequence < MAX_SEQUENCE) {
                elapsed = lastElapsed;
                sequence = lastSequence + 1;
            } else {
                elapsed = lastElapsed + 1;
                sequence = 0;
            }
            lastElapsed = elapsed;
            lastSequence = sequence;
            return (elapsed << (WORKER_ID_BITS + SEQUENCE_BITS)) | (BACKFILL_WORKER_ID << SEQUENCE_BITS) | sequence;
        }
    }
}
