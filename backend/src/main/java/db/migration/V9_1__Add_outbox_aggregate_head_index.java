package db.migration;


import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;


/**
 * Adds the index behind the outbox's head-of-line claim on PostgreSQL (V9 added outbox_events.aggregate_sequence).
 *
 * <p>The claim only takes an event when no earlier event of its aggregate is unpublished:
 * {@code NOT EXISTS (SELECT 1 FROM outbox_events earlier WHERE earlier.aggregate_type = e.aggregate_type AND
 * earlier.aggregate_id = e.aggregate_id AND earlier.aggregate_sequence < e.aggregate_sequence AND earlier.status IN
 * ('PENDING', 'PROCESSING', 'FAILED'))}. A partial index over exactly those statuses, keyed by (aggregate_type,
 * aggregate_id, aggregate_sequence), answers each probe from the aggregate's few unpublished entries, however many
 * published rows the table holds; the statuses are literals in the query so that PostgreSQL can prove the index
 * applies. The blocked-aggregate gauges use it as well.
 *
 * <p>The index is UNIQUE: two unpublished events of one aggregate with the same sequence would mean the sequence
 * allocator is broken, and the insert of the second fails instead of the publisher sending them in an arbitrary order.
 * Published rows are outside the predicate, so it costs nothing for them.
 *
 * <p>A Java migration because H2, which backs the {@code h2} profile, has no partial indexes: on anything but PostgreSQL
 * it only records its version (H2 is for local runs; its claim scans). Like V3, the plain CREATE INDEX runs inside
 * Flyway's transaction and blocks outbox writes (checkouts) for the length of the build; see V3 for why not
 * CONCURRENTLY.
 */
public class V9_1__Add_outbox_aggregate_head_index extends BaseJavaMigration {

    static final String INDEX_NAME = "idx_outbox_events_unpublished_aggregate";

    private static final Logger LOGGER = LoggerFactory.getLogger(V9_1__Add_outbox_aggregate_head_index.class);


    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        if (!"PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
            LOGGER.info(
                    "Skipping {}: partial indexes are only created on PostgreSQL",
                    V9_1__Add_outbox_aggregate_head_index.class.getSimpleName()
            );
            return;
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE UNIQUE INDEX " + INDEX_NAME
                    + " ON outbox_events (aggregate_type, aggregate_id, aggregate_sequence)"
                    + " WHERE status IN ('PENDING', 'PROCESSING', 'FAILED')");
        }
    }
}
