package com.laioffer.onlineorder;


import com.laioffer.onlineorder.money.Money;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;


/**
 * Upgrades a populated V8 database with Flyway: V11 must give every stored amount the currency USD and scale 2
 * without changing an amount, and from then on keep currencies to the supported ones (the currencies table, which
 * must agree with {@link Money}), prices to their currency's minor unit and carts single-currency. An amount that does
 * not fit NUMERIC(12,2), a price or a total, stops the migration before anything changes. Subclasses pick the
 * database.
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
     * 3 x 4.89, as the previous releases stored it: they summed the lines exactly in NUMERIC.
     */
    private static final String TOTAL = "14.67";


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
            seedV8(statement, "4.89", TOTAL);
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
            // Amounts are kept exactly.
            Assertions.assertEquals(List.of("4.89"), strings(statement, "SELECT price FROM menu_items"));
            Assertions.assertEquals(List.of("4.89"), strings(statement, "SELECT price FROM order_items"));
            Assertions.assertEquals(List.of("4.89"), strings(statement, "SELECT price FROM order_history_items"));
            Assertions.assertEquals(List.of("14.67"), strings(statement, "SELECT total_price FROM carts"));
            Assertions.assertEquals(List.of("14.67"), strings(statement, "SELECT total_price FROM orders"));
            Assertions.assertEquals(List.of("14.67"), strings(statement, "SELECT total_price FROM merchant_orders"));

            // An INSERT that does not name the column (hand-written SQL) still writes USD.
            statement.execute("INSERT INTO restaurants (name) VALUES ('Default Diner')");
            Assertions.assertEquals(List.of("USD"),
                    strings(statement, "SELECT currency FROM restaurants WHERE name = 'Default Diner'"));
        }
    }


    @Test
    void theCurrenciesTable_shouldListOnlyCurrenciesThatMoneyAcceptsWithTheirMinorUnits() throws SQLException {
        String url = newDatabase();
        migrate(url, null);

        try (Connection connection = connect(url); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT code, minor_units FROM currencies ORDER BY code")) {
            List<String> codes = new ArrayList<>();
            while (rows.next()) {
                String code = rows.getString("code");
                codes.add(code);
                Currency currency = Money.currency(code);
                Assertions.assertEquals(currency.getDefaultFractionDigits(), rows.getInt("minor_units"), code);
            }
            Assertions.assertTrue(codes.containsAll(List.of("USD", "EUR", "GBP", "JPY")), codes.toString());
        }
    }


    @Test
    void afterTheUpgrade_onlySupportedCurrenciesAndWholeAmountsOfZeroDecimalCurrenciesShouldBeStored()
            throws SQLException {
        String url = newDatabase();
        migrate(url, "8");
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            seedV8(statement, "4.89", TOTAL);
        }
        migrate(url, null);

        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            // Codes that Money rejects: three minor units, no minor unit, not ISO 4217 at all.
            for (String code : List.of("BHD", "KWD", "XXX", "XAU", "ABC")) {
                Assertions.assertThrows(IllegalArgumentException.class, () -> Money.currency(code), code);
                Assertions.assertThrows(SQLException.class, () -> statement.execute(
                        "INSERT INTO restaurants (name, currency) VALUES ('Unsupported " + code + "', '" + code + "')"),
                        code);
            }
            long burgers = longValue(statement, "SELECT id FROM restaurants WHERE name = 'Upgrade Burgers'");
            Assertions.assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE restaurants SET currency = 'BHD' WHERE id = " + burgers));

            // A JPY price has no cents.
            statement.execute("INSERT INTO restaurants (name, currency) VALUES ('Yen Ramen', 'JPY')");
            long ramenShop = longValue(statement, "SELECT id FROM restaurants WHERE name = 'Yen Ramen'");
            Assertions.assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO menu_items (restaurant_id, name, price, currency) VALUES (" + ramenShop
                            + ", 'Half Ramen', 950.50, 'JPY')"));
            statement.execute("INSERT INTO menu_items (restaurant_id, name, price, currency) VALUES (" + ramenShop
                    + ", 'Ramen', 950, 'JPY')");
            List<String> ramenPrice = strings(statement, "SELECT price FROM menu_items WHERE name = 'Ramen'");
            Assertions.assertEquals(List.of("950.00"), ramenPrice);
            Assertions.assertEquals(Money.of("950", "JPY"), Money.of(new BigDecimal(ramenPrice.get(0)), "JPY"));
            Assertions.assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE menu_items SET price = 950.01 WHERE name = 'Ramen'"));

            // The currencies table keeps its own rules: a further currency needs the right minor units, and one
            // without minor units needs a migration that also teaches the menu_items check about it.
            Assertions.assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO currencies (code, minor_units) VALUES ('BHD', 3)"));
            Assertions.assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO currencies (code, minor_units) VALUES ('VND', 0)"));
            Assertions.assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO currencies (code, minor_units) VALUES ('sek', 2)"));
            // A currency in use cannot be removed.
            Assertions.assertThrows(SQLException.class, () -> statement.execute(
                    "DELETE FROM currencies WHERE code = 'JPY'"));
        }
    }


    @Test
    void afterTheUpgrade_currenciesShouldBeIsoCodesAndCartsSingleCurrency() throws SQLException {
        String url = newDatabase();
        migrate(url, "8");
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            seedV8(statement, "4.89", TOTAL);
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
            seedV8(statement, "4.895", TOTAL);
        }

        String messages = failureMessages(url);

        // Every column that does not fit is named with its rows: the seeded price, and the cart line and order item
        // that copied it.
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            for (String column : List.of("menu_items.price", "order_items.price", "order_history_items.price")) {
                String table = column.substring(0, column.indexOf('.'));
                long id = longValue(statement, "SELECT id FROM " + table);
                Assertions.assertTrue(messages.contains("1 row(s) of " + column + " have more than two decimal "
                        + "places or exceed 9999999999.99 (id " + id + ")"), messages);
            }
            Assertions.assertFalse(messages.contains("total_price"), messages);
            Assertions.assertEquals(List.of("4.895"), strings(statement, "SELECT price FROM menu_items"));
            assertNothingMigrated(statement);
        }
    }


    @Test
    void upgradeFromV8_whenATotalHasSubCentDigits_shouldStopInsteadOfRoundingIt() throws SQLException {
        String url = newDatabase();
        migrate(url, "8");
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            // A hand-edited total: the previous releases summed the lines exactly, so this is not rounding noise.
            seedV8(statement, "4.89", "14.675");
        }

        String messages = failureMessages(url);

        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            for (String column : List.of("carts.total_price", "orders.total_price", "merchant_orders.total_price")) {
                String table = column.substring(0, column.indexOf('.'));
                long id = longValue(statement, "SELECT id FROM " + table);
                Assertions.assertTrue(messages.contains("1 row(s) of " + column + " have more than two decimal "
                        + "places or exceed 9999999999.99 (id " + id + ")"), messages);
            }
            Assertions.assertFalse(messages.contains(".price"), messages);
            for (String table : List.of("carts", "orders", "merchant_orders")) {
                Assertions.assertEquals(List.of("14.675"), strings(statement, "SELECT total_price FROM " + table),
                        table);
            }
            assertNothingMigrated(statement);
        }
    }


    @Test
    void upgradeFromV8_whenManyRowsDoNotFit_shouldNameTheFirstTenIds() throws SQLException {
        String url = newDatabase();
        migrate(url, "8");
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO restaurants (name) VALUES ('Fractional Fries')");
            for (int i = 0; i < 12; i++) {
                statement.execute("INSERT INTO menu_items (restaurant_id, name, price) SELECT id, 'Fry " + i
                        + "', 0.125 FROM restaurants");
            }
        }

        String messages = failureMessages(url);

        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            List<String> ids = strings(statement, "SELECT id FROM menu_items ORDER BY id");
            Assertions.assertTrue(messages.contains("12 row(s) of menu_items.price have more than two decimal places "
                    + "or exceed 9999999999.99 (id " + String.join(", ", ids.subList(0, 10)) + ", ...)"), messages);
        }
    }


    /**
     * Runs the migration to the latest version, which must fail.
     *
     * @return the messages of the failure and its causes
     */
    private String failureMessages(String url) {
        FlywayException failure = Assertions.assertThrows(FlywayException.class, () -> migrate(url, null));
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }


    private static void assertNothingMigrated(Statement statement) throws SQLException {
        Assertions.assertEquals(List.of("0"), strings(statement, """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE LOWER(column_name) = 'currency' AND table_schema = CURRENT_SCHEMA
                """));
        Assertions.assertEquals(List.of("0"), strings(statement, """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE LOWER(table_name) = 'currencies' AND table_schema = CURRENT_SCHEMA
                """));
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
     * inbox row, all three with {@code total}.
     */
    private static void seedV8(Statement statement, String price, String total) throws SQLException {
        statement.execute("INSERT INTO customers (email, password) VALUES ('money@example.com', 'x')");
        statement.execute("INSERT INTO carts (customer_id, total_price) SELECT id, " + total + " FROM customers");
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
                """.formatted(total));
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
