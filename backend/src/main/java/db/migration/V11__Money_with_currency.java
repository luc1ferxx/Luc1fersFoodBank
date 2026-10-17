package db.migration;


import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * Gives every stored amount a currency and a fixed scale.
 *
 * <ul>
 *     <li>Every table with an amount gets {@code currency CHAR(3) NOT NULL}, an ISO 4217 code checked to be three
 *     upper-case letters: restaurants (a restaurant prices in one currency), menu_items, carts, order_items (cart
 *     lines), orders, order_history_items and merchant_orders. Existing rows become USD, the only currency the
 *     application had. The column default stays 'USD' so that pods of the previous release, which do not write the
 *     column, keep working during the rollout (every row they write is USD, as before); this release always writes
 *     the currency explicitly.</li>
 *     <li>Every amount becomes {@code NUMERIC(12,2)}; the existing non-negative checks (V2) stay, and
 *     merchant_orders.total_price gets one.</li>
 *     <li>Composite keys make a cart single-currency in the database: a menu item has its restaurant's currency
 *     ({@code (restaurant_id, currency)} references restaurants), a cart line has its menu item's and its cart's
 *     currency. A cart's currency can therefore only change while it is empty, and a restaurant's only while it has
 *     no menu items. Orders, their items and the merchant inbox are history without such keys; the application
 *     copies the cart's currency onto them.</li>
 * </ul>
 *
 * <p>Existing data is checked before anything changes. Unit prices (menu_items, order_items, order_history_items)
 * must already have at most two decimal places and fit twelve digits: the migration fails, naming the column, rather
 * than round a price. Totals (carts, orders, merchant_orders) were summed through DOUBLE PRECISION before this
 * release and can carry binary noise such as 14.669999999999998; they are rounded to cents ({@code ROUND(x, 2)}).
 *
 * <p>This is a Java migration because the currency check differs (PostgreSQL's {@code ~} operator, H2's
 * {@code REGEXP_LIKE}) and the data checks must stop the migration with a message.
 *
 * <p>Production caveat: changing NUMERIC to NUMERIC(12,2) rewrites each of the six tables with an amount under an
 * ACCESS EXCLUSIVE lock (one ALTER TABLE per table on PostgreSQL), held until the migration commits; the currency
 * checks and the composite keys scan them once more. Adding the currency columns with a constant default does not
 * rewrite them. Fine for small databases; plan a maintenance window for large ones (docs/operations.md, "Money with
 * currency (V11)").
 */
public class V11__Money_with_currency extends BaseJavaMigration {

    static final String MAX_AMOUNT = "9999999999.99";

    /**
     * Prices of one unit, as table.column: they must already fit NUMERIC(12,2) exactly.
     */
    static final List<String> UNIT_PRICE_COLUMNS = List.of(
            "menu_items.price",
            "order_items.price",
            "order_history_items.price"
    );

    /**
     * Totals, as table.column: rounded to cents.
     */
    static final List<String> TOTAL_COLUMNS = List.of(
            "carts.total_price",
            "orders.total_price",
            "merchant_orders.total_price"
    );

    /**
     * Every table that gets a currency, with its amount column (null for restaurants) and the name of its currency
     * check, in an order where referenced tables come first.
     */
    static final Map<String, CurrencyTable> CURRENCY_TABLES = currencyTables();

    private static final Logger LOGGER = LoggerFactory.getLogger(V11__Money_with_currency.class);


    record CurrencyTable(String amountColumn, String checkName) {
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

        requireAmountsFit(connection);
        for (String column : TOTAL_COLUMNS) {
            roundToCents(connection, column);
        }

        for (Map.Entry<String, CurrencyTable> table : CURRENCY_TABLES.entrySet()) {
            addCurrencyAndScale(connection, postgres, table.getKey(), table.getValue());
        }
        execute(connection, "ALTER TABLE merchant_orders ADD CONSTRAINT chk_merchant_order_total_price_non_negative "
                + "CHECK (total_price >= 0)");

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
     * Fails the migration, before it changes anything, when a price has more than two decimal places or any amount
     * exceeds twelve digits; the message names every such column.
     */
    private static void requireAmountsFit(Connection connection) throws SQLException {
        List<String> problems = new ArrayList<>();
        for (String column : UNIT_PRICE_COLUMNS) {
            String name = column.substring(column.indexOf('.') + 1);
            long misfits = count(connection, column, name + " <> ROUND(" + name + ", 2) OR ABS(" + name + ") > "
                    + MAX_AMOUNT);
            if (misfits > 0) {
                problems.add(misfits + " row(s) of " + column + " have more than two decimal places or exceed "
                        + MAX_AMOUNT);
            }
        }
        for (String column : TOTAL_COLUMNS) {
            String name = column.substring(column.indexOf('.') + 1);
            long misfits = count(connection, column, "ABS(ROUND(" + name + ", 2)) > " + MAX_AMOUNT);
            if (misfits > 0) {
                problems.add(misfits + " row(s) of " + column + " exceed " + MAX_AMOUNT);
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join("; ", problems) + ". NUMERIC(12,2) cannot store them without "
                    + "rounding: correct those amounts, then run the migration again (docs/operations.md, "
                    + "\"Money with currency (V11)\").");
        }
    }


    private static void roundToCents(Connection connection, String column) throws SQLException {
        String table = column.substring(0, column.indexOf('.'));
        String name = column.substring(column.indexOf('.') + 1);
        try (Statement statement = connection.createStatement()) {
            int rounded = statement.executeUpdate("UPDATE " + table + " SET " + name + " = ROUND(" + name + ", 2) WHERE "
                    + name + " <> ROUND(" + name + ", 2)");
            if (rounded > 0) {
                LOGGER.info("Rounded {} row(s) of {} to cents", rounded, column);
            }
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
                + (postgres ? "currency ~ '^[A-Z]{3}$'" : "REGEXP_LIKE(currency, '^[A-Z]{3}$')") + ")";
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


    /**
     * @return the rows of the table of {@code column} (table.column) that match {@code condition}
     */
    private static long count(Connection connection, String column, String condition) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + column.substring(0, column.indexOf('.')) + " WHERE " + condition;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }


    private static void execute(Connection connection, String sql) throws SQLException {
        LOGGER.info("{}", sql.strip());
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
