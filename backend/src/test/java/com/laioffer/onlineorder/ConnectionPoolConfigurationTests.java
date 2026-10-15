package com.laioffer.onlineorder;


import com.laioffer.onlineorder.error.ApiErrorResponseFactory;
import com.laioffer.onlineorder.error.ApiExceptionHandler;
import com.zaxxer.hikari.HikariConfig;
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
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;


import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;


class ConnectionPoolConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class));


    @Test
    void defaultProfile_shouldUseAFixedSizePoolThatFailsFast() {
        contextRunner.run(context -> {
            HikariDataSource dataSource = context.getBean(HikariDataSource.class);

            Assertions.assertEquals("onlineorder-db", dataSource.getPoolName());
            Assertions.assertEquals(30, dataSource.getMaximumPoolSize());
            Assertions.assertEquals(2000L, dataSource.getConnectionTimeout());
            Assertions.assertTrue(dataSource.getValidationTimeout() < dataSource.getConnectionTimeout());
            Assertions.assertEquals(
                    "SET statement_timeout = 10000; SET lock_timeout = 3000",
                    dataSource.getConnectionInitSql()
            );
            Assertions.assertFalse(dataSource.getJdbcUrl().contains("timeout"));
        });
    }


    @Test
    void environmentOverrides_shouldReplaceThePoolDefaults() {
        contextRunner
                .withPropertyValues(
                        "DATABASE_POOL_MAX_SIZE=7",
                        "DATABASE_POOL_CONNECTION_TIMEOUT_MS=750",
                        "DATABASE_STATEMENT_TIMEOUT_MS=1500",
                        "DATABASE_LOCK_TIMEOUT_MS=500"
                )
                .run(context -> {
                    HikariDataSource dataSource = context.getBean(HikariDataSource.class);

                    Assertions.assertEquals(7, dataSource.getMaximumPoolSize());
                    Assertions.assertEquals(750L, dataSource.getConnectionTimeout());
                    Assertions.assertEquals(
                            "SET statement_timeout = 1500; SET lock_timeout = 500",
                            dataSource.getConnectionInitSql()
                    );
                });
    }


    @Test
    void h2Profile_shouldApplyH2TimeoutsToEveryPooledConnection() {
        contextRunner
                .withPropertyValues("spring.profiles.active=h2")
                .run(context -> {
                    HikariDataSource dataSource = context.getBean(HikariDataSource.class);
                    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

                    Assertions.assertEquals(
                            "SET QUERY_TIMEOUT 10000; SET LOCK_TIMEOUT 3000",
                            dataSource.getConnectionInitSql()
                    );
                    Assertions.assertEquals(
                            "10000",
                            jdbcTemplate.queryForObject(
                                    "SELECT setting_value FROM information_schema.settings WHERE setting_name = 'QUERY_TIMEOUT'",
                                    String.class
                            )
                    );
                    Assertions.assertEquals(3000, jdbcTemplate.queryForObject("SELECT LOCK_TIMEOUT()", Integer.class));
                    // minimum-idle is left unset, so HikariCP keeps a fixed-size pool.
                    Assertions.assertEquals(dataSource.getMaximumPoolSize(), dataSource.getMinimumIdle());
                });
    }


    @Test
    void flyway_shouldMigrateOverItsOwnConnectionsWithoutTheRequestTimeouts() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
                .withPropertyValues("spring.profiles.active=h2")
                .run(context -> {
                    HikariDataSource pool = context.getBean(HikariDataSource.class);
                    DataSource migrationDataSource = context.getBean(Flyway.class).getConfiguration().getDataSource();

                    Assertions.assertNotSame(pool, migrationDataSource);
                    Assertions.assertFalse(migrationDataSource instanceof HikariDataSource);
                    // Same database: the schema and demo seed Flyway applied are visible through the pool.
                    Assertions.assertTrue(new JdbcTemplate(pool).queryForObject("SELECT COUNT(*) FROM restaurants", Integer.class) > 0);
                    Assertions.assertEquals("10000", queryTimeout(new JdbcTemplate(pool)));
                    // A slow migration, including a non-transactional one, is not cancelled by the request timeout.
                    Assertions.assertEquals("0", queryTimeout(new JdbcTemplate(migrationDataSource)));
                });
    }


    @Test
    void exhaustedPool_shouldAnswer503WithTheApiErrorShape() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:pool-exhaustion-" + UUID.randomUUID());
        config.setUsername("sa");
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(250);

        try (HikariDataSource dataSource = new HikariDataSource(config);
             Connection ignored = dataSource.getConnection()) {
            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PoolProbeController(dataSource))
                    .setControllerAdvice(new ApiExceptionHandler(new ApiErrorResponseFactory()))
                    .build();

            for (String path : new String[]{"/probe/jdbc", "/probe/transaction"}) {
                mockMvc.perform(MockMvcRequestBuilders.get(path))
                        .andExpect(MockMvcResultMatchers.status().isServiceUnavailable())
                        .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                        .andExpect(MockMvcResultMatchers.jsonPath("$.status").value(503))
                        .andExpect(MockMvcResultMatchers.jsonPath("$.path").value(path));
            }
        }
    }


    private static String queryTimeout(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForObject(
                "SELECT setting_value FROM information_schema.settings WHERE setting_name = 'QUERY_TIMEOUT'",
                String.class
        );
    }


    @RestController
    static class PoolProbeController {

        private final JdbcTemplate jdbcTemplate;
        private final TransactionTemplate transactionTemplate;


        PoolProbeController(DataSource dataSource) {
            this.jdbcTemplate = new JdbcTemplate(dataSource);
            this.transactionTemplate = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        }


        @GetMapping("/probe/jdbc")
        Integer jdbc() {
            return jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        }


        @GetMapping("/probe/transaction")
        Integer transaction() {
            return transactionTemplate.execute(status -> 1);
        }
    }
}
