package db.migration;


import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;


/**
 * Adds the index the outbox publisher's claim query needs on PostgreSQL.
 *
 * <p>The claim ({@code WHERE status = 'PENDING' OR (status = 'PROCESSING' AND updated_at <= ?) ORDER BY id LIMIT n
 * FOR UPDATE SKIP LOCKED}) walked the primary key and filtered out every PUBLISHED row with a lower id: with 1M
 * PUBLISHED rows ahead of a 100k PENDING backlog each claim read 50k pages and took 115-225 ms, every 500 ms. A
 * partial index over the unpublished rows only serves the same query from a handful of pages (0.1 ms); an index on
 * (status, id) did not help because the OR keeps the planner on the primary key.
 *
 * <p>The query binds the two statuses as parameters. A generic plan cannot prove that bound statuses fall inside the
 * index predicate, but PostgreSQL keeps choosing custom plans for this statement because the generic plan's
 * estimated cost (a primary-key scan over a large share of the table) stays far above the custom plan's, so pgjdbc's
 * server-side prepared statements use the index too. Measured on PostgreSQL 15 with plan_cache_mode=auto.
 *
 * <p>This is a Java migration because H2, which backs the {@code h2} profile, has no partial indexes: on anything but
 * PostgreSQL the migration only records its version.
 *
 * <p>The index is built with a plain CREATE INDEX inside Flyway's transaction, which blocks outbox writes (checkouts)
 * for the length of the build: 0.8 s measured for 1.1M rows. CREATE INDEX CONCURRENTLY would avoid that, but it waits
 * for every open transaction in the database, and Flyway holds one open on its schema-history connection for its
 * transactional advisory lock (the PostgreSQL default), so the build never finishes. Running CONCURRENTLY migrations
 * needs {@code spring.flyway.postgresql.transactional-lock=false} in addition to the unpooled Flyway connection.
 */
public class V3__Add_outbox_unpublished_index extends BaseJavaMigration {

    static final String INDEX_NAME = "idx_outbox_events_unpublished_id";

    private static final Logger LOGGER = LoggerFactory.getLogger(V3__Add_outbox_unpublished_index.class);


    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        if (!isPostgreSql(connection)) {
            LOGGER.info(
                    "Skipping {}: partial indexes are only created on PostgreSQL",
                    V3__Add_outbox_unpublished_index.class.getSimpleName()
            );
            return;
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE INDEX " + INDEX_NAME + " ON outbox_events (id) WHERE status IN ('PENDING', 'PROCESSING')"
            );
        }
    }


    private static boolean isPostgreSql(Connection connection) throws SQLException {
        return "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
    }
}
