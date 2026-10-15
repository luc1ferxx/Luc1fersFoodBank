package com.laioffer.onlineorder;


import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;


class OutboxUnpublishedIndexMigrationH2Tests {

    @Test
    void migration_shouldRecordVersion3WithoutCreatingThePartialIndexOnH2() throws SQLException {
        String url = "jdbc:h2:mem:outbox-index-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection keepAlive = DriverManager.getConnection(url, "sa", "")) {
            Flyway flyway = Flyway.configure()
                    .dataSource(url, "sa", "")
                    .locations("classpath:db/migration")
                    .load();
            flyway.migrate();

            Assertions.assertEquals("3", flyway.info().current().getVersion().getVersion());
            try (Statement statement = keepAlive.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT COUNT(*) FROM information_schema.indexes WHERE LOWER(index_name) = 'idx_outbox_events_unpublished_id'")) {
                Assertions.assertTrue(resultSet.next());
                Assertions.assertEquals(0, resultSet.getInt(1), "H2 has no partial indexes, so none must be created");
            }
        }
    }
}
