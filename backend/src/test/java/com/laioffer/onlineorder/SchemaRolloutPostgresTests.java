package com.laioffer.onlineorder;


import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;


/**
 * The operator procedures of docs/operations.md section 7, run against PostgreSQL:
 * <ul>
 *     <li>the online widen-and-swap of a large table's keys ahead of V7, for orders (a key that other tables reference)
 *     and idempotency_requests (a unique constraint that checkout uses as its ON CONFLICT target), checked with the
 *     db/ops/key_dependents.sql inventory: every constraint and index is back under its name and definition, and V7
 *     then skips the swapped columns;</li>
 *     <li>db/ops/repair_order_history.sql, which records what pods of an older version changed while V7_1 rolled out.</li>
 * </ul>
 * Each test works in a database of its own.
 */
@Testcontainers(disabledWithoutDocker = true)
class SchemaRolloutPostgresTests {

    @Container
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("onlineorder")
            .withUsername("postgres")
            .withPassword("secret");


    @Test
    void keyInventory_shouldListTheOnConflictTargetsAndBeUnchangedByV7() throws Exception {
        String url = createDatabase("inventory");
        migrate(url, "6");
        List<String> before;
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            Assertions.assertEquals(v7Columns(), narrowKeyColumns(statement),
                    "V7 widens exactly the 32-bit key columns of V6");
            before = keyInventory(statement);
        }

        // The unique constraints the code relies on, as ON CONFLICT targets or for deduplication, and the indexes on
        // key columns: what a swap that drops its old columns would lose with them.
        List<String> names = before.stream().map(row -> row.split("\\|")[2]).toList();
        for (String expected : List.of(
                "uq_idempotency_request", "uk_order_item_cart_menu", "uq_notification_order_event",
                "carts_customer_id_key", "uq_merchant_account_customer", "uq_merchant_order_order",
                "uq_delivery_order", "uq_order_deadline_order_type", "dispatch_orders_pkey",
                "fk_history_order", "fk_notification_order", "fk_idempotency_order", "fk_order_item_cart",
                "idx_orders_customer_created_id", "idx_orders_restaurant_created_id", "idx_order_items_cart_id_id",
                "idx_outbox_events_unpublished_id", "idx_deliveries_rider_status", "idx_merchant_accounts_restaurant_id")) {
            Assertions.assertTrue(names.contains(expected), expected + " missing from " + names);
        }
        Assertions.assertTrue(before.contains(
                "idempotency_requests|constraint|uq_idempotency_request|UNIQUE (customer_id, scope, idempotency_key)"),
                before.toString());

