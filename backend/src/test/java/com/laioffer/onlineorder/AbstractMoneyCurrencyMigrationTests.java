package com.laioffer.onlineorder;


import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * Upgrades a populated V8 database with Flyway: V11 must give every stored amount the currency USD and scale 2
 * without changing a price, round the totals that were summed through DOUBLE PRECISION, and from then on keep
 * currencies valid and carts single-currency. A price that does not fit NUMERIC(12,2) stops the migration before
 * anything changes. Subclasses pick the database.
 */
abstract class AbstractMoneyCurrencyMigrationTests {

    /**
     * Amount columns and their table, as V11 leaves them: NUMERIC(12,2).
     */
    private static final Map<String, String> AMOUNT_COLUMNS = Map.of(
            "menu_items", "price",
            "order_items", "price",
            "order_history_items", "price",
            "carts", "total_price",
            "orders", "total_price",
            "merchant_orders", "total_price"
    );

    private static final List<String> CURRENCY_TABLES = List.of(
            "restaurants", "menu_items", "carts", "order_items", "orders", "order_history_items", "merchant_orders");

    /**
     * 3 x 4.89 summed through DOUBLE PRECISION, as carts.total_price could hold it before V11 (and orders and the
     * merchant inbox copied it).
     */
    private static final String NOISY_TOTAL = "14.669999999999998";


    /**
     * @return the JDBC URL of a new, empty database
     */
    abstract String newDatabase() throws SQLException;


    abstract String username();


    abstract String password();


    @Test
    void upgradeFromV8_shouldGiveEveryAmountAUsdCurrencyAndTwoDecimals() throws SQLException {
        String url = newDatabase();
        migrate(url, "8");
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            seedV8(statement, "4.89");
        }

        migrate(url, null);

        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            for (String table : CURRENCY_TABLES) {
                Assertions.assertEquals(List.of("USD"), strings(statement, "SELECT DISTINCT currency FROM " + table),
                        table + " rows become USD");
            }
            for (Map.Entry<String, String> column : AMOUNT_COLUMNS.entrySet()) {
                Assertions.assertEquals(List.of("12|2"), strings(statement, """
                        SELECT numeric_precision || '|' || numeric_scale
                        FROM information_schema.columns
                        WHERE LOWER(table_name) = '%s' AND LOWER(column_name) = '%s' AND table_schema = CURRENT_SCHEMA
                        """.formatted(column.getKey(), column.getValue())), column.getKey() + "." + column.getValue());
            }
            // Prices are kept exactly; totals lose their DOUBLE PRECISION noise.
            Assertions.assertEquals(List.of("4.89"), strings(statement, "SELECT price FROM menu_items"));
            Assertions.assertEquals(List.of("4.89"), strings(statement, "SELECT price FROM order_items"));
            Assertions.assertEquals(List.of("4.89"), strings(statement, "SELECT price FROM order_history_items"));
            Assertions.assertEquals(List.of("14.67"), strings(statement, "SELECT total_price FROM carts"));
            Assertions.assertEquals(List.of("14.67"), strings(statement, "SELECT total_price FROM orders"));
            Assertions.assertEquals(List.of("14.67"), strings(statement, "SELECT total_price FROM merchant_orders"));

