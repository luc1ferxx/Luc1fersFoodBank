package com.laioffer.onlineorder.partition;


import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;


import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;


/**
 * Reads the partition layout from the PostgreSQL catalog. On any other database (H2) no table is partitioned.
 */
@Component
public class PartitionCatalog {

    private final JdbcTemplate jdbcTemplate;
    private volatile Boolean postgres;
    // Only ever turns true: V10 partitions a table at startup, and nothing turns it back into a plain table.
    private final Map<PartitionedTable, Boolean> partitioned = new EnumMap<>(PartitionedTable.class);


    public PartitionCatalog(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }


    public boolean isPostgres() {
        Boolean known = postgres;
        if (known == null) {
            known = Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection ->
                    "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())));
            postgres = known;
        }
        return known;
    }


    /**
     * Whether {@code table} is a partitioned table: on PostgreSQL after V10, never on H2.
     */
    public boolean isPartitioned(PartitionedTable table) {
        synchronized (partitioned) {
            if (Boolean.TRUE.equals(partitioned.get(table))) {
                return true;
            }
        }
        if (!isPostgres()) {
            return false;
        }
        Boolean result = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_partitioned_table WHERE partrelid = to_regclass(?))",
                Boolean.class,
                table.tableName()
        );
        boolean isPartitioned = Boolean.TRUE.equals(result);
        if (isPartitioned) {
            synchronized (partitioned) {
                partitioned.put(table, Boolean.TRUE);
            }
        }
        return isPartitioned;
    }


    /**
     * The partitions of {@code table}, range partitions by lower bound first, the default partition last.
     */
    public List<TablePartition> partitions(PartitionedTable table) {
        return jdbcTemplate.query("""
                                SELECT child.relname, pg_get_expr(child.relpartbound, child.oid)
                                FROM pg_inherits
                                JOIN pg_class child ON child.oid = pg_inherits.inhrelid
                                WHERE pg_inherits.inhparent = to_regclass(?)
                                """,
                        (row, rowNumber) -> TablePartition.parse(row.getString(1), row.getString(2)),
                        table.tableName()
                ).stream()
                .sorted(Comparator.comparing(TablePartition::isDefault)
                        .thenComparing(TablePartition::from, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }


    /**
     * Whether a relation (a partition, an index) of this name exists in the current schema.
     */
    public boolean relationExists(String name) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL",
                Boolean.class,
                name
        ));
    }
}
