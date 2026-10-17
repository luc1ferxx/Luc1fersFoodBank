package db.migration;


import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;


import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;


/**
 * {@code snowflake_worker_leases}: one row per Snowflake worker id an instance can lease (0-1022; 1023 is the V10_1
 * backfill's), used with app.ids.worker-id-source=lease (WorkerIdLeaseManager).
 *
 * <p>{@code owner} and {@code lease_until} (database time) say who holds the worker id until when; a row whose lease
 * is null or past is free. {@code issued_until_ms} bounds the timestamps (epoch milliseconds) of the ids issued under
 * the row so far: the holder never issues past it, and the next holder starts after it, which keeps two holders of
 * one worker id apart even if their leases overlap.
 *
 * <p>A Java migration only to fill the 1023 rows the same way on PostgreSQL and H2 (generate_series against
 * SYSTEM_RANGE).
 */
public class V10_2__Snowflake_worker_leases extends BaseJavaMigration {

    static final int MAX_LEASED_WORKER_ID = 1022;


    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE snowflake_worker_leases
                    (
                        worker_id       BIGINT PRIMARY KEY       NOT NULL,
                        owner           TEXT,
                        lease_until     TIMESTAMP WITH TIME ZONE,
                        issued_until_ms BIGINT                   NOT NULL DEFAULT 0,
                        acquired_at     TIMESTAMP WITH TIME ZONE,
                        renewed_at      TIMESTAMP WITH TIME ZONE,
                        CONSTRAINT chk_snowflake_worker_leases_worker_id CHECK (worker_id BETWEEN 0 AND 1022)
                    )
                    """);
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO snowflake_worker_leases (worker_id) VALUES (?)")) {
            for (int workerId = 0; workerId <= MAX_LEASED_WORKER_ID; workerId++) {
                insert.setLong(1, workerId);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }
}
