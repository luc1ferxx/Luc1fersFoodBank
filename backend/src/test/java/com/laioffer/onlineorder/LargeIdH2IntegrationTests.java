package com.laioffer.onlineorder;


import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;


import java.util.List;


@SpringBootTest
@ActiveProfiles("h2")
class LargeIdH2IntegrationTests extends AbstractLargeIdIntegrationTests {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }


    @Override
    void restartIdAt(String table, long nextId) {
        jdbcTemplate.execute("ALTER TABLE " + table + " ALTER COLUMN id RESTART WITH " + nextId);
    }


    @Override
    boolean runsNotificationConsumer() {
        return false;
    }


    @Override
    List<String> narrowKeyGenerators() {
        return jdbcTemplate.queryForList("""
                SELECT LOWER(table_name)
                FROM information_schema.columns
                WHERE table_schema = CURRENT_SCHEMA
                  AND is_identity = 'YES'
                  AND (identity_maximum IS NULL OR identity_maximum < 9223372036854775807)
                ORDER BY 1
                """, String.class);
    }
}
