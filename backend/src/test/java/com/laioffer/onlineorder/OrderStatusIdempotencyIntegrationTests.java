package com.laioffer.onlineorder;

import com.laioffer.onlineorder.entity.CartEntity;
import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.model.OrderDto;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OrderStatusIdempotencyIntegrationTests {

    private static final String STATUS_SCOPE_PREFIX = "order-status:v1:";
    private static final String CANCEL_SCOPE_PREFIX = "order-cancel:v1:";

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

    @Test
    void idempotentTransitionOrderStatus_whenSameKeyAndSameTargetIsRetried_shouldReturnOrderWithoutDuplicateSideEffects() {
        SeededOrder seededOrder = createOrderWithHistory("PAID");

        OrderDto first = orderService.idempotentTransitionOrderStatus(
                seededOrder.customerId(),
                seededOrder.orderId(),
                "ACCEPTED",
                " status-key-1 "
        );
        OrderDto retry = orderService.idempotentTransitionOrderStatus(
                seededOrder.customerId(),
                seededOrder.orderId(),
                "ACCEPTED",
                "status-key-1"
        );

        Assertions.assertEquals("ACCEPTED", first.status());
        Assertions.assertEquals("ACCEPTED", retry.status());
        Assertions.assertEquals(seededOrder.orderId(), retry.id());
        Assertions.assertEquals(1, countIdempotencyRequests(seededOrder.customerId(), STATUS_SCOPE_PREFIX + seededOrder.orderId(), "status-key-1"));
        Assertions.assertEquals(1, countOutboxEventsForOrder(seededOrder.orderId()));
        Assertions.assertEquals("SUCCEEDED", idempotencyStatus(seededOrder.customerId(), STATUS_SCOPE_PREFIX + seededOrder.orderId(), "status-key-1"));
    }

    @Test
    void idempotentTransitionOrderStatus_whenSameKeyIsCalledConcurrently_shouldReuseOneTransition() throws Exception {
        SeededOrder seededOrder = createOrderWithHistory("PAID");
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        try {
            Future<OrderDto> firstAttempt = executorService.submit(() -> coordinatedStatusTransition(
                    seededOrder.customerId(),
                    seededOrder.orderId(),
                    "status-key-concurrent",
                    readyLatch,
                    startLatch
            ));
            Future<OrderDto> secondAttempt = executorService.submit(() -> coordinatedStatusTransition(
                    seededOrder.customerId(),
                    seededOrder.orderId(),
                    "status-key-concurrent",
                    readyLatch,
                    startLatch
            ));

            Assertions.assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
            startLatch.countDown();

            OrderDto first = firstAttempt.get(10, TimeUnit.SECONDS);
            OrderDto second = secondAttempt.get(10, TimeUnit.SECONDS);

            Assertions.assertEquals("ACCEPTED", first.status());
            Assertions.assertEquals("ACCEPTED", second.status());
            Assertions.assertEquals(seededOrder.orderId(), first.id());
            Assertions.assertEquals(first.id(), second.id());
            Assertions.assertEquals(1, countIdempotencyRequests(seededOrder.customerId(), STATUS_SCOPE_PREFIX + seededOrder.orderId(), "status-key-concurrent"));
            Assertions.assertEquals(1, countOutboxEventsForOrder(seededOrder.orderId()));
            Assertions.assertEquals(1, countOutboxEventsForOrderAndType(seededOrder.orderId(), "order.accepted"));
            Assertions.assertEquals("ACCEPTED", orderRepository.findById(seededOrder.orderId()).orElseThrow().status());
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void idempotentCancelOrder_whenSameKeyIsRetried_shouldReturnCancelledOrderWithoutDuplicateSideEffects() {
        SeededOrder seededOrder = createOrderWithHistory("PAID");

        OrderDto first = orderService.idempotentCancelOrder(seededOrder.customerId(), seededOrder.orderId(), "cancel-key-1");
        OrderDto retry = orderService.idempotentCancelOrder(seededOrder.customerId(), seededOrder.orderId(), "cancel-key-1");

        Assertions.assertEquals("CANCELLED", first.status());
        Assertions.assertEquals("CANCELLED", retry.status());
        Assertions.assertEquals(seededOrder.orderId(), retry.id());
        Assertions.assertEquals(1, countIdempotencyRequests(seededOrder.customerId(), CANCEL_SCOPE_PREFIX + seededOrder.orderId(), "cancel-key-1"));
        Assertions.assertEquals(1, countOutboxEventsForOrder(seededOrder.orderId()));
    }

    @Test
    void idempotentTransitionOrderStatus_whenSameKeyIsUsedWithDifferentTarget_shouldRejectWithoutAdditionalSideEffects() {
        SeededOrder seededOrder = createOrderWithHistory("PAID");
        orderService.idempotentTransitionOrderStatus(seededOrder.customerId(), seededOrder.orderId(), "ACCEPTED", "status-key-2");

        Assertions.assertThrows(
                ConflictException.class,
                () -> orderService.idempotentTransitionOrderStatus(seededOrder.customerId(), seededOrder.orderId(), "PREPARING", "status-key-2")
        );

        OrderEntity order = orderRepository.findById(seededOrder.orderId()).orElseThrow();
        Assertions.assertEquals("ACCEPTED", order.status());
        Assertions.assertEquals(1, countOutboxEventsForOrder(seededOrder.orderId()));
    }

    @Test
    void idempotentTransitionOrderStatus_whenTargetStatusCaseDiffers_shouldTreatRetryAsSameRequest() {
        SeededOrder seededOrder = createOrderWithHistory("PAID");

        orderService.idempotentTransitionOrderStatus(seededOrder.customerId(), seededOrder.orderId(), "accepted", "status-key-3");
        OrderDto retry = orderService.idempotentTransitionOrderStatus(seededOrder.customerId(), seededOrder.orderId(), "ACCEPTED", "status-key-3");

        Assertions.assertEquals("ACCEPTED", retry.status());
        Assertions.assertEquals(1, countIdempotencyRequests(seededOrder.customerId(), STATUS_SCOPE_PREFIX + seededOrder.orderId(), "status-key-3"));
        Assertions.assertEquals(1, countOutboxEventsForOrder(seededOrder.orderId()));
    }

    @Test
    void idempotentTransitionOrderStatus_whenSuccessfulRequestIsRetriedAfterLaterProgress_shouldReturnCurrentOrder() {
        SeededOrder seededOrder = createOrderWithHistory("PAID");

        orderService.idempotentTransitionOrderStatus(seededOrder.customerId(), seededOrder.orderId(), "ACCEPTED", "status-key-4a");
        orderService.idempotentTransitionOrderStatus(seededOrder.customerId(), seededOrder.orderId(), "PREPARING", "status-key-4b");
        OrderDto retry = orderService.idempotentTransitionOrderStatus(seededOrder.customerId(), seededOrder.orderId(), "ACCEPTED", "status-key-4a");

        Assertions.assertEquals("PREPARING", retry.status());
        Assertions.assertEquals(2, countOutboxEventsForOrder(seededOrder.orderId()));
    }

    @Test
    void idempotentTransitionOrderStatus_whenMatchingRequestIsStillProcessing_shouldRejectAsConflict() {
        SeededOrder seededOrder = createOrderWithHistory("PAID");
        String scope = STATUS_SCOPE_PREFIX + seededOrder.orderId();
        String key = "status-key-processing";
        insertIdempotencyRequest(
                seededOrder.customerId(),
                scope,
                key,
                statusRequestHash(seededOrder.customerId(), seededOrder.orderId(), "ACCEPTED"),
                "PROCESSING",
                null
        );

        Assertions.assertThrows(
                ConflictException.class,
                () -> orderService.idempotentTransitionOrderStatus(seededOrder.customerId(), seededOrder.orderId(), "ACCEPTED", key)
        );

        OrderEntity order = orderRepository.findById(seededOrder.orderId()).orElseThrow();
        Assertions.assertEquals("PAID", order.status());
        Assertions.assertEquals(0, countOutboxEventsForOrder(seededOrder.orderId()));
    }

    @Test
    void idempotentTransitionOrderStatus_whenKeyIsMissingBlankOrTooLong_shouldRejectAsBadRequest() {
        SeededOrder seededOrder = createOrderWithHistory("PAID");
        String tooLongKey = "x".repeat(256);

        Assertions.assertThrows(
                BadRequestException.class,
                () -> orderService.idempotentTransitionOrderStatus(seededOrder.customerId(), seededOrder.orderId(), "ACCEPTED", null)
        );
        Assertions.assertThrows(
                BadRequestException.class,
                () -> orderService.idempotentTransitionOrderStatus(seededOrder.customerId(), seededOrder.orderId(), "ACCEPTED", "   ")
        );
        Assertions.assertThrows(
                BadRequestException.class,
                () -> orderService.idempotentTransitionOrderStatus(seededOrder.customerId(), seededOrder.orderId(), "ACCEPTED", tooLongKey)
        );
        Assertions.assertEquals(0, countIdempotencyRequestsForCustomer(seededOrder.customerId()));
    }

    @Test
    void idempotentCancelOrder_whenActorDoesNotOwnOrder_shouldKeepExistingAuthorizationSemanticsWithoutSuccessfulIdempotencyRow() {
        SeededOrder seededOrder = createOrderWithHistory("PAID");
        CustomerEntity otherActor = createCustomer("cancel-other");

        Assertions.assertThrows(
                ResourceNotFoundException.class,
                () -> orderService.idempotentCancelOrder(otherActor.id(), seededOrder.orderId(), "cancel-key-unauthorized")
        );

        OrderEntity order = orderRepository.findById(seededOrder.orderId()).orElseThrow();
        Assertions.assertEquals("PAID", order.status());
        Assertions.assertEquals(0, countOutboxEventsForOrder(seededOrder.orderId()));
        Assertions.assertEquals(0, countIdempotencyRequests(otherActor.id(), CANCEL_SCOPE_PREFIX + seededOrder.orderId(), "cancel-key-unauthorized"));
    }

    private SeededOrder createOrderWithHistory(String status) {
        LocalDateTime now = LocalDateTime.now();
        CustomerEntity customer = createCustomer("status-idem");
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

    private CustomerEntity createCustomer(String prefix) {
        long suffix = System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        return customerRepository.save(new CustomerEntity(
                null,
                prefix + "-" + suffix + "@example.com",
                "{noop}demo123",
                true,
                "Status",
                "Idempotency",
                "ACTIVE",
                true,
                0,
                null,
                null,
                now,
                now
        ));
    }

    private OrderDto coordinatedStatusTransition(
            Long customerId,
            Long orderId,
            String idempotencyKey,
            CountDownLatch readyLatch,
            CountDownLatch startLatch
    ) throws Exception {
        readyLatch.countDown();
        Assertions.assertTrue(startLatch.await(5, TimeUnit.SECONDS));
        return orderService.idempotentTransitionOrderStatus(customerId, orderId, "ACCEPTED", idempotencyKey);
    }

    private long countOutboxEventsForOrder(Long orderId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'ORDER' AND aggregate_id = ?",
                Long.class,
                orderId
        );
        return count == null ? 0 : count;
    }

    private long countOutboxEventsForOrderAndType(Long orderId, String eventType) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'ORDER' AND aggregate_id = ? AND event_type = ?",
                Long.class,
                orderId,
                eventType
        );
        return count == null ? 0 : count;
    }

    private long countIdempotencyRequests(Long customerId, String scope, String key) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_requests WHERE customer_id = ? AND scope = ? AND idempotency_key = ?",
                Long.class,
                customerId,
                scope,
                key
        );
        return count == null ? 0 : count;
    }

    private long countIdempotencyRequestsForCustomer(Long customerId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_requests WHERE customer_id = ?",
                Long.class,
                customerId
        );
        return count == null ? 0 : count;
    }

    private String idempotencyStatus(Long customerId, String scope, String key) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM idempotency_requests WHERE customer_id = ? AND scope = ? AND idempotency_key = ?",
                String.class,
                customerId,
                scope,
                key
        );
    }

    private void insertIdempotencyRequest(Long customerId, String scope, String key, String requestHash, String status, Long orderId) {
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
                scope,
                key,
                requestHash,
                status,
                orderId
        );
    }

    private String statusRequestHash(Long actorCustomerId, Long orderId, String targetStatus) {
        return requestHash(
                "ORDER_STATUS_TRANSITION",
                "PATCH",
                "/orders/{orderId}/status",
                orderId,
                actorCustomerId,
                targetStatus
        );
    }

    private String requestHash(String operation, String method, String routeTemplate, Long orderId, Long actorCustomerId, String targetStatus) {
        String canonical = "operation=" + operation + "\n"
                + "method=" + method + "\n"
                + "route=" + routeTemplate + "\n"
                + "orderId=" + orderId + "\n"
                + "actorCustomerId=" + actorCustomerId + "\n"
                + "targetStatus=" + targetStatus.toUpperCase();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private record SeededOrder(Long customerId, Long orderId) {
    }
}
