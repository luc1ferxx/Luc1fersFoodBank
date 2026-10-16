package com.laioffer.onlineorder;


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
 * Upgrades a populated V6 database to the current schema with Flyway: V7 (64-bit keys) must keep every row and
 * foreign key, and lift the INTEGER limit that the orders key generator has just reached; V7_1 backfills
 * orders.updated_at and one order_status_history row per existing order. Subclasses pick the database.
 */
abstract class AbstractKeyWideningMigrationTests {

    static final long INT_MAX = Integer.MAX_VALUE;
    static final Timestamp PLACED_AT = Timestamp.valueOf(LocalDateTime.of(2026, 1, 2, 3, 4, 5));


    abstract String jdbcUrl();


    abstract String username();


    abstract String password();


    /**
     * Makes the next generated orders.id equal to {@code nextId}.
     */
    abstract void restartOrderIdsAt(Statement statement, long nextId) throws SQLException;


    @Test
    void upgradeFromV6_shouldKeepTheDataAndLiftTheIntegerLimit() throws SQLException {
        migrate("6");
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            seedV6(statement);
            // The orders key generator reaches the INTEGER maximum: the order at 2,147,483,647 is the last one V6 takes.
            restartOrderIdsAt(statement, INT_MAX);
            insertOrder(statement, "PLACED");
            Assertions.assertEquals(INT_MAX, maxOrderId(statement));
            Assertions.assertThrows(SQLException.class, () -> insertOrder(statement, "PLACED"),
                    "V6 cannot store an order id past the INTEGER maximum");
        }

        migrate(null);

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            Assertions.assertEquals(List.of("ACCEPTED", "PLACED"), strings(statement, "SELECT status FROM orders ORDER BY id"));
            Assertions.assertEquals(List.of("Upgrade Rider"), strings(statement, """
                    SELECT riders.name
                    FROM deliveries
                    JOIN riders ON riders.id = deliveries.rider_id
                    JOIN dispatch_orders ON dispatch_orders.order_id = deliveries.order_id
                    JOIN orders ON orders.id = deliveries.order_id
                    """));

            // V7_1: updated_at backfilled from created_at, one SYSTEM history row per existing order.
            Assertions.assertEquals(List.of("0"), strings(statement,
                    "SELECT COUNT(*) FROM orders WHERE updated_at IS NULL OR updated_at <> created_at"));
            Assertions.assertEquals(List.of("ACCEPTED|SYSTEM|backfill|true", "PLACED|SYSTEM|backfill|true"), strings(statement, """
                    SELECT h.to_status || '|' || h.actor_type || '|' || h.reason || '|'
                           || CASE WHEN h.from_status IS NULL AND h.actor_id IS NULL AND h.occurred_at = o.created_at
                                   THEN 'true' ELSE 'false' END
                    FROM order_status_history h
                    JOIN orders o ON o.id = h.order_id
                    ORDER BY h.order_id
                    """));

            // V7: the next order gets an id past the INTEGER maximum, and the foreign keys still hold.
            insertOrder(statement, "PAID");
            Assertions.assertEquals(INT_MAX + 1, maxOrderId(statement));
            Assertions.assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO order_history_items (order_id, menu_item_id, restaurant_id, price, quantity, menu_item_name)
                    VALUES (9000000000, 1, 1, 1, 1, 'nothing')
                    """), "the order foreign key must survive the type change");
        }
    }


    private void migrate(String target) {
        var configuration = Flyway.configure()
                .dataSource(jdbcUrl(), username(), password())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }


    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), username(), password());
    }


    private static void seedV6(Statement statement) throws SQLException {
        statement.execute("INSERT INTO customers (email, password) VALUES ('upgrade@example.com', 'x')");
        statement.execute("INSERT INTO carts (customer_id, total_price) SELECT id, 0 FROM customers");
        statement.execute("INSERT INTO restaurants (name) VALUES ('Upgrade Diner')");
        statement.execute("INSERT INTO menu_items (restaurant_id, name, price) SELECT id, 'Soup', 5 FROM restaurants");
        statement.execute("""
                INSERT INTO orders (customer_id, total_price, status, created_at, restaurant_id)
                SELECT customers.id, 5, 'ACCEPTED', TIMESTAMP '2026-01-02 03:04:05', restaurants.id
                FROM customers, restaurants
                """);
        statement.execute("""
                INSERT INTO order_history_items (order_id, menu_item_id, restaurant_id, price, quantity, menu_item_name)
                SELECT orders.id, menu_items.id, menu_items.restaurant_id, 5, 1, 'Soup'
                FROM orders, menu_items
                """);
        statement.execute("INSERT INTO riders (name) VALUES ('Upgrade Rider')");
        statement.execute("""
                INSERT INTO deliveries (order_id, customer_id, restaurant_id, rider_id, status, assigned_at, updated_at)
                SELECT orders.id, orders.customer_id, orders.restaurant_id, riders.id, 'ASSIGNED',
                       TIMESTAMP '2026-01-02 03:10:00', TIMESTAMP '2026-01-02 03:10:00'
                FROM orders, riders
                """);
        statement.execute("""
                INSERT INTO dispatch_orders (order_id, created_at, updated_at)
                SELECT id, TIMESTAMP '2026-01-02 03:10:00', TIMESTAMP '2026-01-02 03:10:00' FROM orders
                """);
    }


    private static void insertOrder(Statement statement, String status) throws SQLException {
        statement.execute("""
                INSERT INTO orders (customer_id, total_price, status, created_at)
                SELECT id, 5, '%s', TIMESTAMP '2026-01-02 03:04:05' FROM customers
                """.formatted(status));
    }


    private static long maxOrderId(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT MAX(id) FROM orders")) {
            result.next();
            return result.getLong(1);
        }
    }


    private static List<String> strings(Statement statement, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }
}
