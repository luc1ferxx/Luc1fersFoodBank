package com.laioffer.onlineorder.partition;


import com.laioffer.onlineorder.ids.SnowflakeIdGenerator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


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
 * Upgrades a populated V8 database to the current schema: V10 (monthly partitions on PostgreSQL, plain tables on H2)
 * must keep every row, id, constraint and the processed_events deduplication; V10_1 must give every existing order a
 * unique order number in the Snowflake layout; V10_2 creates the worker id leases. Subclasses pick the database.
 */
abstract class AbstractScaleMigrationTests {

    static final LocalDateTime JANUARY = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
    static final LocalDateTime MARCH = LocalDateTime.of(2026, 3, 15, 12, 0, 0);


    abstract String jdbcUrl();


    abstract String username();


    abstract String password();


    /**
     * Checks the database-specific shape of the three tables after the upgrade.
     */
    abstract void assertTableLayout(Statement statement) throws SQLException;


    @Test
    void upgradeFromV8_shouldKeepEveryRowAndNumberEveryOrder() throws SQLException {
        migrate("8");
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            seedV8(statement);
        }

        migrate(null);

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertTableLayout(statement);

            // Rows and ids survive the copy into the partitioned tables.
            Assertions.assertEquals(List.of("1|admin.order|JANUARY", "2|admin.order|MARCH"), strings(statement, """
                    SELECT id || '|' || action || '|' || target_id FROM audit_log ORDER BY id
                    """));
            Assertions.assertEquals(List.of("ACCEPTED|2026-01-02 03:04:05", "PLACED|2026-03-15 12:00:00"),
                    strings(statement, """
                            SELECT to_status || '|' || CAST(occurred_at AS VARCHAR(19))
                            FROM order_status_history ORDER BY order_id
                            """));
            Assertions.assertEquals(List.of("consumer-a|evt-1", "consumer-a|evt-2", "consumer-b|evt-1"), strings(statement, """
                    SELECT consumer_name || '|' || dedup_key FROM processed_events ORDER BY consumer_name, dedup_key
                    """));

            // The id sequences continue after the copied rows.
            statement.execute("""
                    INSERT INTO audit_log (actor_role, action, target_type, target_id, occurred_at)
                    VALUES ('ROLE_ADMIN', 'admin.after', 'order', 'AFTER', CURRENT_TIMESTAMP)
                    """);
            Assertions.assertEquals(List.of("3"), strings(statement, "SELECT MAX(id) FROM audit_log"));

