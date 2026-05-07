package com.laioffer.onlineorder;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DatabaseConstraintIntegrationTests {

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
        registry.add("spring.session.store-type", () -> "none");
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.outbox.publisher-enabled", () -> "false");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;


    @Test
    void ordersStatusConstraint_shouldAcceptAllowedStatusesAndRejectUnsupportedStatus() {
        Long customerId = createCustomer();

        for (String status : new String[]{"PLACED", "PAID", "ACCEPTED", "PREPARING", "COMPLETED", "CANCELLED"}) {
            Assertions.assertDoesNotThrow(() -> insertOrder(customerId, status));
        }
        Assertions.assertThrows(DataIntegrityViolationException.class, () -> insertOrder(customerId, "UNKNOWN"));
    }


    @Test
    void idempotencyStatusConstraint_shouldAcceptAllowedStatusesAndRejectUnsupportedStatus() {
        Long customerId = createCustomer();

        Assertions.assertDoesNotThrow(() -> insertIdempotencyRequest(customerId, "PROCESSING", null));
        Assertions.assertThrows(
                DataIntegrityViolationException.class,
                () -> insertIdempotencyRequest(customerId, "FAILED", null)
        );
    }


    @Test
    void idempotencyOrderForeignKey_shouldAllowNullOrExistingOrderAndRejectMissingOrder() {
        Long customerId = createCustomer();
        Long orderId = insertOrder(customerId, "PLACED");

        Assertions.assertDoesNotThrow(() -> insertIdempotencyRequest(customerId, "SUCCEEDED", null));
        Assertions.assertDoesNotThrow(() -> insertIdempotencyRequest(customerId, "SUCCEEDED", orderId));
        Assertions.assertThrows(
                DataIntegrityViolationException.class,
                () -> insertIdempotencyRequest(customerId, "SUCCEEDED", orderId + 999_999L)
        );
    }


    private Long createCustomer() {
        long suffix = System.nanoTime();
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO customers (
                            email,
                            enabled,
                            password,
                            first_name,
                            last_name
                        ) VALUES (?, TRUE, '{noop}demo123', 'Constraint', 'Test')
                        RETURNING id
                        """,
                Long.class,
                "constraint-" + suffix + "@example.com"
        );
    }


    private Long insertOrder(Long customerId, String status) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO orders (
                            customer_id,
                            total_price,
                            status,
                            created_at
                        ) VALUES (?, 12.34, ?, CURRENT_TIMESTAMP)
                        RETURNING id
                        """,
                Long.class,
                customerId,
                status
        );
    }


    private void insertIdempotencyRequest(Long customerId, String status, Long orderId) {
        long suffix = System.nanoTime();
        jdbcTemplate.update(
                """
                        INSERT INTO idempotency_requests (
                            customer_id,
                            scope,
                            idempotency_key,
                            request_hash,
                            status,
                            order_id,
                            created_at,
                            updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """,
                customerId,
                "constraint-scope-" + suffix,
                "constraint-key-" + suffix,
                "constraint-hash-" + suffix,
                status,
                orderId
        );
    }
}
