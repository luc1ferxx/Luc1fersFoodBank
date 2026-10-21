package com.laioffer.onlineorder;

import com.laioffer.onlineorder.audit.AuditLogService;
import com.laioffer.onlineorder.entity.CartEntity;
import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.repository.CartRepository;
import com.laioffer.onlineorder.repository.CustomerRepository;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import com.laioffer.onlineorder.service.OrderService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OrderStatusTransitionRollbackTests {

    @Container
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
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
    private MeterRegistry meterRegistry;

    @MockitoBean
    private OrderEventOutboxService orderEventOutboxService;

    @MockitoSpyBean
    private AuditLogService auditLogService;

    @Test
    void transitionOrderStatus_whenOutboxStepFails_shouldRollbackUpdatedStatusAndLeaveNoOutboxResidue() {
        SeededOrder seededOrder = createOrderWithHistory("PAID");
        Mockito.doThrow(new IllegalStateException("forced outbox failure during status transition"))
                .when(orderEventOutboxService)
                .enqueueOrderEvent(Mockito.any(), Mockito.anyList(), Mockito.isNull(), Mockito.any());

        IllegalStateException ex = Assertions.assertThrows(
                IllegalStateException.class,
                () -> orderService.transitionOrderStatus(seededOrder.orderId(), "ACCEPTED")
        );

        Assertions.assertTrue(ex.getMessage().contains("forced outbox failure during status transition"));

        OrderEntity unchangedOrder = orderRepository.findById(seededOrder.orderId()).orElseThrow();
        Assertions.assertEquals("PAID", unchangedOrder.status());
        Assertions.assertEquals(unchangedOrder.createdAt(), unchangedOrder.updatedAt());
        Assertions.assertEquals(1, countOrderHistoryItemsForOrder(seededOrder.orderId()));
        Assertions.assertEquals(0, countOutboxEventsForOrder(seededOrder.orderId()));
        // The history row is written before the outbox event and rolls back with it.
        Assertions.assertEquals(0, countRows("order_status_history", "order_id", seededOrder.orderId()));
    }

    @Test
    void idempotentTransitionOrderStatus_whenOutboxStepFails_shouldRollbackUpdatedStatusAndIdempotencyRow() {
        SeededOrder seededOrder = createOrderWithHistory("PAID");
        Mockito.doThrow(new IllegalStateException("forced outbox failure during idempotent status transition"))
                .when(orderEventOutboxService)
                .enqueueOrderEvent(Mockito.any(), Mockito.anyList(), Mockito.isNull(), Mockito.any());

        IllegalStateException ex = Assertions.assertThrows(
                IllegalStateException.class,
                () -> orderService.idempotentTransitionOrderStatus(
                        seededOrder.customerId(),
                        seededOrder.orderId(),
                        "ACCEPTED",
                        "status-rollback-key"
                )
        );

        Assertions.assertTrue(ex.getMessage().contains("forced outbox failure during idempotent status transition"));

        OrderEntity unchangedOrder = orderRepository.findById(seededOrder.orderId()).orElseThrow();
        Assertions.assertEquals("PAID", unchangedOrder.status());
        Assertions.assertEquals(1, countOrderHistoryItemsForOrder(seededOrder.orderId()));
        Assertions.assertEquals(0, countOutboxEventsForOrder(seededOrder.orderId()));
        Assertions.assertEquals(0, countIdempotencyRequests(seededOrder.customerId(), "status-rollback-key"));
        Assertions.assertEquals(0, countRows("order_status_history", "order_id", seededOrder.orderId()));
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE target_type = 'ORDER' AND target_id = ?",
                Long.class,
                String.valueOf(seededOrder.orderId())
        ));
    }

    @Test
    void transitionMetric_shouldNotCountARolledBackTransition() {
        SeededOrder seededOrder = createOrderWithHistory("PAID");
        double before = transitionCount("paid", "accepted");
        // The audit entry is written after the transition itself, so the order row, its history and its event were
        // already written when this fails and the whole transaction rolls back. The spy sits behind the service's
        // transactional proxy, whose MANDATORY propagation would reject a stubbing call made outside a transaction.
        AuditLogService auditSpy = AopTestUtils.getUltimateTargetObject(auditLogService);
        Mockito.doThrow(new IllegalStateException("forced audit failure after the status transition"))
                .doCallRealMethod()
                .when(auditSpy)
                .record(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        IllegalStateException ex = Assertions.assertThrows(
                IllegalStateException.class,
                () -> orderService.idempotentTransitionOrderStatus(
                        seededOrder.customerId(),
                        seededOrder.orderId(),
                        "ACCEPTED",
                        "metric-rollback-key"
                )
        );

        Assertions.assertTrue(ex.getMessage().contains("forced audit failure after the status transition"));
        Assertions.assertEquals("PAID", orderRepository.findById(seededOrder.orderId()).orElseThrow().status());
        Assertions.assertEquals(0, countRows("order_status_history", "order_id", seededOrder.orderId()));
        Assertions.assertEquals(before, transitionCount("paid", "accepted"));

        // The same transition counts once it commits.
        orderService.idempotentTransitionOrderStatus(
                seededOrder.customerId(),
                seededOrder.orderId(),
                "ACCEPTED",
                "metric-commit-key"
        );

        Assertions.assertEquals("ACCEPTED", orderRepository.findById(seededOrder.orderId()).orElseThrow().status());
        Assertions.assertEquals(before + 1, transitionCount("paid", "accepted"));
    }

    private SeededOrder createOrderWithHistory(String status) {
        long suffix = System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        CustomerEntity customer = customerRepository.save(new CustomerEntity(
                null,
                "status-rollback-" + suffix + "@example.com",
                "{noop}demo123",
                true,
                "Status",
                "Rollback",
                "ACTIVE",
                true,
                0,
                null,
                null,
                now,
                now
        ));
        cartRepository.save(new CartEntity(null, customer.id(), BigDecimal.ZERO, "USD"));
        MenuItemEntity menuItem = menuItemRepository.findById(1L).orElseThrow();
        OrderEntity order = orderRepository.save(new OrderEntity(null, customer.id(), menuItem.unitPrice(), status, now));
        orderHistoryItemRepository.save(new OrderHistoryItemEntity(
                null,
                order.id(),
                menuItem.id(),
                menuItem.restaurantId(),
                menuItem.price(),
                1,
                menuItem.name(),
                menuItem.description(),
                menuItem.imageUrl(),
                "USD"
        ));
        return new SeededOrder(customer.id(), order.id());
    }

    private double transitionCount(String from, String to) {
        return meterRegistry.find("onlineorder.order.transition").tag("from", from).tag("to", to).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
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

    private long countRows(String table, String column, Long value) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Long.class,
                value
        );
        return count == null ? 0 : count;
    }

    private long countIdempotencyRequests(Long customerId, String idempotencyKey) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_requests WHERE customer_id = ? AND idempotency_key = ?",
                Long.class,
                customerId,
                idempotencyKey
        );
        return count == null ? 0 : count;
    }

    private record SeededOrder(Long customerId, Long orderId) {
    }
}
