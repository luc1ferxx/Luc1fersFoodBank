package com.laioffer.onlineorder;


import com.laioffer.onlineorder.repository.OutboxEventRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.data.jdbc.repository.query.Query;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;


import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


/**
 * The onlineorder.outbox.oldest_pending_age gauge runs on every Prometheus scrape, so its query must stay cheap on a
 * large outbox table: PostgreSQL has to serve it from the V3 partial index over unpublished rows, not by walking the
 * primary key past every PUBLISHED row.
 */
@Testcontainers(disabledWithoutDocker = true)
class OutboxOldestPendingAgeQueryPlanTests {

    private static final String INDEX_NAME = "idx_outbox_events_unpublished_id";

    @Container
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("onlineorder")
            .withUsername("postgres")
            .withPassword("secret");


    @Test
    void oldestPendingAgeQuery_shouldUseThePartialIndexAndReturnTheOldestUnpublishedEvent() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        String query = OutboxEventRepository.class
                .getMethod("findOldestUnpublishedCreatedAt")
                .getAnnotation(Query.class)
                .value();
        LocalDateTime oldestPending = LocalDateTime.of(2026, 1, 1, 12, 0);

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO outbox_events (aggregate_type, aggregate_id, event_id, topic, event_key, event_type,
                                               payload, status, attempts, created_at, updated_at, published_at)
                    SELECT 'ORDER', g, 'evt-' || g, 'order-events', 'order-' || g, 'ORDER_PAID', '{}',
                           'PUBLISHED', 1, now(), now(), now()
                    FROM generate_series(1, 20000) g
                    """);
            statement.execute("""
                    INSERT INTO outbox_events (aggregate_type, aggregate_id, event_id, topic, event_key, event_type,
                                               payload, status, attempts, created_at, updated_at)
                    SELECT 'ORDER', g, 'evt-' || g, 'order-events', 'order-' || g, 'ORDER_PAID', '{}',
                           CASE WHEN g %% 2 = 0 THEN 'PENDING' ELSE 'PROCESSING' END, 0,
                           TIMESTAMP '%s' + (g - 20001) * INTERVAL '1 second', now()
                    FROM generate_series(20001, 20100) g
                    """.formatted(Timestamp.valueOf(oldestPending)));
            statement.execute("ANALYZE outbox_events");

            List<String> plan = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery("EXPLAIN " + query)) {
                while (resultSet.next()) {
                    plan.add(resultSet.getString(1));
                }
            }
            String planText = String.join("\n", plan);
            Assertions.assertTrue(
                    planText.contains("Index Scan using " + INDEX_NAME),
                    "the gauge query should be served by the partial index, but the plan was:\n" + planText
            );
            Assertions.assertFalse(planText.contains("outbox_events_pkey"), planText);
            Assertions.assertFalse(planText.contains("Seq Scan"), planText);

            try (ResultSet resultSet = statement.executeQuery(query)) {
                Assertions.assertTrue(resultSet.next());
                Assertions.assertEquals(oldestPending, resultSet.getTimestamp(1).toLocalDateTime());
            }
        }
    }
}
