package com.laioffer.onlineorder;


import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


import java.sql.SQLException;
import java.sql.Statement;


@Testcontainers(disabledWithoutDocker = true)
class KeyWideningMigrationPostgresTests extends AbstractKeyWideningMigrationTests {

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
    void restartOrderIdsAt(Statement statement, long nextId) throws SQLException {
        statement.execute("SELECT setval(pg_get_serial_sequence('orders', 'id'), " + nextId + ", false)");
    }
}
