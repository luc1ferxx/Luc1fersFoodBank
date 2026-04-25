package com.laioffer.onlineorder;

import com.laioffer.onlineorder.entity.CartEntity;
import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.entity.OrderItemEntity;
import com.laioffer.onlineorder.repository.CartRepository;
import com.laioffer.onlineorder.repository.CustomerRepository;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import com.laioffer.onlineorder.repository.OrderItemRepository;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
class CheckoutTransactionRollbackTests {

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
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.session.store-type", () -> "none");
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.outbox.publisher-enabled", () -> "false");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }

    @Autowired
    private CartService cartService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private MenuItemRepository menuItemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private OrderEventOutboxService orderEventOutboxService;

    @Test
    void checkoutWithIdempotency_whenOutboxStepFails_shouldRollbackOrderHistoryOutboxIdempotencyAndCartState() {
        TestCustomer testCustomer = createCustomerWithOneItemInCart();
        Mockito.doThrow(new IllegalStateException("forced outbox failure"))
                .when(orderEventOutboxService)
                .enqueueOrderEvent(Mockito.any(), Mockito.anyList(), Mockito.any());

        IllegalStateException ex = Assertions.assertThrows(
                IllegalStateException.class,
                () -> cartService.checkoutWithIdempotency(testCustomer.customerId(), "PLACED", "rollback-key", "checkout-request")
        );

        Assertions.assertTrue(ex.getMessage().contains("forced outbox failure"));
        Assertions.assertEquals(0, countOrdersForCustomer(testCustomer.customerId()));
        Assertions.assertEquals(0, countOrderHistoryItemsForCustomer(testCustomer.customerId()));
        Assertions.assertEquals(0, countOutboxEventsForCustomer(testCustomer.customerId()));
        Assertions.assertEquals(0, countIdempotencyRequestsForCustomer(testCustomer.customerId(), "rollback-key"));

        Assertions.assertEquals(1, orderItemRepository.getAllByCartId(testCustomer.cartId()).size());
        CartEntity cart = cartRepository.getByCustomerId(testCustomer.customerId());
        Assertions.assertNotNull(cart);
        Assertions.assertEquals(testCustomer.expectedCartTotal(), cart.totalPrice());
    }

    private TestCustomer createCustomerWithOneItemInCart() {
        long suffix = System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        CustomerEntity customer = customerRepository.save(new CustomerEntity(
                null,
                "rollback-" + suffix + "@example.com",
                "{noop}demo123",
                true,
                "Rollback",
                "Test",
                "ACTIVE",
                true,
                0,
                null,
                null,
                now,
                now
        ));
        CartEntity cart = cartRepository.save(new CartEntity(null, customer.id(), 0.0));
        MenuItemEntity menuItem = menuItemRepository.findById(1L).orElseThrow();
        orderItemRepository.save(new OrderItemEntity(null, menuItem.id(), cart.id(), menuItem.price(), 1));
        cartRepository.updateTotalPrice(cart.id(), menuItem.price());
        return new TestCustomer(customer.id(), cart.id(), menuItem.price());
    }

    private long countOrdersForCustomer(Long customerId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE customer_id = ?",
                Long.class,
                customerId
        );
        return count == null ? 0 : count;
    }

    private long countOrderHistoryItemsForCustomer(Long customerId) {
        Long count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM order_history_items ohi
                        JOIN orders o ON o.id = ohi.order_id
                        WHERE o.customer_id = ?
                        """,
                Long.class,
                customerId
        );
        return count == null ? 0 : count;
    }

    private long countOutboxEventsForCustomer(Long customerId) {
        Long count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM outbox_events oe
                        JOIN orders o ON o.id = oe.aggregate_id
                        WHERE oe.aggregate_type = 'ORDER'
                          AND o.customer_id = ?
                        """,
                Long.class,
                customerId
        );
        return count == null ? 0 : count;
    }

    private long countIdempotencyRequestsForCustomer(Long customerId, String idempotencyKey) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_requests WHERE customer_id = ? AND idempotency_key = ?",
                Long.class,
                customerId,
                idempotencyKey
        );
        return count == null ? 0 : count;
    }

    private record TestCustomer(Long customerId, Long cartId, Double expectedCartTotal) {
    }
}
