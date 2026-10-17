package db.migration;


import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * Gives every stored amount a currency and a fixed scale.
 *
 * <ul>
 *     <li>A {@code currencies} reference table lists the supported ISO 4217 currencies and their minor units
 *     ({@link #SUPPORTED_CURRENCIES}): only currencies with 0 or 2 minor units, the ones the {@code Money} type
 *     accepts. {@code restaurants.currency} references it, so a restaurant (inserted by SQL: there is no API for it)
 *     cannot use a code that the application would reject, such as BHD (3 minor units), XXX (none) or an invented
 *     one; and a menu item of a currency without minor units cannot have cents (a check on {@code menu_items}).</li>
 *     <li>Every table with an amount gets {@code currency CHAR(3) NOT NULL}, checked to be three upper-case letters:
 *     restaurants (a restaurant prices in one currency), menu_items, carts, order_items (cart lines), orders,
 *     order_history_items and merchant_orders. Existing rows become USD, the only currency the application had. The
 *     column default stays 'USD' for an INSERT that does not name the column (hand-written SQL); the application
 *     always writes the currency explicitly.</li>
 *     <li>Every amount becomes {@code NUMERIC(12,2)}; the existing non-negative checks (V2) stay, and
 *     merchant_orders.total_price gets one.</li>
 *     <li>Composite keys make a cart single-currency in the database: a menu item has its restaurant's currency
 *     ({@code (restaurant_id, currency)} references restaurants), a cart line has its menu item's and its cart's
 *     currency. A cart's currency can therefore only change while it is empty, and a restaurant's only while it has
 *     no menu items. Orders, their items and the merchant inbox are history without such keys; the application
 *     copies the cart's currency onto them.</li>
 * </ul>
 *
 * <p>Existing data is checked before anything changes: every amount (the unit prices of menu_items, order_items and
 * order_history_items, and the totals of carts, orders and merchant_orders) must already have at most two decimal
 * places and fit twelve digits. The migration never rounds an amount; it fails, naming each column and the ids of
 * its first offending rows. (The previous releases summed totals exactly in NUMERIC, so a total with sub-cent digits
 * means a hand-edited or otherwise inconsistent row, not rounding noise.)
 *
 * <p>This is a Java migration because the currency check differs (PostgreSQL's {@code ~} operator, H2's
 * {@code REGEXP_LIKE}), the data checks must stop the migration with a message, and the PostgreSQL locks are taken
 * up front.
 *
 * <p>Production caveat: on PostgreSQL the migration first locks all seven tables {@code IN ACCESS EXCLUSIVE MODE}
 * (one statement, before the data check, so no row can change between the check and the type change), and Flyway
 * holds the locks until V11 commits. Changing NUMERIC to NUMERIC(12,2) rewrites each of the six tables with an
 * amount; the new checks and keys scan them once more. It must not run next to pods of the previous release, which
 * lock carts before menu_items and would deadlock with it (docs/operations.md, "Money with currency (V11)").
 */
public class V11__Money_with_currency extends BaseJavaMigration {

    static final String MAX_AMOUNT = "9999999999.99";

    /**
     * Every amount, as table.column: it must already fit NUMERIC(12,2) exactly.
     */
    static final List<String> AMOUNT_COLUMNS = List.of(
            "menu_items.price",
            "order_items.price",
            "order_history_items.price",
            "carts.total_price",
            "orders.total_price",
            "merchant_orders.total_price"
    );

    /**
     * The rows of the currencies table: ISO 4217 code to minor units, as {@link java.util.Currency} reports them
     * ({@code MoneyCurrencyMigration*Tests} check each row against it and against {@code Money}). The database
     * supports 0 and 2 minor units. A further currency is added by a later migration that inserts its row; one with
     * 0 minor units also has to replace the two checks built from {@link #ZERO_MINOR_UNIT_CURRENCIES}
     * ({@code chk_currency_minor_units} and {@code chk_menu_item_price_minor_units}).
     */
    static final Map<String, Integer> SUPPORTED_CURRENCIES = supportedCurrencies();

    /**
     * The supported currencies without minor units, whose prices must be whole numbers.
     */
    static final List<String> ZERO_MINOR_UNIT_CURRENCIES = SUPPORTED_CURRENCIES.entrySet().stream()
            .filter(currency -> currency.getValue() == 0)
            .map(Map.Entry::getKey)
            .toList();

    /**
     * How many offending row ids the data check names per column.
     */
    static final int REPORTED_IDS = 10;

    /**
     * Every table that gets a currency, with its amount column (null for restaurants) and the name of its currency
     * check, in an order where referenced tables come first.
     */
    static final Map<String, CurrencyTable> CURRENCY_TABLES = currencyTables();

    private static final Logger LOGGER = LoggerFactory.getLogger(V11__Money_with_currency.class);


    record CurrencyTable(String amountColumn, String checkName) {
    }


    private static Map<String, Integer> supportedCurrencies() {
        Map<String, Integer> currencies = new LinkedHashMap<>();
        for (String code : List.of("USD", "EUR", "GBP", "CHF", "CAD", "AUD", "NZD", "SEK", "NOK", "DKK", "PLN", "CZK",
                "HUF", "CNY", "HKD", "SGD", "TWD", "INR", "THB", "MXN", "BRL", "ZAR", "AED")) {
            currencies.put(code, 2);
        }
        currencies.put("JPY", 0);
        currencies.put("KRW", 0);
        return currencies;
    }


    private static Map<String, CurrencyTable> currencyTables() {
        Map<String, CurrencyTable> tables = new LinkedHashMap<>();
        tables.put("restaurants", new CurrencyTable(null, "chk_restaurant_currency"));
        tables.put("menu_items", new CurrencyTable("price", "chk_menu_item_currency"));
        tables.put("carts", new CurrencyTable("total_price", "chk_cart_currency"));
        tables.put("order_items", new CurrencyTable("price", "chk_order_item_currency"));
        tables.put("orders", new CurrencyTable("total_price", "chk_order_currency"));
        tables.put("order_history_items", new CurrencyTable("price", "chk_order_history_item_currency"));
        tables.put("merchant_orders", new CurrencyTable("total_price", "chk_merchant_order_currency"));
        return tables;
    }


    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        boolean postgres = "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());

        if (postgres) {
            // Every lock this migration needs, in one statement and before the data check: no row changes between
            // the check and the type change, and a lock that cannot be had (lock_timeout) fails the migration
            // before it has changed anything.
            execute(connection, "LOCK TABLE " + String.join(", ", CURRENCY_TABLES.keySet())
                    + " IN ACCESS EXCLUSIVE MODE");
        }
        requireAmountsFit(connection);
        createCurrencies(connection, postgres);

        for (Map.Entry<String, CurrencyTable> table : CURRENCY_TABLES.entrySet()) {
            addCurrencyAndScale(connection, postgres, table.getKey(), table.getValue());
        }
        execute(connection, "ALTER TABLE merchant_orders ADD CONSTRAINT chk_merchant_order_total_price_non_negative "
                + "CHECK (total_price >= 0)");

        execute(connection, "ALTER TABLE restaurants ADD CONSTRAINT fk_restaurant_currency "
                + "FOREIGN KEY (currency) REFERENCES currencies (code)");
        execute(connection, "ALTER TABLE menu_items ADD CONSTRAINT chk_menu_item_price_minor_units "
                + "CHECK (currency NOT IN (" + quotedList(ZERO_MINOR_UNIT_CURRENCIES) + ") "
                + "OR price = ROUND(price, 0))");
        execute(connection, "ALTER TABLE restaurants ADD CONSTRAINT uq_restaurant_id_currency UNIQUE (id, currency)");
        execute(connection, "ALTER TABLE menu_items ADD CONSTRAINT uq_menu_item_id_currency UNIQUE (id, currency)");
        execute(connection, "ALTER TABLE carts ADD CONSTRAINT uq_cart_id_currency UNIQUE (id, currency)");
        execute(connection, """
                ALTER TABLE menu_items ADD CONSTRAINT fk_menu_item_restaurant_currency
                FOREIGN KEY (restaurant_id, currency) REFERENCES restaurants (id, currency) ON DELETE CASCADE
                """);
        execute(connection, """
                ALTER TABLE order_items ADD CONSTRAINT fk_order_item_menu_item_currency
                FOREIGN KEY (menu_item_id, currency) REFERENCES menu_items (id, currency) ON DELETE CASCADE
                """);
        execute(connection, """
                ALTER TABLE order_items ADD CONSTRAINT fk_order_item_cart_currency
                FOREIGN KEY (cart_id, currency) REFERENCES carts (id, currency) ON DELETE CASCADE
                """);
    }


    /**
     * Fails the migration, before it changes anything, when an amount has more than two decimal places or exceeds
     * twelve digits; the message names every such column with its row count and the ids of its first rows.
     */
    private static void requireAmountsFit(Connection connection) throws SQLException {
        List<String> problems = new ArrayList<>();
        for (String column : AMOUNT_COLUMNS) {
            String table = column.substring(0, column.indexOf('.'));
            String name = column.substring(column.indexOf('.') + 1);
            String misfit = name + " <> ROUND(" + name + ", 2) OR ABS(" + name + ") > " + MAX_AMOUNT;
            long misfits = count(connection, table, misfit);
            if (misfits > 0) {
                List<Long> ids = firstIds(connection, table, misfit);
                problems.add(misfits + " row(s) of " + column + " have more than two decimal places or exceed "
                        + MAX_AMOUNT + " (id " + ids.stream().map(String::valueOf).collect(Collectors.joining(", "))
                        + (misfits > ids.size() ? ", ..." : "") + ")");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join("; ", problems) + ". NUMERIC(12,2) cannot store them without "
                    + "rounding, and V11 does not round money: correct those amounts, then run the migration again "
                    + "(docs/operations.md, \"Money with currency (V11)\").");
        }
    }


    /**
     * The currencies table, filled with {@link #SUPPORTED_CURRENCIES}. Its checks refuse a code that is not three
     * upper-case letters, and minor units other than 2, or 0 for {@link #ZERO_MINOR_UNIT_CURRENCIES}, which are the
     * currencies the menu_items check knows to have whole prices.
     */
    private static void createCurrencies(Connection connection, boolean postgres) throws SQLException {
        execute(connection, "CREATE TABLE currencies ("
                + "code CHAR(3) PRIMARY KEY NOT NULL, "
                + "minor_units SMALLINT NOT NULL, "
                + "CONSTRAINT chk_currency_code CHECK (" + upperCaseCodeCheck(postgres, "code") + "), "
                + "CONSTRAINT chk_currency_minor_units CHECK (minor_units = CASE WHEN code IN ("
                + quotedList(ZERO_MINOR_UNIT_CURRENCIES) + ") THEN 0 ELSE 2 END))");
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO currencies (code, minor_units) VALUES (?, ?)")) {
            for (Map.Entry<String, Integer> currency : SUPPORTED_CURRENCIES.entrySet()) {
                insert.setString(1, currency.getKey());
                insert.setInt(2, currency.getValue());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }


    private static void addCurrencyAndScale(
            Connection connection,
            boolean postgres,
            String table,
            CurrencyTable currencyTable
    ) throws SQLException {
        String addColumn = "ADD COLUMN currency CHAR(3) DEFAULT 'USD' NOT NULL";
        String addCheck = "ADD CONSTRAINT " + currencyTable.checkName() + " CHECK ("
                + upperCaseCodeCheck(postgres, "currency") + ")";
        String changeScale = currencyTable.amountColumn() == null
                ? null
                : "ALTER COLUMN " + currencyTable.amountColumn() + " SET DATA TYPE NUMERIC(12,2)";
        if (postgres) {
            // One statement, so PostgreSQL rewrites the table once.
            List<String> clauses = new ArrayList<>(List.of(addColumn));
            if (changeScale != null) {
                clauses.add(changeScale);
            }
            clauses.add(addCheck);
            execute(connection, "ALTER TABLE " + table + " " + String.join(", ", clauses));
            return;
        }
        execute(connection, "ALTER TABLE " + table + " " + addColumn);
        if (changeScale != null) {
            execute(connection, "ALTER TABLE " + table + " " + changeScale);
        }
        execute(connection, "ALTER TABLE " + table + " " + addCheck);
    }


    private static String upperCaseCodeCheck(boolean postgres, String column) {
        return postgres ? column + " ~ '^[A-Z]{3}$'" : "REGEXP_LIKE(" + column + ", '^[A-Z]{3}$')";
    }


    private static String quotedList(List<String> codes) {
        return codes.stream().map(code -> "'" + code + "'").collect(Collectors.joining(", "));
    }


    /**
     * @return the rows of {@code table} that match {@code condition}
     */
    private static long count(Connection connection, String table, String condition) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE " + condition;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }


    /**
     * @return the smallest {@link #REPORTED_IDS} ids of the rows of {@code table} that match {@code condition}
     */
    private static List<Long> firstIds(Connection connection, String table, String condition) throws SQLException {
        String sql = "SELECT id FROM " + table + " WHERE " + condition + " ORDER BY id LIMIT " + REPORTED_IDS;
        List<Long> ids = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                ids.add(result.getLong(1));
            }
        }
        return ids;
    }


    private static void execute(Connection connection, String sql) throws SQLException {
        LOGGER.info("{}", sql.strip());
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