            // A writer that does not know the column (a pod of the previous release) still writes USD.
            statement.execute("INSERT INTO restaurants (name) VALUES ('Default Diner')");
            Assertions.assertEquals(List.of("USD"),
                    strings(statement, "SELECT currency FROM restaurants WHERE name = 'Default Diner'"));
        }
    }


    @Test
    void afterTheUpgrade_currenciesShouldBeIsoCodesAndCartsSingleCurrency() throws SQLException {
        String url = newDatabase();
        migrate(url, "8");
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            seedV8(statement, "4.89");
        }
        migrate(url, null);

        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            for (String code : List.of("usd", "US", "U5D", "EURO")) {
                Assertions.assertThrows(SQLException.class, () -> statement.execute(
                        "INSERT INTO restaurants (name, currency) VALUES ('Bad', '" + code + "')"), code);
            }
            statement.execute("INSERT INTO restaurants (name, currency) VALUES ('Cafe Euro', 'EUR')");
            long euroRestaurant = longValue(statement, "SELECT id FROM restaurants WHERE name = 'Cafe Euro'");

            // A menu item has its restaurant's currency.
            Assertions.assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO menu_items (restaurant_id, name, price, currency) VALUES (" + euroRestaurant
                            + ", 'Dollar Croissant', 3.50, 'USD')"));
            statement.execute("INSERT INTO menu_items (restaurant_id, name, price, currency) VALUES (" + euroRestaurant
                    + ", 'Croissant', 3.50, 'EUR')");
            long croissant = longValue(statement, "SELECT id FROM menu_items WHERE name = 'Croissant'");
            // A restaurant cannot change its currency under its priced menu.
            Assertions.assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE restaurants SET currency = 'GBP' WHERE id = " + euroRestaurant));

            // The seeded cart is USD and holds a USD line: a EUR line is refused, so is a currency change.
            long cart = longValue(statement, "SELECT id FROM carts");
            Assertions.assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO order_items (menu_item_id, cart_id, price, quantity, currency) VALUES (" + croissant
                            + ", " + cart + ", 3.50, 1, 'EUR')"));
            Assertions.assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE carts SET currency = 'EUR' WHERE id = " + cart));
            // A line that claims EUR in a EUR cart must still match its menu item's currency.
            statement.execute("DELETE FROM order_items WHERE cart_id = " + cart);
            statement.execute("UPDATE carts SET currency = 'EUR', total_price = 0 WHERE id = " + cart);
            long burger = longValue(statement, "SELECT id FROM menu_items WHERE name = 'Burger'");
            Assertions.assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO order_items (menu_item_id, cart_id, price, quantity, currency) VALUES (" + burger
                            + ", " + cart + ", 4.89, 1, 'EUR')"));
            statement.execute("INSERT INTO order_items (menu_item_id, cart_id, price, quantity, currency) VALUES ("
                    + croissant + ", " + cart + ", 3.50, 1, 'EUR')");
            Assertions.assertEquals(List.of("EUR"), strings(statement, "SELECT currency FROM order_items"));

            // Amounts stay non-negative, the merchant inbox total included.
            Assertions.assertThrows(SQLException.class, () -> statement.execute("UPDATE menu_items SET price = -1"));
            Assertions.assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE merchant_orders SET total_price = -1"));
            Assertions.assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE orders SET total_price = 10000000000"));

            // Deleting a restaurant still takes its menu and the cart lines with it.
            statement.execute("DELETE FROM restaurants WHERE id = " + euroRestaurant);
            Assertions.assertEquals(List.of("0"), strings(statement, "SELECT COUNT(*) FROM order_items"));
        }
    }


    @Test
    void upgradeFromV8_whenAPriceHasMoreThanTwoDecimals_shouldStopBeforeChangingAnything() throws SQLException {
        String url = newDatabase();
        migrate(url, "8");
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            seedV8(statement, "4.895");
        }

        FlywayException failure = Assertions.assertThrows(FlywayException.class, () -> migrate(url, null));

        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        // Every column that does not fit is named: the seeded price, and the cart line and order item that copied it.
        for (String column : List.of("menu_items.price", "order_items.price", "order_history_items.price")) {
            Assertions.assertTrue(messages.toString().contains("1 row(s) of " + column + " have more than two decimal "
                    + "places"), messages.toString());
        }
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            Assertions.assertEquals(List.of("4.895"), strings(statement, "SELECT price FROM menu_items"));
            Assertions.assertEquals(List.of(NOISY_TOTAL), strings(statement, "SELECT total_price FROM carts"));
            Assertions.assertEquals(List.of("0"), strings(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE LOWER(column_name) = 'currency' AND table_schema = CURRENT_SCHEMA
                    """));
        }
    }


    private void migrate(String url, String target) {
        var configuration = Flyway.configure()
                .dataSource(url, username(), password())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }


    private Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, username(), password());
    }


    /**
     * One customer with a cart of three burgers at {@code price}, and one paid order of them with its merchant
     * inbox row; the totals carry DOUBLE PRECISION noise.
     */
    private static void seedV8(Statement statement, String price) throws SQLException {
        statement.execute("INSERT INTO customers (email, password) VALUES ('money@example.com', 'x')");
        statement.execute("INSERT INTO carts (customer_id, total_price) SELECT id, " + NOISY_TOTAL + " FROM customers");
        statement.execute("INSERT INTO restaurants (name) VALUES ('Upgrade Burgers')");
        statement.execute("INSERT INTO menu_items (restaurant_id, name, price) SELECT id, 'Burger', " + price
                + " FROM restaurants");
        statement.execute("""
                INSERT INTO order_items (menu_item_id, cart_id, price, quantity)
                SELECT menu_items.id, carts.id, menu_items.price, 3 FROM menu_items, carts
                """);
        statement.execute("""
                INSERT INTO orders (customer_id, total_price, status, created_at, restaurant_id)
                SELECT customers.id, %s, 'PAID', TIMESTAMP '2026-01-02 03:04:05', restaurants.id
                FROM customers, restaurants
                """.formatted(NOISY_TOTAL));
        statement.execute("""
                INSERT INTO order_history_items (order_id, menu_item_id, restaurant_id, price, quantity, menu_item_name)
                SELECT orders.id, menu_items.id, menu_items.restaurant_id, menu_items.price, 3, 'Burger'
                FROM orders, menu_items
                """);
        statement.execute("""
                INSERT INTO merchant_orders (order_id, restaurant_id, customer_id, status, total_price, items, paid_at,
                                             created_at, updated_at)
                SELECT id, restaurant_id, customer_id, 'PAID', total_price, '[]', created_at, created_at, created_at
                FROM orders
                """);
    }


    private static long longValue(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            Assertions.assertTrue(result.next(), sql);
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
