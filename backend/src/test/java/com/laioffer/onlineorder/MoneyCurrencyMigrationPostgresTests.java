package com.laioffer.onlineorder;


import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;


import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * The money migration on PostgreSQL; every test migrates its own database of the container. On PostgreSQL V11 also
 * takes all its table locks up front, so a lock it cannot get fails it before it has changed anything.
 */
@Testcontainers(disabledWithoutDocker = true)
class MoneyCurrencyMigrationPostgresTests extends AbstractMoneyCurrencyMigrationTests {

    @Container
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("onlineorder")
            .withUsername("postgres")
            .withPassword("secret");

    private static final AtomicInteger DATABASES = new AtomicInteger();


    @Test
    void v11_whenAnotherTransactionHoldsOneOfItsTables_shouldGiveUpBeforeChangingAnything() throws SQLException {
        String url = newDatabase();
        String before11 = versionBefore11(url);
        flyway(url, null).target(before11).load().migrate();

        try (Connection blocker = DriverManager.getConnection(url, username(), password())) {
            // A reader of the last table V11 locks, holding its lock as an open report or a pod's transaction would.
            blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement()) {
                statement.executeQuery("SELECT COUNT(*) FROM merchant_orders").close();
            }

            FlywayException failure = Assertions.assertThrows(FlywayException.class,
                    () -> flyway(url, "SET lock_timeout = '1s'").load().migrate());

            StringBuilder messages = new StringBuilder();
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                messages.append(cause.getMessage()).append('\n');
            }
            Assertions.assertTrue(messages.toString().contains("lock timeout"), messages.toString());
            // V11, the next migration, is the one that gave up: it is still pending.
            Assertions.assertEquals(before11, flyway(url, null).load().info().current().getVersion().getVersion());
            try (Connection connection = DriverManager.getConnection(url, username(), password());
                 Statement statement = connection.createStatement()) {
                // The tables V11 had already locked are free again, and nothing was changed.
                statement.execute("SET lock_timeout = '1s'");
                statement.execute("INSERT INTO restaurants (name) VALUES ('Still Open')");
                try (ResultSet result = statement.executeQuery("""
                        SELECT COUNT(*) FROM information_schema.columns
                        WHERE column_name = 'currency' AND table_schema = CURRENT_SCHEMA
                        """)) {
                    result.next();
                    Assertions.assertEquals(0, result.getInt(1));
                }
            }
            blocker.rollback();
        }

        // Without the other transaction it completes.
        flyway(url, "SET lock_timeout = '1s'").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, username(), password());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT currency FROM restaurants WHERE name = 'Still Open'")) {
            Assertions.assertTrue(result.next());
            Assertions.assertEquals("USD", result.getString(1));
        }
    }


    private FluentConfiguration flyway(String url, String initSql) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(url, username(), password())
                .locations("classpath:db/migration");
        return initSql == null ? configuration : configuration.initSql(initSql);
    }


    /**
     * @return the latest migration version before 11, so that this test also holds once other releases add some
     */
    private String versionBefore11(String url) {
        MigrationVersion v11 = MigrationVersion.fromVersion("11");
        return Arrays.stream(flyway(url, null).load().info().all())
                .map(MigrationInfo::getVersion)
                .filter(version -> version != null && version.compareTo(v11) < 0)
                .max(MigrationVersion::compareTo)
                .orElseThrow()
                .getVersion();
    }


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
