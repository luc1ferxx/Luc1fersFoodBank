package com.laioffer.onlineorder.partition;


import org.junit.jupiter.api.Assertions;


import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;


/**
 * The same upgrade on H2 in PostgreSQL mode (the h2 profile): V10 leaves the tables as they are.
 */
class ScaleMigrationH2Tests extends AbstractScaleMigrationTests {

    private final String jdbcUrl = "jdbc:h2:mem:scale-migration-" + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";


    @Override
    String jdbcUrl() {
        return jdbcUrl;
    }


    @Override
    String username() {
        return "sa";
    }


    @Override
    String password() {
        return "";
    }


    @Override
    void assertTableLayout(Statement statement) throws SQLException {
        // processed_events keeps its global unique constraint, which deduplicates on H2.
        Assertions.assertEquals(List.of("uq_processed_event_consumer_dedup"), strings(statement, """
                SELECT LOWER(constraint_name) FROM information_schema.table_constraints
                WHERE LOWER(table_name) = 'processed_events' AND constraint_type = 'UNIQUE'
                """));
        Assertions.assertEquals(List.of("0"), strings(statement, """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE LOWER(table_name) LIKE '%\\_p20%' ESCAPE '\\' OR LOWER(table_name) LIKE '%\\_default' ESCAPE '\\'
                """));
    }
}
