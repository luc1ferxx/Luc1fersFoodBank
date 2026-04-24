package com.laioffer.onlineorder;

import com.laioffer.onlineorder.entity.CartEntity;
import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.repository.CartRepository;
import com.laioffer.onlineorder.repository.CustomerRepository;
import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.entity.OrderItemEntity;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import com.laioffer.onlineorder.repository.OrderItemRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.service.CartService;
import org.junit.jupiter.api.AfterEach;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class CartConcurrencyTests {

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
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private MenuItemRepository menuItemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ExecutorService executorService = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    @Test
    void checkoutWithSameIdempotencyKey_whenCalledConcurrently_shouldReuseOneOrder() throws Exception {
        TestCustomer testCustomer = createCustomerWithOneItemInCart();
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<OrderDto> firstAttempt = executorService.submit(() -> coordinatedCheckout(testCustomer.customerId(), "shared-key", readyLatch, startLatch));
        Future<OrderDto> secondAttempt = executorService.submit(() -> coordinatedCheckout(testCustomer.customerId(), "shared-key", readyLatch, startLatch));

        Assertions.assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
        startLatch.countDown();

        OrderDto firstOrder = firstAttempt.get(5, TimeUnit.SECONDS);
        OrderDto secondOrder = secondAttempt.get(5, TimeUnit.SECONDS);

        Assertions.assertEquals(firstOrder.id(), secondOrder.id());
        Assertions.assertEquals(1, orderRepository.findAllByCustomerIdOrderByCreatedAtDesc(testCustomer.customerId()).size());
        Assertions.assertEquals(1, countIdempotencyRequests(testCustomer.customerId(), "shared-key"));
        Assertions.assertTrue(orderItemRepository.getAllByCartId(testCustomer.cartId()).isEmpty());
    }

    @Test
    void checkoutWithDifferentIdempotencyKeys_whenCalledConcurrently_shouldOnlyCreateOneOrder() throws Exception {
        TestCustomer testCustomer = createCustomerWithOneItemInCart();
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<OrderDto> firstAttempt = executorService.submit(() -> coordinatedCheckout(testCustomer.customerId(), "key-a", readyLatch, startLatch));
        Future<OrderDto> secondAttempt = executorService.submit(() -> coordinatedCheckout(testCustomer.customerId(), "key-b", readyLatch, startLatch));

        Assertions.assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
        startLatch.countDown();

        List<Future<OrderDto>> attempts = List.of(firstAttempt, secondAttempt);
        int successCount = 0;
        int badRequestCount = 0;
        Long successfulOrderId = null;
        for (Future<OrderDto> attempt : attempts) {
            try {
                OrderDto order = attempt.get(5, TimeUnit.SECONDS);
                successCount++;
                successfulOrderId = order.id();
            } catch (ExecutionException ex) {
                Assertions.assertInstanceOf(BadRequestException.class, ex.getCause());
                badRequestCount++;
            }
        }

        Assertions.assertEquals(1, successCount);
        Assertions.assertEquals(1, badRequestCount);
        Assertions.assertNotNull(successfulOrderId);
        Assertions.assertEquals(1, orderRepository.findAllByCustomerIdOrderByCreatedAtDesc(testCustomer.customerId()).size());
        Assertions.assertEquals(1, countTotalIdempotencyRequests(testCustomer.customerId()));
        Assertions.assertTrue(orderItemRepository.getAllByCartId(testCustomer.cartId()).isEmpty());
    }

    private OrderDto coordinatedCheckout(
            Long customerId,
            String idempotencyKey,
            CountDownLatch readyLatch,
            CountDownLatch startLatch
    ) throws Exception {
        readyLatch.countDown();
        Assertions.assertTrue(startLatch.await(5, TimeUnit.SECONDS));
        return cartService.checkoutWithIdempotency(customerId, "PLACED", idempotencyKey, "checkout-request");
    }

    private TestCustomer createCustomerWithOneItemInCart() {
        long suffix = System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        CustomerEntity customer = customerRepository.save(new CustomerEntity(
                null,
                "concurrency-" + suffix + "@example.com",
                "{noop}demo123",
                true,
                "Concurrency",
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
        return new TestCustomer(customer.id(), cart.id());
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

    private long countTotalIdempotencyRequests(Long customerId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_requests WHERE customer_id = ?",
                Long.class,
                customerId
        );
        return count == null ? 0 : count;
    }

    private record TestCustomer(Long customerId, Long cartId) {
    }
}
