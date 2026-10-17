package com.laioffer.onlineorder;


import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;


import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * The money migration on PostgreSQL; every test migrates its own database of the container.
 */
@Testcontainers(disabledWithoutDocker = true)
class MoneyCurrencyMigrationPostgresTests extends AbstractMoneyCurrencyMigrationTests {

    @Container
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("onlineorder")
            .withUsername("postgres")
            .withPassword("secret");

    private static final AtomicInteger DATABASES = new AtomicInteger();


    @Override
    String newDatabase() throws SQLException {
        String name = "money_" + DATABASES.incrementAndGet();
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), username(), password());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return POSTGRES.getJdbcUrl().replace("/onlineorder", "/" + name);
    }


    @Override
    String username() {
        return POSTGRES.getUsername();
    }


    @Override
    String password() {
        return POSTGRES.getPassword();
    }
}
