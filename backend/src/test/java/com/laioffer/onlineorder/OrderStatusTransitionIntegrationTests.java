package com.laioffer.onlineorder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.entity.CartEntity;
import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.repository.CartRepository;
import com.laioffer.onlineorder.repository.CustomerRepository;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.service.OrderService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OrderStatusTransitionIntegrationTests {

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

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderHistoryItemRepository orderHistoryItemRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private MenuItemRepository menuItemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void transitionOrderStatus_whenSuccessful_shouldCommitUpdatedOrderAndOutboxEventTogether() throws Exception {
        SeededOrder seededOrder = createPaidOrderWithHistory();

        orderService.transitionOrderStatus(seededOrder.orderId(), "ACCEPTED");

        OrderEntity updatedOrder = orderRepository.findById(seededOrder.orderId()).orElseThrow();
        Assertions.assertEquals("ACCEPTED", updatedOrder.status());

        Assertions.assertEquals(1, countOrderHistoryItemsForOrder(seededOrder.orderId()));
        String payload = latestOutboxPayloadForOrder(seededOrder.orderId());
        Assertions.assertNotNull(payload);

        OrderEventEnvelope envelope = objectMapper.readValue(payload, OrderEventEnvelope.class);
        Assertions.assertEquals("order.accepted", envelope.eventType());
        Assertions.assertEquals(seededOrder.orderId(), envelope.aggregateId());
        Assertions.assertEquals("ACCEPTED", envelope.data().status());
        Assertions.assertEquals(1, envelope.data().items().size());
    }

    @Test
    void transitionOrderStatus_whenInvalid_shouldRejectStateChangeWithoutOutboxSideEffects() {
        SeededOrder seededOrder = createCompletedOrderWithHistory();

        Assertions.assertThrows(
                ConflictException.class,
                () -> orderService.transitionOrderStatus(seededOrder.orderId(), "CANCELLED")
        );

        OrderEntity unchangedOrder = orderRepository.findById(seededOrder.orderId()).orElseThrow();
        Assertions.assertEquals("COMPLETED", unchangedOrder.status());
        Assertions.assertEquals(1, countOrderHistoryItemsForOrder(seededOrder.orderId()));
        Assertions.assertEquals(0, countOutboxEventsForOrder(seededOrder.orderId()));
    }

    private SeededOrder createPaidOrderWithHistory() {
        return createOrderWithHistory("PAID");
    }

    private SeededOrder createCompletedOrderWithHistory() {
        return createOrderWithHistory("COMPLETED");
    }

    private SeededOrder createOrderWithHistory(String status) {
        long suffix = System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        CustomerEntity customer = customerRepository.save(new CustomerEntity(
                null,
                "status-" + suffix + "@example.com",
                "{noop}demo123",
                true,
                "Status",
                "Test",
                "ACTIVE",
                true,
                0,
                null,
                null,
                now,
                now
        ));
        cartRepository.save(new CartEntity(null, customer.id(), 0.0));
        MenuItemEntity menuItem = menuItemRepository.findById(1L).orElseThrow();
        OrderEntity order = orderRepository.save(new OrderEntity(null, customer.id(), menuItem.price(), status, now));
        orderHistoryItemRepository.save(new OrderHistoryItemEntity(
                null,
                order.id(),
                menuItem.id(),
                menuItem.restaurantId(),
                menuItem.price(),
                1,
                menuItem.name(),
                menuItem.description(),
                menuItem.imageUrl()
        ));
        return new SeededOrder(customer.id(), order.id());
    }

    private long countOrderHistoryItemsForOrder(Long orderId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_history_items WHERE order_id = ?",
                Long.class,
                orderId
        );
        return count == null ? 0 : count;
    }

    private long countOutboxEventsForOrder(Long orderId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'ORDER' AND aggregate_id = ?",
                Long.class,
                orderId
        );
        return count == null ? 0 : count;
    }

    private String latestOutboxPayloadForOrder(Long orderId) {
        return jdbcTemplate.query(
                "SELECT payload FROM outbox_events WHERE aggregate_type = 'ORDER' AND aggregate_id = ? ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null,
                orderId
        );
    }

    private record SeededOrder(Long customerId, Long orderId) {
    }
}