            // The history's foreign key and checks still hold.
            Assertions.assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO order_status_history (order_id, to_status, actor_type, occurred_at)
                    VALUES (987654321, 'PAID', 'SYSTEM', CURRENT_TIMESTAMP)
                    """), "fk_order_status_history_order");
            Assertions.assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO order_status_history (order_id, to_status, actor_type, occurred_at)
                    SELECT MIN(id), 'LOST', 'SYSTEM', CURRENT_TIMESTAMP FROM orders
                    """), "chk_order_status_history_to_status");

            // A mark that is already there, in the same month, is still rejected by the database.
            Assertions.assertEquals(0, statement.executeUpdate("""
                    INSERT INTO processed_events (consumer_name, dedup_key, event_type, processed_at)
                    VALUES ('consumer-a', 'evt-1', 'order.paid', TIMESTAMP '2026-01-20 00:00:00')
                    ON CONFLICT DO NOTHING
                    """));

            assertOrderNumbers(statement);
            Assertions.assertEquals(List.of("1023|0|1022"), strings(statement, """
                    SELECT COUNT(*) || '|' || MIN(worker_id) || '|' || MAX(worker_id) FROM snowflake_worker_leases
                    """));
        }
    }


    private void assertOrderNumbers(Statement statement) throws SQLException {
        List<long[]> orders = new ArrayList<>();
        try (ResultSet result = statement.executeQuery("SELECT id, order_no, created_at FROM orders ORDER BY id")) {
            while (result.next()) {
                orders.add(new long[]{result.getLong(1), result.getLong(2), result.getTimestamp(3).getTime()});
            }
        }
        Assertions.assertEquals(3, orders.size());
        long previous = 0;
        for (long[] order : orders) {
            long orderNo = order[1];
            Assertions.assertTrue(orderNo > previous, "numbers increase with the id");
            previous = orderNo;
            Assertions.assertEquals(SnowflakeIdGenerator.BACKFILL_WORKER_ID, SnowflakeIdGenerator.workerIdOf(orderNo));
        }
        // The first two orders were placed in the same millisecond: same timestamp, consecutive sequences.
        Assertions.assertEquals(orders.get(0)[2], SnowflakeIdGenerator.timestampMillisOf(orders.get(0)[1]));
        Assertions.assertEquals(orders.get(1)[2], SnowflakeIdGenerator.timestampMillisOf(orders.get(1)[1]));
        Assertions.assertEquals(SnowflakeIdGenerator.sequenceOf(orders.get(0)[1]) + 1,
                SnowflakeIdGenerator.sequenceOf(orders.get(1)[1]));
        // The third one has the earliest created_at but the highest id: its number still comes after the others.
        Assertions.assertTrue(orders.get(2)[2] < orders.get(0)[2]);

        Assertions.assertThrows(SQLException.class, () -> statement.execute("""
                INSERT INTO orders (customer_id, total_price, status, created_at)
                SELECT MIN(id), 5, 'PLACED', CURRENT_TIMESTAMP FROM customers
                """), "order_no is NOT NULL");
        Assertions.assertThrows(SQLException.class, () -> statement.execute("""
                INSERT INTO orders (customer_id, total_price, status, created_at, order_no)
                SELECT MIN(customers.id), 5, 'PLACED', CURRENT_TIMESTAMP, MIN(orders.order_no) FROM customers, orders
                """), "uq_orders_order_no");
    }


    private void seedV8(Statement statement) throws SQLException {
        statement.execute("INSERT INTO customers (email, password) VALUES ('scale@example.com', 'x')");
        statement.execute("INSERT INTO restaurants (name) VALUES ('Scale Diner')");
        insertOrder(statement, "ACCEPTED", JANUARY);
        insertOrder(statement, "PLACED", JANUARY);
        insertOrder(statement, "PAID", JANUARY.minusDays(1));
        statement.execute("DELETE FROM order_status_history");
        statement.execute("""
                INSERT INTO order_status_history (order_id, from_status, to_status, actor_type, occurred_at)
                SELECT MIN(id), NULL, 'ACCEPTED', 'SYSTEM', TIMESTAMP '2026-01-02 03:04:05' FROM orders
                """);
        statement.execute("""
                INSERT INTO order_status_history (order_id, from_status, to_status, actor_type, occurred_at)
                SELECT MAX(id), NULL, 'PLACED', 'CUSTOMER', TIMESTAMP '2026-03-15 12:00:00' FROM orders
                """);
        statement.execute("""
                INSERT INTO audit_log (actor_role, action, target_type, target_id, occurred_at)
                VALUES ('ROLE_ADMIN', 'admin.order', 'order', 'JANUARY', TIMESTAMP '2026-01-02 03:04:05'),
                       ('ROLE_ADMIN', 'admin.order', 'order', 'MARCH', TIMESTAMP '2026-03-15 12:00:00')
                """);
        statement.execute("""
                INSERT INTO processed_events (consumer_name, event_id, dedup_key, event_type, processed_at)
                VALUES ('consumer-a', 'evt-1', 'evt-1', 'order.paid', TIMESTAMP '2026-01-02 03:04:05'),
                       ('consumer-a', 'evt-2', 'evt-2', 'order.paid', TIMESTAMP '2026-03-15 12:00:00'),
                       ('consumer-b', 'evt-1', 'evt-1', 'order.paid', TIMESTAMP '2026-03-15 12:00:00')
                """);
    }


    private static void insertOrder(Statement statement, String status, LocalDateTime createdAt) throws SQLException {
        statement.execute("""
                INSERT INTO orders (customer_id, total_price, status, created_at, updated_at, restaurant_id)
                SELECT customers.id, 5, '%s', TIMESTAMP '%s', TIMESTAMP '%s', restaurants.id
                FROM customers, restaurants
                """.formatted(status, Timestamp.valueOf(createdAt), Timestamp.valueOf(createdAt)));
    }


    void migrate(String target) {
        var configuration = Flyway.configure()
                .dataSource(jdbcUrl(), username(), password())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }


    Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), username(), password());
    }


    static List<String> strings(Statement statement, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }
}
