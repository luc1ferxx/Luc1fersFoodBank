package com.laioffer.onlineorder;


import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;


/**
 * The h2 profile runs the same migration list: V9's backlog numbering (MERGE with a window function) works on H2, and
 * V9_1 only records its version there.
 */
class OutboxAggregateSequenceMigrationH2Tests {

    @Test
    void migrations_shouldNumberTheBacklogAndRecordV9_1WithoutAnIndex() throws SQLException {
        String url = "jdbc:h2:mem:outbox-sequence-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection keepAlive = DriverManager.getConnection(url, "sa", "")) {
            Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").target("8").load().migrate();
            try (Statement statement = keepAlive.createStatement()) {
                statement.execute("""
                        INSERT INTO outbox_events (aggregate_type, aggregate_id, event_id, topic, event_key, event_type,
                                                   payload, status, attempts, created_at, updated_at)
                        VALUES ('ORDER', 1, 'e1', 'order-events', '1', 'order.paid', '{}', 'PUBLISHED', 1, NOW(), NOW()),
                               ('ORDER', 1, 'e2', 'order-events', '1', 'order.accepted', '{}', 'PENDING', 0, NOW(), NOW()),
                               ('ORDER', 1, 'e3', 'order-events', '1', 'order.preparing', '{}', 'PENDING', 0, NOW(), NOW())
                        """);
            }

            Flyway flyway = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load();
            flyway.migrate();

            MigrationInfo version91 = Arrays.stream(flyway.info().applied())
                    .filter(info -> "9.1".equals(info.getVersion().getVersion()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("version 9.1 was not recorded on H2"));
            Assertions.assertEquals(MigrationState.SUCCESS, version91.getState());
            Assertions.assertEquals(List.of("e1:null", "e2:1", "e3:2"), rows(keepAlive, """
                    SELECT event_id || ':' || COALESCE(CAST(aggregate_sequence AS VARCHAR), 'null')
                    FROM outbox_events ORDER BY id
                    """));
            Assertions.assertEquals(List.of("2"), rows(keepAlive,
                    "SELECT CAST(last_sequence AS VARCHAR) FROM outbox_aggregate_sequences"));
            Assertions.assertEquals(List.of("0"), rows(keepAlive, """
                    SELECT CAST(COUNT(*) AS VARCHAR) FROM information_schema.indexes
                    WHERE LOWER(index_name) = 'idx_outbox_events_unpublished_aggregate'
                    """));
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
