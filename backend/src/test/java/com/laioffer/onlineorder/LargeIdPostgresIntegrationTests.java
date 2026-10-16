package com.laioffer.onlineorder;


import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


import java.util.List;


@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class LargeIdPostgresIntegrationTests extends AbstractLargeIdIntegrationTests {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("onlineorder")
            .withUsername("postgres")
            .withPassword("secret");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("spring.session.store-type", () -> "none");
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.outbox.publisher-enabled", () -> "false");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }


    @Override
    void restartIdAt(String table, long nextId) {
        // is_called = false: the next nextval() returns nextId itself.
        jdbcTemplate.queryForObject("SELECT setval(pg_get_serial_sequence(?, 'id'), ?, false)", Long.class, table, nextId);
    }


    @Override
    List<String> narrowKeyGenerators() {
        return jdbcTemplate.queryForList("""
                SELECT sequencename
                FROM pg_sequences
                WHERE schemaname = current_schema()
                  AND data_type <> 'bigint'::regtype
                ORDER BY sequencename
                """, String.class);
    }
}
