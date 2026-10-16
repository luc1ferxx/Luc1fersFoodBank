package com.laioffer.onlineorder;


import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;


/**
 * The same upgrade on H2 in PostgreSQL mode, with the settings of the h2 profile's URL.
 */
class KeyWideningMigrationH2Tests extends AbstractKeyWideningMigrationTests {

    private final String jdbcUrl = "jdbc:h2:mem:key-widening-" + UUID.randomUUID()
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
    void restartOrderIdsAt(Statement statement, long nextId) throws SQLException {
        statement.execute("ALTER TABLE orders ALTER COLUMN id RESTART WITH " + nextId);
    }
}
