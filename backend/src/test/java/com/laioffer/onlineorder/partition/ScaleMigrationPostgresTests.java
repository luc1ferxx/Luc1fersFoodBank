package com.laioffer.onlineorder.partition;


import org.junit.jupiter.api.Assertions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;


import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;


@Testcontainers(disabledWithoutDocker = true)
class ScaleMigrationPostgresTests extends AbstractScaleMigrationTests {

    @Container
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("onlineorder")
            .withUsername("postgres")
            .withPassword("secret");


    @Override
    String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }


    @Override
    String username() {
        return POSTGRES.getUsername();
    }


    @Override
    String password() {
        return POSTGRES.getPassword();
    }


    @Override
    void assertTableLayout(Statement statement) throws SQLException {
        Assertions.assertEquals(List.of("audit_log", "order_status_history", "processed_events"), strings(statement, """
                SELECT partrelid::regclass::text FROM pg_partitioned_table ORDER BY 1
                """));

        // A partition per month with rows, the current month and three ahead, and the default partition.
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        for (PartitionedTable table : PartitionedTable.values()) {
            List<String> expected = new ArrayList<>(List.of(
                    table.partitionName(LocalDate.of(2026, 1, 1)),
                    table.partitionName(LocalDate.of(2026, 3, 1))
            ));
            for (int ahead = 0; ahead <= 3; ahead++) {
                String name = table.partitionName(currentMonth.plusMonths(ahead));
                if (!expected.contains(name)) {
                    expected.add(name);
                }
            }
            expected.sort(String::compareTo);
            expected.add(table.defaultPartitionName());
            Assertions.assertEquals(expected, strings(statement, """
                    SELECT child.relname
                    FROM pg_inherits JOIN pg_class child ON child.oid = pg_inherits.inhrelid
                    WHERE pg_inherits.inhparent = '%s'::regclass
                    ORDER BY child.relname LIKE '%%_default', child.relname
                    """.formatted(table.tableName())), table.tableName());
            Assertions.assertEquals(List.of("PRIMARY KEY (id, " + table.partitionColumn() + ")"), strings(statement, """
                    SELECT pg_get_constraintdef(oid) FROM pg_constraint
                    WHERE conrelid = '%s'::regclass AND contype = 'p'
                    """.formatted(table.tableName())));
            Assertions.assertEquals(List.of("true"), strings(statement,
                    "SELECT (pg_get_serial_sequence('" + table.tableName() + "', 'id') IS NOT NULL)::text"));
        }

        // processed_events: no cross-partition unique constraint any more, a unique index on every partition instead.
        Assertions.assertEquals(List.of("0"), strings(statement,
                "SELECT COUNT(*) FROM pg_constraint WHERE conname = 'uq_processed_event_consumer_dedup'"));
        Assertions.assertEquals(List.of("0"), strings(statement, """
                SELECT COUNT(*)
                FROM pg_inherits JOIN pg_class child ON child.oid = pg_inherits.inhrelid
                WHERE pg_inherits.inhparent = 'processed_events'::regclass
                  AND NOT EXISTS (
                      SELECT 1 FROM pg_index
                      WHERE pg_index.indrelid = child.oid
                        AND pg_index.indisunique
                        AND pg_get_indexdef(pg_index.indexrelid) LIKE '%(consumer_name, dedup_key)'
                  )
                """));
        // The audit and history indexes exist on the partitioned tables (and so on every partition).
        Assertions.assertEquals(List.of("idx_audit_log_actor_id", "idx_audit_log_target_id",
                "idx_order_status_history_order_id_id"), strings(statement, """
                SELECT indexname FROM pg_indexes
                WHERE tablename IN ('audit_log', 'order_status_history') AND indexname LIKE 'idx_%'
                ORDER BY indexname
                """));
        // Nothing left of the copies.
        Assertions.assertEquals(List.of("0"), strings(statement,
                "SELECT COUNT(*) FROM pg_class WHERE relname LIKE '%_unpartitioned'"));
    }
}
