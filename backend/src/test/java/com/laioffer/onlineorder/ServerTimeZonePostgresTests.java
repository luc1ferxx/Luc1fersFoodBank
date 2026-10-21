package com.laioffer.onlineorder;


import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;


import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;


/**
 * The PostgreSQL driver sends the JVM's default zone as the session {@code TimeZone} when it connects, so with the
 * server in UTC ({@link ServerTimeZoneTests}) every session is UTC, and {@code CURRENT_TIMESTAMP} stored into a
 * {@code TIMESTAMP} column (the column defaults, the login lockout SQL, the demo seed) is the same UTC wall-clock time
 * as the application's {@code LocalDateTime.now()}.
 */
@Testcontainers(disabledWithoutDocker = true)
class ServerTimeZonePostgresTests {

    @Container
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("onlineorder")
            .withUsername("postgres")
            .withPassword("secret");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class));


    @Test
    void pooledAndMigrationConnections_shouldRunTheirSessionsInUtc() {
        postgresContext().run(context -> {
            DataSource pool = context.getBean(HikariDataSource.class);
            DataSource migrations = context.getBean(Flyway.class).getConfiguration().getDataSource();

            for (DataSource dataSource : new DataSource[] {pool, migrations}) {
                try (Connection connection = dataSource.getConnection()) {
                    Assertions.assertEquals("UTC", show(connection, "TimeZone"));
                }
            }
        });
    }


    @Test
    void timestampsTheDatabaseTakes_shouldBeUtcWallClockTimeLikeTheApplications() {
        postgresContext().run(context -> {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(HikariDataSource.class));

            assertUtcNow(jdbcTemplate.queryForObject("SELECT CAST(CURRENT_TIMESTAMP AS TIMESTAMP)", LocalDateTime.class));
            assertUtcNow(jdbcTemplate.queryForObject("SELECT LOCALTIMESTAMP", LocalDateTime.class));
            // Written with CURRENT_TIMESTAMP by the demo seed, which Flyway ran on its own connections at start-up.
            assertUtcNow(jdbcTemplate.queryForObject("SELECT MAX(created_at) FROM merchant_accounts", LocalDateTime.class));
        });
    }


    private ApplicationContextRunner postgresContext() {
        return contextRunner.withPropertyValues(
                "DATABASE_URL=" + POSTGRES.getJdbcUrl(),
                "DATABASE_USERNAME=" + POSTGRES.getUsername(),
                "DATABASE_PASSWORD=" + POSTGRES.getPassword(),
                "FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/demo"
        );
    }


    private static void assertUtcNow(LocalDateTime databaseTime) {
        Assertions.assertNotNull(databaseTime);
        // Seconds apart at most (the container's clock, the context start-up); a session in another zone is off by
        // that zone's offset, which is at least 30 minutes for every zone that is not at UTC's.
        Duration difference = Duration.between(databaseTime, LocalDateTime.now(ZoneOffset.UTC)).abs();
        Assertions.assertTrue(difference.compareTo(Duration.ofMinutes(5)) < 0,
                "The database time " + databaseTime + " is " + difference + " away from UTC now");
    }


    private static String show(Connection connection, String setting) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SHOW " + setting)) {
            Assertions.assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
