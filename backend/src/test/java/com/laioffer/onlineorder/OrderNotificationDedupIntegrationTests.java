package com.laioffer.onlineorder;


import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventPayload;
import com.laioffer.onlineorder.repository.CustomerRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.service.OrderNotificationService;
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


import java.time.LocalDateTime;
import java.util.List;


@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OrderNotificationDedupIntegrationTests {

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
    private OrderNotificationService orderNotificationService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    @Test
    void recordOrderEvent_whenSameNotificationIsRecordedTwice_shouldNotThrowAndShouldInsertOnce() {
        SeededOrder seededOrder = createOrder("PAID");
        OrderEventEnvelope event = buildEvent("evt-duplicate-" + System.nanoTime(), seededOrder.orderId(), seededOrder.customerId(), "order.paid", "PAID");

        Assertions.assertDoesNotThrow(() -> orderNotificationService.recordOrderEvent(event));
        Assertions.assertDoesNotThrow(() -> orderNotificationService.recordOrderEvent(event));

        Assertions.assertEquals(1, countNotifications(seededOrder.orderId(), "order.paid"));
    }


    @Test
    void recordOrderEvent_whenForeignKeyConstraintFails_shouldPropagateIntegrityViolation() {
        SeededOrder seededOrder = createOrder("PAID");
        OrderEventEnvelope event = buildEvent(
                "evt-bad-fk-" + System.nanoTime(),
                seededOrder.orderId() + 999_999L,
                seededOrder.customerId(),
                "order.paid",
                "PAID"
        );

        Assertions.assertThrows(DataIntegrityViolationException.class, () -> orderNotificationService.recordOrderEvent(event));
    }


    private SeededOrder createOrder(String status) {
        long suffix = System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        CustomerEntity customer = customerRepository.save(new CustomerEntity(
                null,
                "notification-dedup-" + suffix + "@example.com",
                "{noop}demo123",
                true,
                "Notification",
                "Dedup",
                "ACTIVE",
                true,
                0,
                null,
                null,
                now,
                now
        ));
        OrderEntity order = orderRepository.save(new OrderEntity(null, customer.id(), 22.0, status, now));
        return new SeededOrder(customer.id(), order.id());
    }


    private OrderEventEnvelope buildEvent(String eventId, Long orderId, Long customerId, String eventType, String status) {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 4, 4, 12, 0);
        return new OrderEventEnvelope(
                eventId,
                1,
                eventType,
                "ORDER",
                orderId,
                occurredAt,
                eventId,
                "idem-" + eventId,
                new OrderEventPayload(customerId, status, 22.0, occurredAt, List.of())
        );
    }


    private long countNotifications(Long orderId, String eventType) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_notifications WHERE order_id = ? AND event_type = ?",
                Long.class,
                orderId,
                eventType
        );
        return count == null ? 0 : count;
    }


    private record SeededOrder(Long customerId, Long orderId) {
    }
}
