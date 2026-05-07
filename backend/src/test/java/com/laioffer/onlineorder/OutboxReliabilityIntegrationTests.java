package com.laioffer.onlineorder;

import com.laioffer.onlineorder.entity.CartEntity;
import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.entity.OutboxEventEntity;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventPayload;
import com.laioffer.onlineorder.messaging.OutboxEventPublisher;
import com.laioffer.onlineorder.model.OrderHistoryItemDto;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.CartRepository;
import com.laioffer.onlineorder.repository.CustomerRepository;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.repository.OutboxEventRepository;
import com.laioffer.onlineorder.service.OrderEventHandlerService;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OutboxReliabilityIntegrationTests {

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
        registry.add("app.outbox.max-attempts", () -> "2");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }

    @Autowired
    private OrderEventOutboxService orderEventOutboxService;

    @Autowired
    private OrderEventHandlerService orderEventHandlerService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ApplicationMetricsService metricsService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private MenuItemRepository menuItemRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderHistoryItemRepository orderHistoryItemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private KafkaTemplate<String, String> kafkaTemplate;
    private OutboxEventPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        kafkaTemplate = Mockito.mock(KafkaTemplate.class);
        publisher = new OutboxEventPublisher(
                kafkaTemplate,
                Duration.ofSeconds(5),
                orderEventOutboxService,
                metricsService
        );
    }

    @Test
    void publishPendingEvents_whenFirstSendFails_shouldKeepEventRetryableAndPublishItOnNextRun() {
        OutboxEventEntity event = createPendingOutboxEvent("retry");
        Mockito.when(kafkaTemplate.send(event.topic(), event.eventKey(), event.payload()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingEvents();

        OutboxEventEntity retryableEvent = outboxEventRepository.findById(event.id()).orElseThrow();
        Assertions.assertEquals("PENDING", retryableEvent.status());
        Assertions.assertEquals(1, retryableEvent.attempts());
        Assertions.assertNotNull(retryableEvent.lastError());
        Assertions.assertTrue(retryableEvent.lastError().contains("Kafka unavailable"));
        Assertions.assertNull(retryableEvent.publishedAt());

        publisher.publishPendingEvents();

        OutboxEventEntity publishedEvent = outboxEventRepository.findById(event.id()).orElseThrow();
        Assertions.assertEquals("PUBLISHED", publishedEvent.status());
        Assertions.assertEquals(2, publishedEvent.attempts());
        Assertions.assertNull(publishedEvent.lastError());
        Assertions.assertNotNull(publishedEvent.publishedAt());
        Mockito.verify(kafkaTemplate, Mockito.times(2)).send(event.topic(), event.eventKey(), event.payload());
    }

    @Test
    void publishPendingEvents_whenAttemptsReachMax_shouldMarkFailedAndStopRetrying() {
        OutboxEventEntity event = createPendingOutboxEvent("max-attempts");
        Mockito.when(kafkaTemplate.send(event.topic(), event.eventKey(), event.payload()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka still unavailable")));

        publisher.publishPendingEvents();

        OutboxEventEntity retryableEvent = outboxEventRepository.findById(event.id()).orElseThrow();
        Assertions.assertEquals("PENDING", retryableEvent.status());
        Assertions.assertEquals(1, retryableEvent.attempts());
        Assertions.assertNotNull(retryableEvent.lastError());
        Assertions.assertTrue(retryableEvent.lastError().contains("Kafka unavailable"));
        Assertions.assertNull(retryableEvent.publishedAt());

        publisher.publishPendingEvents();

        OutboxEventEntity failedEvent = outboxEventRepository.findById(event.id()).orElseThrow();
        Assertions.assertEquals("FAILED", failedEvent.status());
        Assertions.assertEquals(2, failedEvent.attempts());
        Assertions.assertNotNull(failedEvent.lastError());
        Assertions.assertTrue(failedEvent.lastError().contains("Kafka still unavailable"));
        Assertions.assertNull(failedEvent.publishedAt());

        publisher.publishPendingEvents();

        OutboxEventEntity stillFailedEvent = outboxEventRepository.findById(event.id()).orElseThrow();
        Assertions.assertEquals("FAILED", stillFailedEvent.status());
        Assertions.assertEquals(2, stillFailedEvent.attempts());
        Mockito.verify(kafkaTemplate, Mockito.times(2)).send(event.topic(), event.eventKey(), event.payload());
    }

    @Test
    void publishPendingEvents_whenEventAlreadyPublished_shouldNotPublishItAgain() {
        OutboxEventEntity event = createPendingOutboxEvent("single-publish");
        Mockito.when(kafkaTemplate.send(event.topic(), event.eventKey(), event.payload()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingEvents();
        publisher.publishPendingEvents();

        OutboxEventEntity publishedEvent = outboxEventRepository.findById(event.id()).orElseThrow();
        Assertions.assertEquals("PUBLISHED", publishedEvent.status());
        Assertions.assertEquals(1, publishedEvent.attempts());
        Assertions.assertNotNull(publishedEvent.publishedAt());
        Mockito.verify(kafkaTemplate, Mockito.times(1)).send(event.topic(), event.eventKey(), event.payload());
    }

    @Test
    void handleOrderEvent_whenSameEventIsConsumedTwice_shouldRecordProcessedEventAndNotificationOnlyOnce() {
        SeededOrder seededOrder = createOrderWithHistory("PAID");
        String eventId = "evt-dedup-" + System.nanoTime();
        OrderEventEnvelope event = new OrderEventEnvelope(
                eventId,
                1,
                "order.paid",
                "ORDER",
                seededOrder.orderId(),
                seededOrder.createdAt(),
                eventId,
                "idem-dedup",
                new OrderEventPayload(
                        seededOrder.customerId(),
                        "PAID",
                        seededOrder.totalPrice(),
                        seededOrder.createdAt(),
                        List.of(seededOrder.itemDto())
                )
        );

        orderEventHandlerService.handle(event);
        orderEventHandlerService.handle(event);

        Assertions.assertEquals(1, countProcessedEvents(eventId));
        Assertions.assertEquals(1, countOrderNotifications(seededOrder.orderId(), "order.paid"));
    }

    private OutboxEventEntity createPendingOutboxEvent(String label) {
        long suffix = System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        return outboxEventRepository.save(new OutboxEventEntity(
                null,
                "ORDER",
                suffix,
                "evt-" + label + "-" + suffix,
                "order-events",
                String.valueOf(suffix),
                "order.created",
                "{\"event_id\":\"evt-" + label + "-" + suffix + "\"}",
                "PENDING",
                0,
                null,
                now,
                now,
                null
        ));
    }

    private SeededOrder createOrderWithHistory(String status) {
        long suffix = System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        CustomerEntity customer = customerRepository.save(new CustomerEntity(
                null,
                "outbox-dedup-" + suffix + "@example.com",
                "{noop}demo123",
                true,
                "Outbox",
                "Dedup",
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
        OrderHistoryItemEntity historyItem = orderHistoryItemRepository.save(new OrderHistoryItemEntity(
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
        return new SeededOrder(
                customer.id(),
                order.id(),
                menuItem.price(),
                now,
                new OrderHistoryItemDto(historyItem)
        );
    }

    private long countProcessedEvents(String eventId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE consumer_name = 'order-notification-consumer' AND dedup_key = ?",
                Long.class,
                eventId
        );
        return count == null ? 0 : count;
    }

    private long countOrderNotifications(Long orderId, String eventType) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_notifications WHERE order_id = ? AND event_type = ?",
                Long.class,
                orderId,
                eventType
        );
        return count == null ? 0 : count;
    }

    private record SeededOrder(
            Long customerId,
            Long orderId,
            Double totalPrice,
            LocalDateTime createdAt,
            OrderHistoryItemDto itemDto
    ) {
    }
}
