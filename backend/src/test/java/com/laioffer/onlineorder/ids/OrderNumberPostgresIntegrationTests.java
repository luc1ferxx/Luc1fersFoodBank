package com.laioffer.onlineorder.ids;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventItem;
import com.laioffer.onlineorder.messaging.OrderEventPayload;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderNotificationDto;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.OrderEventHandlerService;
import com.laioffer.onlineorder.service.OrderNotificationService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;


import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;


/**
 * Public order numbers on PostgreSQL with the Kubernetes setting app.ids.worker-id-source=lease: checkout takes the
 * number from the leased worker id, the APIs write it as a string, and the notifications name the order by it.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OrderNumberPostgresIntegrationTests {

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
        registry.add("app.partitions.initial-delay-ms", () -> "3600000");
        registry.add("app.security.rate-limit.enabled", () -> "false");
        registry.add("app.ids.worker-id-source", () -> "lease");
    }

    @Autowired
    private DistributedIdGenerator idGenerator;

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderEventHandlerService orderEventHandlerService;

    @Autowired
    private OrderNotificationService orderNotificationService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    @Test
    void leaseMode_shouldHoldAWorkerIdInTheDatabase() {
        Assertions.assertInstanceOf(WorkerIdLeaseManager.class, idGenerator);
        WorkerIdLeaseManager manager = (WorkerIdLeaseManager) idGenerator;
        Assertions.assertTrue(manager.workerId() >= 0);
        Assertions.assertEquals(List.of(manager.owner()), jdbcTemplate.queryForList("""
                SELECT owner FROM snowflake_worker_leases
                WHERE worker_id = ? AND lease_until > CURRENT_TIMESTAMP
                """, String.class, manager.workerId()));
    }


    @Test
    void checkout_shouldGiveTheOrderAPublicNumberFromTheLeasedWorker() throws Exception {
        Long customerId = customerId();
        long before = System.currentTimeMillis();
        cartService.addMenuItemToCart(customerId, 1L);

        OrderDto order = cartService.checkout(customerId);

        Assertions.assertNotNull(order.orderNo());
        Assertions.assertNotEquals(order.id(), order.orderNo());
        Assertions.assertEquals(idGenerator.workerId(), SnowflakeIdGenerator.workerIdOf(order.orderNo()));
        long issuedAt = SnowflakeIdGenerator.timestampMillisOf(order.orderNo());
        Assertions.assertTrue(issuedAt >= before && issuedAt <= System.currentTimeMillis());
        Assertions.assertEquals(order.orderNo(), orderRepository.findOrderNoById(order.id()).orElseThrow());

        // Written as a string: JavaScript numbers lose digits past 2^53.
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(order));
        Assertions.assertTrue(json.get("order_no").isString(), json.toString());
        Assertions.assertEquals(String.valueOf(order.orderNo()), json.get("order_no").asString());
        Assertions.assertTrue(json.get("id").isNumber());
        // A status change keeps the number.
        OrderEntity reloaded = orderRepository.findById(order.id()).orElseThrow();
        orderRepository.save(reloaded.withStatus("PAID", null, LocalDateTime.now()));
        Assertions.assertEquals(order.orderNo(), orderRepository.findOrderNoById(order.id()).orElseThrow());
    }


    @Test
    void notifications_shouldNameTheOrderByItsPublicNumber() throws Exception {
        Long customerId = customerId();
        cartService.addMenuItemToCart(customerId, 1L);
        OrderDto order = cartService.checkout(customerId);

        List<OrderEventItem> items = order.items().stream()
                .map(item -> new OrderEventItem(
                        item.id(),
                        item.menuItemId(),
                        item.restaurantId(),
                        item.price(),
                        null,
                        item.quantity(),
                        item.menuItemName(),
                        item.menuItemDescription(),
                        item.menuItemImageUrl()
                ))
                .toList();
        orderEventHandlerService.handle(new OrderEventEnvelope(
                UUID.randomUUID().toString(), 1, "order.created", "ORDER", order.id(), null, LocalDateTime.now(),
                "corr", null,
                new OrderEventPayload(customerId, "PLACED", order.totalPrice(), null, order.createdAt(), items, null, null)
        ));

        OrderNotificationDto notification = orderNotificationService.getNotificationsByCustomerId(customerId).stream()
                .filter(candidate -> order.id().equals(candidate.orderId()))
                .findFirst()
                .orElseThrow();
        Assertions.assertEquals("Order #" + order.orderNo() + " was placed and sent for processing.",
                notification.message());
        Assertions.assertEquals(order.orderNo(), notification.orderNo());
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(notification));
        Assertions.assertEquals(String.valueOf(order.orderNo()), json.get("order_no").asString());
    }


    @Test
    void orderNumbers_fromConcurrentInsertsAreUnique() throws Exception {
        Long customerId = customerId();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Long>> numbers = new java.util.ArrayList<>();
            for (int i = 0; i < 200; i++) {
                numbers.add(executor.submit(() -> orderRepository.save(new OrderEntity(
                        null, customerId, com.laioffer.onlineorder.money.Money.of("1.00", "USD"), "PLACED", LocalDateTime.now())).orderNo()));
            }
            Set<Long> unique = new HashSet<>();
            for (Future<Long> number : numbers) {
                unique.add(number.get());
            }
            Assertions.assertEquals(200, unique.size());
        } finally {
            executor.shutdownNow();
        }
    }


    private Long customerId() {
        return jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = 'demo@laifood.com'", Long.class);
    }
}
