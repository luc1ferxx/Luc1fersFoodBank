package com.laioffer.onlineorder;


import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;


@Testcontainers(disabledWithoutDocker = true)
class DatabaseTimeoutConfigurationTests {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("onlineorder")
            .withUsername("postgres")
            .withPassword("secret");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class));


    @Test
    void everyPooledConnection_shouldCarryTheConfiguredTimeouts() {
        postgresContext().run(context -> {
            HikariDataSource dataSource = context.getBean(HikariDataSource.class);

            // Flyway already ran the schema migrations and the demo seed against the same database.
            Assertions.assertTrue(new JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM restaurants", Integer.class) > 0);

            List<Connection> connections = new ArrayList<>();
            try {
                for (int i = 0; i < 5; i++) {
                    connections.add(dataSource.getConnection());
                }
                for (Connection connection : connections) {
                    Assertions.assertEquals("10s", show(connection, "statement_timeout"));
                    Assertions.assertEquals("3s", show(connection, "lock_timeout"));
                }
            } finally {
                for (Connection connection : connections) {
                    connection.close();
                }
            }
        });
    }


    @Test
    void flywayConnections_shouldNotCarryTheRequestTimeouts() {
        postgresContext().run(context -> {
            DataSource migrationDataSource = context.getBean(Flyway.class).getConfiguration().getDataSource();

            Assertions.assertNotSame(context.getBean(HikariDataSource.class), migrationDataSource);
            // SET LOCAL cannot raise the timeout for a non-transactional migration such as
            // CREATE INDEX CONCURRENTLY, so migrations must not start with the request timeouts.
            try (Connection connection = migrationDataSource.getConnection()) {
                Assertions.assertEquals("0", show(connection, "statement_timeout"));
                Assertions.assertEquals("0", show(connection, "lock_timeout"));
            }
        });
    }


    @Test
    void statementTimeout_shouldCancelLongRunningStatements() {
        postgresContext()
                .withPropertyValues("FLYWAY_ENABLED=false", "DATABASE_STATEMENT_TIMEOUT_MS=200")
                .run(context -> {
                    HikariDataSource dataSource = context.getBean(HikariDataSource.class);

                    try (Connection connection = dataSource.getConnection();
                         Statement statement = connection.createStatement()) {
                        SQLException ex = Assertions.assertThrows(
                                SQLException.class,
                                () -> statement.execute("SELECT pg_sleep(5)")
                        );
                        Assertions.assertEquals("57014", ex.getSQLState());
                    }
                });
    }


    @Test
    void lockTimeout_shouldFailInsteadOfWaitingForALockedTable() {
        postgresContext()
                .withPropertyValues("FLYWAY_ENABLED=false", "DATABASE_LOCK_TIMEOUT_MS=200")
                .run(context -> {
                    HikariDataSource dataSource = context.getBean(HikariDataSource.class);
                    new JdbcTemplate(dataSource).execute("CREATE TABLE IF NOT EXISTS lock_timeout_probe (id INT)");

                    try (Connection lockHolder = dataSource.getConnection();
                         Connection waiter = dataSource.getConnection()) {
                        lockHolder.setAutoCommit(false);
                        try (Statement statement = lockHolder.createStatement()) {
                            statement.execute("LOCK TABLE lock_timeout_probe IN ACCESS EXCLUSIVE MODE");
                        }

                        try (Statement statement = waiter.createStatement()) {
                            SQLException ex = Assertions.assertThrows(
                                    SQLException.class,
                                    () -> statement.executeQuery("SELECT COUNT(*) FROM lock_timeout_probe")
                            );
                            Assertions.assertEquals("55P03", ex.getSQLState());
                        } finally {
                            lockHolder.rollback();
                        }
                    }
                });
    }


    private ApplicationContextRunner postgresContext() {
        // Overriding DATABASE_URL must keep the timeouts, so they are not part of the JDBC URL.
        return contextRunner.withPropertyValues(
                "DATABASE_URL=" + POSTGRES.getJdbcUrl(),
                "DATABASE_USERNAME=" + POSTGRES.getUsername(),
                "DATABASE_PASSWORD=" + POSTGRES.getPassword(),
                "FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/demo"
        );
    }


    private static String show(Connection connection, String setting) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SHOW " + setting)) {
            Assertions.assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
