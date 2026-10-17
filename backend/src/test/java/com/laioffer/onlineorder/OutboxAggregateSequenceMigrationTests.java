package com.laioffer.onlineorder;


import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;


import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * V9 (aggregate sequences, backlog numbering) and V9_1 (the head-of-line index) on PostgreSQL. Each test starts from an
 * empty schema.
 */
@Testcontainers(disabledWithoutDocker = true)
class OutboxAggregateSequenceMigrationTests {

    private static final String INDEX_NAME = "idx_outbox_events_unpublished_aggregate";

    /**
     * The claim exactly as OutboxEventRepository.lockNextBatchReadyToPublish sends it.
     */
    private static final String CLAIM = """
            SELECT *
            FROM outbox_events e
            WHERE (
                    e.status = ?
                 OR (
                        e.status = ?
                    AND e.updated_at <= ?
                 )
              )
              AND NOT EXISTS (
                    SELECT 1
                    FROM outbox_events earlier
                    WHERE earlier.aggregate_type = e.aggregate_type
                      AND earlier.aggregate_id = e.aggregate_id
                      AND earlier.aggregate_sequence < e.aggregate_sequence
                      AND earlier.status IN ('PENDING', 'PROCESSING', 'FAILED')
              )
            ORDER BY e.id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;

    @Container
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("onlineorder")
            .withUsername("postgres")
            .withPassword("secret");


    /**
     * The backlog waiting to be published is numbered per aggregate in id order. A FAILED row from before the upgrade
     * keeps NULL: it was terminal and held nothing back, and it still holds nothing back.
     */
    @Test
    void v9_shouldNumberTheUnpublishedBacklogPerAggregateAndStartTheCountersAfterIt() throws SQLException {
        Flyway flyway = flyway("8");
        flyway.clean();
        flyway.migrate();
        try (Connection connection = connect()) {
            insert(connection, "ORDER", 1, "PUBLISHED");
            insert(connection, "ORDER", 2, "PENDING");
            insert(connection, "ORDER", 1, "PENDING");
            insert(connection, "ORDER", 1, "FAILED");
            insert(connection, "DELIVERY", 1, "PROCESSING");
            insert(connection, "ORDER", 1, "PENDING");
            insert(connection, "ORDER", 3, "PUBLISHED");
        }

        flyway(null).migrate();

        try (Connection connection = connect()) {
            Assertions.assertEquals(
                    List.of("ORDER:1:PUBLISHED:null", "ORDER:2:PENDING:1", "ORDER:1:PENDING:1", "ORDER:1:FAILED:null",
                            "DELIVERY:1:PROCESSING:1", "ORDER:1:PENDING:2", "ORDER:3:PUBLISHED:null"),
                    rows(connection, """
                            SELECT aggregate_type || ':' || aggregate_id || ':' || status || ':'
                                   || COALESCE(CAST(aggregate_sequence AS TEXT), 'null')
                            FROM outbox_events ORDER BY id
                            """)
            );
            Assertions.assertEquals(
                    List.of("DELIVERY:1:1", "ORDER:1:2", "ORDER:2:1"),
                    rows(connection, """
                            SELECT aggregate_type || ':' || aggregate_id || ':' || last_sequence
                            FROM outbox_aggregate_sequences ORDER BY aggregate_type, aggregate_id
                            """)
            );

            // The heads are claimable; order 1's second PENDING event waits for its first, not for the old FAILED one.
            Assertions.assertEquals(List.of("ORDER:2:1", "ORDER:1:1"), claimable(connection));
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        UPDATE outbox_events SET status = 'PUBLISHED', published_at = now()
                        WHERE aggregate_type = 'ORDER' AND aggregate_sequence = 1
                        """);
            }
            Assertions.assertEquals(List.of("ORDER:1:2"), claimable(connection));

            // Retried, the old FAILED row is claimable at once (published unordered, as before V9).
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE outbox_events SET status = 'PENDING' WHERE status = 'FAILED'");
            }
            Assertions.assertEquals(List.of("ORDER:1:null", "ORDER:1:2"), claimable(connection));
        }
    }


    private static List<String> claimable(Connection connection) throws SQLException {
        List<String> claimed = new ArrayList<>();
        connection.setAutoCommit(false);
        try (PreparedStatement claim = connection.prepareStatement(CLAIM)) {
            bindClaim(claim);
            try (ResultSet result = claim.executeQuery()) {
                while (result.next()) {
                    Object sequence = result.getObject("aggregate_sequence");
                    claimed.add(result.getString("aggregate_type") + ":" + result.getLong("aggregate_id") + ":" + sequence);
                }
            }
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
        return claimed;
    }


    @Test
    void v9_1_shouldBuildAValidUniquePartialIndexOverTheUnpublishedEventsOfEachAggregate() throws SQLException {
        Flyway flyway = flyway(null);
        flyway.clean();
        flyway.migrate();

        try (Connection connection = connect()) {
            List<String> definitions = rows(connection, """
                    SELECT pg_get_indexdef(i.indexrelid) || ' valid=' || i.indisvalid
                    FROM pg_index i
                    JOIN pg_class c ON c.oid = i.indexrelid
                    WHERE c.relname = '%s'
                    """.formatted(INDEX_NAME));
            Assertions.assertEquals(1, definitions.size(), definitions::toString);
            String definition = definitions.get(0);
            Assertions.assertTrue(definition.startsWith("CREATE UNIQUE INDEX " + INDEX_NAME), definition);
            Assertions.assertTrue(definition.contains("(aggregate_type, aggregate_id, aggregate_sequence)"), definition);
            Assertions.assertTrue(definition.contains("'PENDING'") && definition.contains("'PROCESSING'")
                    && definition.contains("'FAILED'"), definition);
            Assertions.assertTrue(definition.endsWith("valid=true"), definition);

            insertWithSequence(connection, "ORDER", 7, "PUBLISHED", 1);
            insertWithSequence(connection, "ORDER", 7, "PENDING", 1);
            SQLException duplicate = Assertions.assertThrows(
                    SQLException.class, () -> insertWithSequence(connection, "ORDER", 7, "FAILED", 1));
            Assertions.assertEquals("23505", duplicate.getSQLState(), duplicate.getMessage());
        }
    }


    @Test
    void claim_shouldProbeTheHeadIndexForEarlierEventsInsteadOfScanningTheAggregate() throws SQLException {
        Flyway flyway = flyway(null);
        flyway.clean();
        flyway.migrate();

        try (Connection connection = connect()) {
            try (Statement statement = connection.createStatement()) {
                // 20k published events of 4k orders (5 each), then a backlog of 2 events for each of 200 orders.
                statement.execute("""
                        INSERT INTO outbox_events (aggregate_type, aggregate_id, aggregate_sequence, event_id, topic,
                                                   event_key, event_type, payload, status, attempts, created_at,
                                                   updated_at, published_at)
                        SELECT 'ORDER', (g - 1) / 5 + 1, (g - 1) % 5 + 1, 'evt-' || g, 'order-events',
                               CAST((g - 1) / 5 + 1 AS TEXT), 'order.paid', '{}', 'PUBLISHED', 1, now(), now(), now()
                        FROM generate_series(1, 20000) g
                        """);
                statement.execute("""
                        INSERT INTO outbox_events (aggregate_type, aggregate_id, aggregate_sequence, event_id, topic,
                                                   event_key, event_type, payload, status, attempts, created_at,
                                                   updated_at)
                        SELECT 'ORDER', 4000 + (g - 1) / 2 + 1, 6 + (g - 1) % 2, 'backlog-' || g, 'order-events',
                               CAST(4000 + (g - 1) / 2 + 1 AS TEXT), 'order.paid', '{}', 'PENDING', 0, now(), now()
                        FROM generate_series(1, 400) g
                        """);
                statement.execute("ANALYZE outbox_events");
            }

            // Whatever join the planner picks for the backlog at hand, it reads only unpublished rows: never the 20k
            // published ones through a sequential scan or the primary key.
            String planText = explainClaim(connection);
            Assertions.assertFalse(planText.contains("Seq Scan on outbox_events"), planText);
            Assertions.assertFalse(planText.contains("outbox_events_pkey"), planText);
            Assertions.assertTrue(planText.contains("idx_outbox_events_unpublished_id"),
                    "the claim should walk the unpublished rows by id:\n" + planText);

            // As a per-row probe (the plan for a backlog much larger than a batch), NOT EXISTS uses the head index: its
            // literal statuses match the index predicate.
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET enable_hashjoin = off");
                statement.execute("SET enable_mergejoin = off");
            }
            String probePlan = explainClaim(connection);
            Assertions.assertTrue(probePlan.contains("Index Only Scan using " + INDEX_NAME)
                            || probePlan.contains("Index Scan using " + INDEX_NAME),
                    "NOT EXISTS should probe " + INDEX_NAME + ":\n" + probePlan);
            try (Statement statement = connection.createStatement()) {
                statement.execute("RESET enable_hashjoin");
                statement.execute("RESET enable_mergejoin");
            }

            // Only the head of each of the 200 backlogged orders is claimable.
            List<String> claimed = new ArrayList<>();
            connection.setAutoCommit(false);
            try (PreparedStatement claim = connection.prepareStatement(CLAIM)) {
                bindClaim(claim);
                try (ResultSet result = claim.executeQuery()) {
                    while (result.next()) {
                        claimed.add(result.getLong("aggregate_id") + ":" + result.getLong("aggregate_sequence"));
                    }
                }
            }
            connection.rollback();
            Assertions.assertEquals(200, claimed.size());
            Assertions.assertTrue(claimed.stream().allMatch(row -> row.endsWith(":6")), claimed::toString);

            // The publisher's successor check after a batch (OutboxEventRepository.countWithPendingSuccessor) probes the
            // head index for each published event instead of scanning the table.
            List<String> successorPlan = new ArrayList<>();
            try (PreparedStatement explain = connection.prepareStatement("EXPLAIN " + SUCCESSOR_CHECK.formatted(
                    "SELECT id FROM outbox_events WHERE aggregate_id BETWEEN 4001 AND 4005 AND aggregate_sequence = 6"));
                 ResultSet result = explain.executeQuery()) {
                while (result.next()) {
                    successorPlan.add(result.getString(1));
                }
            }
            connection.rollback();
            String successorPlanText = String.join("\n", successorPlan);
            Assertions.assertFalse(successorPlanText.contains("Seq Scan on outbox_events later"), successorPlanText);
            Assertions.assertTrue(successorPlanText.contains(INDEX_NAME), successorPlanText);
            try (PreparedStatement count = connection.prepareStatement(SUCCESSOR_CHECK.formatted(
                    "SELECT id FROM outbox_events WHERE aggregate_id BETWEEN 4001 AND 4005 AND aggregate_sequence = 6"));
                 ResultSet result = count.executeQuery()) {
                Assertions.assertTrue(result.next());
                Assertions.assertEquals(5, result.getLong(1), "each of the five heads has its sequence-7 event waiting");
            }
            connection.rollback();
        }
    }


    /**
     * OutboxEventRepository.countWithPendingSuccessor with the id list replaced by {@code %s}.
     */
    private static final String SUCCESSOR_CHECK = """
            SELECT COUNT(*)
            FROM outbox_events published
            WHERE published.id IN (%s)
              AND published.aggregate_sequence IS NOT NULL
              AND EXISTS (
                    SELECT 1
                    FROM outbox_events later
                    WHERE later.aggregate_type = published.aggregate_type
                      AND later.aggregate_id = published.aggregate_id
                      AND later.aggregate_sequence > published.aggregate_sequence
                      AND later.status = 'PENDING'
              )
            """;


    private static String explainClaim(Connection connection) throws SQLException {
        List<String> plan = new ArrayList<>();
        try (PreparedStatement explain = connection.prepareStatement("EXPLAIN " + CLAIM)) {
            bindClaim(explain);
            try (ResultSet result = explain.executeQuery()) {
                while (result.next()) {
                    plan.add(result.getString(1));
                }
            }
        }
        return String.join("\n", plan);
    }


    private static void bindClaim(PreparedStatement statement) throws SQLException {
        statement.setString(1, "PENDING");
        statement.setString(2, "PROCESSING");
        statement.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now().minusSeconds(30)));
        statement.setInt(4, 500);
    }


    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }


    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }


    private static final Map<String, Integer> EVENT_NUMBERS = new LinkedHashMap<>();


    private static void insert(Connection connection, String aggregateType, long aggregateId, String status)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO outbox_events (aggregate_type, aggregate_id, event_id, topic, event_key, event_type,
                                           payload, status, attempts, created_at, updated_at, published_at)
                VALUES (?, ?, ?, 'order-events', ?, 'order.paid', '{}', ?, 0, now(), now(),
                        CASE WHEN ? = 'PUBLISHED' THEN now() END)
                """)) {
            statement.setString(1, aggregateType);
            statement.setLong(2, aggregateId);
            statement.setString(3, "evt-" + EVENT_NUMBERS.merge("all", 1, Integer::sum));
            statement.setString(4, String.valueOf(aggregateId));
            statement.setString(5, status);
            statement.setString(6, status);
            statement.executeUpdate();
        }
    }


    private static void insertWithSequence(
            Connection connection,
            String aggregateType,
            long aggregateId,
            String status,
            long sequence
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO outbox_events (aggregate_type, aggregate_id, aggregate_sequence, event_id, topic, event_key,
                                           event_type, payload, status, attempts, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'order-events', ?, 'order.paid', '{}', ?, 0, now(), now())
                """)) {
            statement.setString(1, aggregateType);
            statement.setLong(2, aggregateId);
            statement.setLong(3, sequence);
            statement.setString(4, "evt-seq-" + EVENT_NUMBERS.merge("all", 1, Integer::sum));
            statement.setString(5, String.valueOf(aggregateId));
            statement.setString(6, status);
            statement.executeUpdate();
        }
    }


    private static List<String> rows(Connection connection, String query) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(query)) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return rows;
    }
}
