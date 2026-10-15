package com.laioffer.onlineorder;


import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


@Testcontainers(disabledWithoutDocker = true)
class OutboxUnpublishedIndexMigrationTests {

    private static final String INDEX_NAME = "idx_outbox_events_unpublished_id";

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("onlineorder")
            .withUsername("postgres")
            .withPassword("secret");


    @Test
    void migration_shouldBuildAValidPartialIndexOverUnpublishedEvents() throws SQLException {
        migrate();

        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT pg_get_indexdef(i.indexrelid), i.indisvalid
                     FROM pg_index i
                     JOIN pg_class c ON c.oid = i.indexrelid
                     WHERE c.relname = '%s'
                     """.formatted(INDEX_NAME))) {
            Assertions.assertTrue(resultSet.next(), "index " + INDEX_NAME + " should exist after the migration");
            String definition = resultSet.getString(1);
            Assertions.assertTrue(resultSet.getBoolean(2), "index should be valid: " + definition);
            Assertions.assertTrue(definition.contains("USING btree (id)"), definition);
            Assertions.assertTrue(definition.contains("WHERE"), definition);
            Assertions.assertTrue(definition.contains("'PENDING'") && definition.contains("'PROCESSING'"), definition);
            Assertions.assertFalse(resultSet.next(), "exactly one index named " + INDEX_NAME);
        }
    }


    @Test
    void claimQuery_withBoundStatuses_shouldScanThePartialIndexInsteadOfThePrimaryKey() throws SQLException {
        migrate();

        try (Connection connection = connect()) {
            seedOutbox(connection, 5000, 100);

            // Spring Data JDBC sends the statuses and the staleness cut-off as bind parameters, exactly like this.
            String explain = """
                    EXPLAIN SELECT *
                    FROM outbox_events
                    WHERE status = ? OR (status = ? AND updated_at <= ?)
                    ORDER BY id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """;
            List<String> plan = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(explain)) {
                statement.setString(1, "PENDING");
                statement.setString(2, "PROCESSING");
                statement.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now().minusSeconds(30)));
                statement.setInt(4, 200);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        plan.add(resultSet.getString(1));
                    }
                }
            }

            String planText = String.join("\n", plan);
            Assertions.assertTrue(
                    planText.contains("Index Scan using " + INDEX_NAME),
                    "claim should be served by the partial index, but the plan was:\n" + planText
            );
            Assertions.assertFalse(planText.contains("outbox_events_pkey"), planText);
        }
    }


    private static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }


    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }


    /**
     * Published rows get the lower ids, as they do in production, so a primary-key walk would have to skip all of
     * them before reaching the first pending event.
     */
    private static void seedOutbox(Connection connection, int published, int pending) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE outbox_events");
            statement.execute("""
                    INSERT INTO outbox_events (aggregate_type, aggregate_id, event_id, topic, event_key, event_type,
                                               payload, status, attempts, created_at, updated_at, published_at)
                    SELECT 'ORDER', g, 'evt-' || g, 'order-events', 'order-' || g, 'ORDER_PAID', '{}',
                           'PUBLISHED', 1, now(), now(), now()
                    FROM generate_series(1, %d) g
                    """.formatted(published));
            statement.execute("""
                    INSERT INTO outbox_events (aggregate_type, aggregate_id, event_id, topic, event_key, event_type,
                                               payload, status, attempts, created_at, updated_at)
                    SELECT 'ORDER', g, 'evt-' || g, 'order-events', 'order-' || g, 'ORDER_PAID', '{}',
                           'PENDING', 0, now(), now()
                    FROM generate_series(%d, %d) g
                    """.formatted(published + 1, published + pending));
            statement.execute("ANALYZE outbox_events");
        }
    }
}
