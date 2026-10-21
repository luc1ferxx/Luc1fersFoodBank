package com.laioffer.onlineorder;


import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;


/**
 * The after-sales schema on H2 (the {@code h2} profile), which runs the same migrations, V12_1's H2 currency check
 * included.
 */
@SpringBootTest
@ActiveProfiles("h2")
class AfterSalesSchemaH2Tests extends AbstractAfterSalesSchemaTests {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.session.store-type", () -> "none");
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.outbox.publisher-enabled", () -> "false");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }


    @Override
    boolean parsesOnConflictTarget() {
        return false;
    }
}