        migrate(url, "7");
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            Assertions.assertEquals(before, keyInventory(statement), "ALTER COLUMN TYPE keeps every name and definition");
            Assertions.assertTrue(narrowKeyColumns(statement).isEmpty());
        }
    }


    @Test
    void onlineSwap_shouldKeepEveryConstraintAndLetV7SkipTheSwappedColumns() throws Exception {
        String url = createDatabase("swap");
        migrate(url, "6");
        List<String> before;
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            seed(statement);
            before = keyInventory(statement);

            // 1. New columns (catalog only) kept in step by a trigger.
            addShadowColumns(statement, "orders", "id", "customer_id", "restaurant_id");
            addShadowColumns(statement, "idempotency_requests", "id", "customer_id", "order_id");
            insertOrder(statement, "PAID"); // traffic while the swap is prepared
            insertIdempotencyRequest(statement, "checkout:during");

            // 2. Backfill in batches, prove NOT NULL without a scan under lock, build every index on the new columns.
            backfill(statement, "orders");
            backfill(statement, "idempotency_requests");
            for (String column : List.of("orders.id", "orders.customer_id", "idempotency_requests.id",
                    "idempotency_requests.customer_id")) {
                String[] parts = column.split("\\.");
                statement.execute("ALTER TABLE " + parts[0] + " ADD CONSTRAINT " + parts[0] + "_" + parts[1]
                        + "_new_not_null CHECK (" + parts[1] + "_new IS NOT NULL) NOT VALID");
                statement.execute("ALTER TABLE " + parts[0] + " VALIDATE CONSTRAINT " + parts[0] + "_" + parts[1]
                        + "_new_not_null");
            }
            statement.execute("CREATE UNIQUE INDEX CONCURRENTLY orders_pkey_new ON orders (id_new)");
            statement.execute("CREATE INDEX CONCURRENTLY idx_orders_customer_created_id_new"
                    + " ON orders (customer_id_new, created_at, id_new)");
            statement.execute("CREATE INDEX CONCURRENTLY idx_orders_restaurant_created_id_new"
                    + " ON orders (restaurant_id_new, created_at, id_new)");
            statement.execute("CREATE UNIQUE INDEX CONCURRENTLY idempotency_requests_pkey_new"
                    + " ON idempotency_requests (id_new)");
            statement.execute("CREATE UNIQUE INDEX CONCURRENTLY uq_idempotency_request_new"
                    + " ON idempotency_requests (customer_id_new, scope, idempotency_key)");
        }

        // 3. The swap, one short transaction per table.
        try (Connection connection = connect(url)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = '5s'");
                statement.execute("LOCK TABLE orders, order_history_items, order_notifications, idempotency_requests"
                        + " IN ACCESS EXCLUSIVE MODE");
                statement.execute("ALTER TABLE order_history_items DROP CONSTRAINT fk_history_order");
                statement.execute("ALTER TABLE order_notifications DROP CONSTRAINT fk_notification_order");
                statement.execute("ALTER TABLE idempotency_requests DROP CONSTRAINT fk_idempotency_order");
                statement.execute("ALTER TABLE orders DROP CONSTRAINT orders_pkey, DROP CONSTRAINT fk_order_customer");
                statement.execute("DROP INDEX idx_orders_customer_created_id, idx_orders_restaurant_created_id");
                swapColumns(statement, "orders", List.of("id", "customer_id", "restaurant_id"), List.of("id", "customer_id"));
                statement.execute("ALTER TABLE orders ADD CONSTRAINT orders_pkey PRIMARY KEY USING INDEX orders_pkey_new");
                statement.execute("ALTER INDEX idx_orders_customer_created_id_new RENAME TO idx_orders_customer_created_id");
                statement.execute("ALTER INDEX idx_orders_restaurant_created_id_new RENAME TO idx_orders_restaurant_created_id");
                statement.execute("ALTER TABLE orders ADD CONSTRAINT fk_order_customer FOREIGN KEY (customer_id)"
                        + " REFERENCES customers(id) ON DELETE CASCADE NOT VALID");
                statement.execute("ALTER TABLE order_history_items ADD CONSTRAINT fk_history_order FOREIGN KEY (order_id)"
                        + " REFERENCES orders(id) ON DELETE CASCADE NOT VALID");
                statement.execute("ALTER TABLE order_notifications ADD CONSTRAINT fk_notification_order FOREIGN KEY (order_id)"
                        + " REFERENCES orders(id) ON DELETE CASCADE NOT VALID");
                statement.execute("ALTER TABLE idempotency_requests ADD CONSTRAINT fk_idempotency_order FOREIGN KEY (order_id)"
                        + " REFERENCES orders(id) NOT VALID");
            }
            connection.commit();

            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = '5s'");
                statement.execute("LOCK TABLE idempotency_requests IN ACCESS EXCLUSIVE MODE");
                statement.execute("ALTER TABLE idempotency_requests DROP CONSTRAINT idempotency_requests_pkey,"
                        + " DROP CONSTRAINT uq_idempotency_request, DROP CONSTRAINT fk_idempotency_customer,"
                        + " DROP CONSTRAINT fk_idempotency_order");
                swapColumns(statement, "idempotency_requests", List.of("id", "customer_id", "order_id"),
                        List.of("id", "customer_id"));
                statement.execute("ALTER TABLE idempotency_requests ADD CONSTRAINT idempotency_requests_pkey"
                        + " PRIMARY KEY USING INDEX idempotency_requests_pkey_new");
                statement.execute("ALTER TABLE idempotency_requests ADD CONSTRAINT uq_idempotency_request"
                        + " UNIQUE USING INDEX uq_idempotency_request_new");
                statement.execute("ALTER TABLE idempotency_requests ADD CONSTRAINT fk_idempotency_customer"
                        + " FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE NOT VALID");
                statement.execute("ALTER TABLE idempotency_requests ADD CONSTRAINT fk_idempotency_order"
                        + " FOREIGN KEY (order_id) REFERENCES orders(id) NOT VALID");
            }
            connection.commit();
        }

        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            // 4. Validate (no write lock), compare the inventory, drop the old columns.
            for (String constraint : List.of("orders.fk_order_customer", "order_history_items.fk_history_order",
                    "order_notifications.fk_notification_order", "idempotency_requests.fk_idempotency_customer",
                    "idempotency_requests.fk_idempotency_order")) {
                String[] parts = constraint.split("\\.");
                statement.execute("ALTER TABLE " + parts[0] + " VALIDATE CONSTRAINT " + parts[1]);
            }
            Assertions.assertEquals(before, keyInventory(statement), "every constraint and index is back as it was");
            statement.execute("ALTER TABLE orders DROP COLUMN id_old, DROP COLUMN customer_id_old,"
                    + " DROP COLUMN restaurant_id_old");
            statement.execute("ALTER TABLE idempotency_requests DROP COLUMN id_old, DROP COLUMN customer_id_old,"
                    + " DROP COLUMN order_id_old");

            Assertions.assertEquals(List.of("bigint", "bigint", "bigint"), strings(statement, """
                    SELECT data_type FROM information_schema.columns
                    WHERE table_name = 'orders' AND column_name IN ('id', 'customer_id', 'restaurant_id')
                    """));
            // The checkout ON CONFLICT target is back: a repeat of a key is absorbed, not a second row or an error.
            Assertions.assertEquals(1, insertIdempotencyRequest(statement, "checkout:after"));
            Assertions.assertEquals(0, insertIdempotencyRequest(statement, "checkout:after"));
            Assertions.assertEquals(0, insertIdempotencyRequest(statement, "checkout:during"));
            // The moved sequence still numbers new rows after the old ones, and the foreign keys hold.
            long previousOrderId = Long.parseLong(strings(statement, "SELECT MAX(id) FROM orders").get(0));
            Assertions.assertEquals(previousOrderId + 1, insertOrder(statement, "PLACED"));
            Assertions.assertEquals(List.of("PLACED", "PAID", "PLACED"), strings(statement, "SELECT status FROM orders ORDER BY id"));
            Assertions.assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO order_history_items (order_id, menu_item_id, restaurant_id, price, quantity, menu_item_name)
                    VALUES (987654321, 1, 1, 1, 1, 'nothing')
                    """));
        }

        // V7 then skips the swapped columns and widens the rest; V7_1 and V7_2 follow.
        migrate(url, null);
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            Assertions.assertTrue(narrowKeyColumns(statement).isEmpty());
            Assertions.assertEquals(List.of("bigint", "bigint"), strings(statement, """
                    SELECT format_type(seqtypid, NULL) FROM pg_sequence
                    WHERE seqrelid IN ('orders_id_seq'::regclass, 'idempotency_requests_id_seq'::regclass)
                    """));
            Assertions.assertEquals(List.of("3"), strings(statement, "SELECT COUNT(*) FROM order_status_history"));
        }
    }


    @Test
    void historyRepair_shouldRecordWhatOldPodsLeftOutExactlyOnce() throws Exception {
        String url = createDatabase("repair");
        migrate(url, null);
        long seeded;
        long current;
        long created;
        long changed;
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            seed(statement); // its order has no history row either
            seeded = Long.parseLong(strings(statement, "SELECT MAX(id) FROM orders").get(0));
            // An order of the current version: created with its history row.
            current = insertOrder(statement, "PAID");
            statement.execute("UPDATE orders SET updated_at = created_at WHERE id = " + current);
            statement.execute("""
                    INSERT INTO order_status_history (order_id, from_status, to_status, actor_type, actor_id, occurred_at)
                    SELECT id, NULL, status, 'CUSTOMER', customer_id, created_at FROM orders WHERE id = %d
                    """.formatted(current));
            // An order an old pod created (no history; updated_at from the column default) ...
            created = insertOrder(statement, "PAID");
            // ... and one whose status an old pod changed after the V7_1 backfill row.
            changed = insertOrder(statement, "PAID");
            statement.execute("UPDATE orders SET updated_at = created_at WHERE id = " + changed);
            statement.execute("""
                    INSERT INTO order_status_history (order_id, from_status, to_status, actor_type, reason, occurred_at)
                    SELECT id, NULL, status, 'SYSTEM', 'backfill', created_at FROM orders WHERE id = %d
                    """.formatted(changed));
            statement.execute("UPDATE orders SET status = 'ACCEPTED' WHERE id = " + changed);
        }
        LocalDateTime beforeRepair = LocalDateTime.now().minusMinutes(1);

        repairHistory(url);
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            List<String> afterFirstRun = history(statement);
            Assertions.assertEquals(List.of(
                    seeded + "|null|PLACED|SYSTEM|backfill",
                    current + "|null|PAID|CUSTOMER|null",
                    created + "|null|PAID|SYSTEM|backfill",
                    changed + "|null|PAID|SYSTEM|backfill",
                    changed + "|PAID|ACCEPTED|SYSTEM|backfill"
            ), afterFirstRun);
            Assertions.assertEquals(List.of("true"), strings(statement, """
                    SELECT BOOL_AND(o.updated_at = o.created_at AND h.occurred_at = o.created_at)::text
                    FROM orders o JOIN order_status_history h ON h.order_id = o.id
                    WHERE o.id = %d
                    """.formatted(created)));
            Assertions.assertTrue(timestamp(statement, "SELECT updated_at FROM orders WHERE id = " + changed)
                    .isAfter(beforeRepair), "the drifted order's updated_at moves to the repair");

            repairHistory(url);
            Assertions.assertEquals(afterFirstRun, history(statement), "a second run changes nothing");
        }
    }


    private static String createDatabase(String name) throws SQLException {
        try (Connection connection = connect(POSTGRES.getJdbcUrl()); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                + "/" + name;
    }


    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }


    private static void migrate(String url, String target) {
        var configuration = Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }


    private static List<String> keyInventory(Statement statement) throws SQLException, IOException {
        String query = new ClassPathResource("db/ops/key_dependents.sql").getContentAsString(StandardCharsets.UTF_8);
        List<String> rows = new ArrayList<>();
        try (ResultSet result = statement.executeQuery(query)) {
            while (result.next()) {
                rows.add(result.getString(1) + "|" + result.getString(2) + "|" + result.getString(3) + "|"
                        + result.getString(4));
            }
        }
        return rows;
    }


    private static TreeSet<String> narrowKeyColumns(Statement statement) throws SQLException {
        return new TreeSet<>(strings(statement, """
                SELECT table_name || '.' || column_name
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND data_type = 'integer'
                  AND (column_name = 'id' OR column_name LIKE '%\\_id')
                """));
    }


    @SuppressWarnings("unchecked")
    private static TreeSet<String> v7Columns() throws ReflectiveOperationException {
        Field field = Class.forName("db.migration.V7__Widen_keys_to_bigint").getDeclaredField("ID_COLUMNS");
        field.setAccessible(true);
        TreeSet<String> columns = new TreeSet<>();
        ((Map<String, List<String>>) field.get(null)).forEach(
                (table, tableColumns) -> tableColumns.forEach(column -> columns.add(table + "." + column)));
        return columns;
    }


    private static void addShadowColumns(Statement statement, String table, String... columns) throws SQLException {
        List<String> adds = new ArrayList<>();
        StringBuilder copies = new StringBuilder();
        for (String column : columns) {
            adds.add("ADD COLUMN " + column + "_new BIGINT");
            copies.append("NEW.").append(column).append("_new := NEW.").append(column).append("; ");
        }
        statement.execute("ALTER TABLE " + table + " " + String.join(", ", adds));
        statement.execute("CREATE FUNCTION " + table + "_copy_keys() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + copies + "RETURN NEW; END $$");
        statement.execute("CREATE TRIGGER " + table + "_copy_keys BEFORE INSERT OR UPDATE ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION " + table + "_copy_keys()");
    }


    private static void backfill(Statement statement, String table) throws SQLException {
        long max = Long.parseLong(strings(statement, "SELECT COALESCE(MAX(id), 0) FROM " + table).get(0));
        for (long from = 0; from <= max; from += 2) {
            // The trigger copies every key column of the row.
            statement.execute("UPDATE " + table + " SET id_new = id WHERE id BETWEEN " + from + " AND " + (from + 1)
                    + " AND id_new IS NULL");
        }
    }


    /**
     * The renames of the swap transaction: the trigger goes first (it names the *_new columns, so every insert would
     * fail after the rename), the old columns stop being NOT NULL and lose the sequence default (new rows no longer fill
     * them), the new ones become NOT NULL without a scan (the validated CHECK) and take over the sequence.
     */
    private static void swapColumns(Statement statement, String table, List<String> columns, List<String> notNull)
            throws SQLException {
        statement.execute("DROP TRIGGER " + table + "_copy_keys ON " + table);
        statement.execute("DROP FUNCTION " + table + "_copy_keys()");
        for (String column : columns) {
            statement.execute("ALTER TABLE " + table + " RENAME COLUMN " + column + " TO " + column + "_old");
            statement.execute("ALTER TABLE " + table + " RENAME COLUMN " + column + "_new TO " + column);
        }
        List<String> clauses = new ArrayList<>();
        for (String column : notNull) {
            clauses.add("ALTER COLUMN " + column + " SET NOT NULL");
            clauses.add("ALTER COLUMN " + column + "_old DROP NOT NULL");
        }
        clauses.add("ALTER COLUMN id_old DROP DEFAULT");
        statement.execute("ALTER TABLE " + table + " " + String.join(", ", clauses));
        for (String column : notNull) {
            statement.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + table + "_" + column + "_new_not_null");
        }
        statement.execute("ALTER SEQUENCE " + table + "_id_seq AS bigint OWNED BY " + table + ".id");
        statement.execute("ALTER TABLE " + table + " ALTER COLUMN id SET DEFAULT nextval('" + table + "_id_seq')");
    }


    private static void seed(Statement statement) throws SQLException {
        statement.execute("INSERT INTO customers (email, password) VALUES ('rollout@example.com', 'x')");
        statement.execute("INSERT INTO restaurants (name) VALUES ('Rollout Diner')");
        statement.execute("INSERT INTO menu_items (restaurant_id, name, price) SELECT id, 'Soup', 5 FROM restaurants");
        long orderId = insertOrder(statement, "PLACED");
        statement.execute("""
                INSERT INTO order_history_items (order_id, menu_item_id, restaurant_id, price, quantity, menu_item_name)
                SELECT %d, id, restaurant_id, 5, 1, 'Soup' FROM menu_items
                """.formatted(orderId));
        insertIdempotencyRequest(statement, "checkout:before");
    }


    /**
     * Gives the order an order number once V10_1 has added the column (the application's inserts always do).
     */
    private static long insertOrder(Statement statement, String status) throws SQLException {
        boolean numbered = !strings(statement, """
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'orders' AND column_name = 'order_no'
                """).isEmpty();
        try (ResultSet result = statement.executeQuery("""
                INSERT INTO orders (customer_id, total_price, status, created_at, restaurant_id%s)
                SELECT customers.id, 5, '%s', LOCALTIMESTAMP - INTERVAL '1 hour', restaurants.id%s
                FROM customers, restaurants
                RETURNING id
                """.formatted(
                numbered ? ", order_no" : "",
                status,
                numbered ? ", (SELECT COALESCE(MAX(order_no), 0) + 1 FROM orders)" : ""))) {
            result.next();
            return result.getLong(1);
        }
    }


    /**
     * IdempotencyRequestRepository.insertIfAbsent's statement.
     */
    private static int insertIdempotencyRequest(Statement statement, String key) throws SQLException {
        return statement.executeUpdate("""
                INSERT INTO idempotency_requests (customer_id, scope, idempotency_key, request_hash, status, order_id,
                                                  created_at, updated_at)
                SELECT id, 'checkout', '%s', 'hash', 'PROCESSING', NULL, LOCALTIMESTAMP, LOCALTIMESTAMP
                FROM customers
                ON CONFLICT (customer_id, scope, idempotency_key) DO NOTHING
                """.formatted(key));
    }


    private static void repairHistory(String url) throws SQLException {
        try (Connection connection = connect(url)) {
            connection.setAutoCommit(false);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/ops/repair_order_history.sql"));
            connection.commit();
        }
    }


    private static List<String> history(Statement statement) throws SQLException {
        return strings(statement, """
                SELECT order_id || '|' || COALESCE(from_status, 'null') || '|' || to_status || '|' || actor_type || '|'
                       || COALESCE(reason, 'null')
                FROM order_status_history
                ORDER BY order_id, id
                """);
    }


    private static LocalDateTime timestamp(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getTimestamp(1).toLocalDateTime();
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
