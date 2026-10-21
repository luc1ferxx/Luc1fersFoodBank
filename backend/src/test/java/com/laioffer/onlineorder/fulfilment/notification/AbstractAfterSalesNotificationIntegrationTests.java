package com.laioffer.onlineorder.fulfilment.notification;


import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.fulfilment.FulfilmentEvents;
import com.laioffer.onlineorder.messaging.AfterSalesCaseLinePayload;
import com.laioffer.onlineorder.messaging.AfterSalesCasePayload;
import com.laioffer.onlineorder.messaging.AfterSalesEventEnvelope;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.repository.CustomerRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;


/**
 * The after-sales notifications through the real repository (ADR 0002 section 20, dedup_scope): an order's two requests
 * notify in their own scopes ('case:' || case id) next to the order's own notifications (empty scope), a redelivered
 * event notifies once, also when its processed_events mark is gone, and the automatic refund of a cancelled order only
 * notifies its completion. The insert has to parse and behave the same on PostgreSQL and on H2 in PostgreSQL mode (the
 * {@code h2} profile), so the cases live here and each database gets a subclass.
 */
abstract class AbstractAfterSalesNotificationIntegrationTests {

    private static final LocalDateTime OPENED_AT = LocalDateTime.of(2026, 4, 25, 13, 10, 5);

    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 4, 26, 13, 10, 5);

    @Autowired
    private AfterSalesNotificationService afterSalesNotificationService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    @Test
    void secondRequestOfAnOrder_shouldNotifyInItsOwnScopeNextToTheOrdersNotifications() {
        Order order = order("COMPLETED");
        jdbcTemplate.update("""
                INSERT INTO order_notifications (order_id, customer_id, event_type, title, message, created_at)
                VALUES (?, ?, 'order.completed', 'Order completed', 'Order completed.', ?)
                """, order.id(), order.customerId(), OPENED_AT);
        long firstCase = caseId();
        long secondCase = caseId();

        afterSalesNotificationService.handle(event(order, firstCase, FulfilmentEvents.AFTER_SALES_CASE_OPENED,
                "CUSTOMER_REQUEST", "PENDING_MERCHANT", "OPEN", null));
        afterSalesNotificationService.handle(event(order, firstCase, FulfilmentEvents.AFTER_SALES_CASE_CLOSED,
                "CUSTOMER_REQUEST", "CLOSED", "CUSTOMER_WITHDRAW", "WITHDRAWN"));
        afterSalesNotificationService.handle(event(order, secondCase, FulfilmentEvents.AFTER_SALES_CASE_OPENED,
                "CUSTOMER_REQUEST", "PENDING_MERCHANT", "OPEN", null));

        Assertions.assertEquals(List.of(
                "order.completed||Order completed",
                FulfilmentEvents.AFTER_SALES_CASE_OPENED + "|case:" + firstCase + "|Refund requested",
                FulfilmentEvents.AFTER_SALES_CASE_CLOSED + "|case:" + firstCase + "|Refund request closed",
                FulfilmentEvents.AFTER_SALES_CASE_OPENED + "|case:" + secondCase + "|Refund requested"
        ), notifications(order));
        Assertions.assertEquals(
                "Your refund request of 12.50 USD for order #" + order.orderNo() + " was sent to the restaurant. "
                        + "If it does not answer by 2026-04-26 13:10, the request is approved automatically.",
                message(order, FulfilmentEvents.AFTER_SALES_CASE_OPENED, secondCase));
    }


    /**
     * A redelivered event is caught by its processed_events mark. Once the mark is gone (retention, a replay much
     * later under the event id), the unique (order_id, event_type, dedup_scope) still keeps one notification, and the
     * insert reports the duplicate as 0 rows instead of failing.
     */
    @Test
    void redeliveredEvent_shouldNotifyOnce_evenWithoutItsProcessedMark() {
        Order order = order("COMPLETED");
        long caseId = caseId();
        AfterSalesEventEnvelope opened = event(order, caseId, FulfilmentEvents.AFTER_SALES_CASE_OPENED,
                "CUSTOMER_REQUEST", "PENDING_MERCHANT", "OPEN", null);

        afterSalesNotificationService.handle(opened);
        afterSalesNotificationService.handle(opened);
        Assertions.assertEquals(1, marks(opened.eventId()));
        jdbcTemplate.update("DELETE FROM processed_events WHERE dedup_key = ?", opened.eventId());
        afterSalesNotificationService.handle(opened);

        Assertions.assertEquals(List.of(FulfilmentEvents.AFTER_SALES_CASE_OPENED + "|case:" + caseId + "|Refund requested"),
                notifications(order));
        Assertions.assertEquals(1, marks(opened.eventId()));
    }


    @Test
    void orderCancelledCase_shouldNotifyOnlyItsRefund() {
        Order order = order("CANCELLED");
        long caseId = caseId();

        afterSalesNotificationService.handle(event(order, caseId, FulfilmentEvents.AFTER_SALES_CASE_OPENED,
                "ORDER_CANCELLED", "REFUNDING", "ORDER_CANCELLED", null));
        Assertions.assertEquals(List.of(), notifications(order));
        afterSalesNotificationService.handle(event(order, caseId, FulfilmentEvents.AFTER_SALES_CASE_REFUNDED,
                "ORDER_CANCELLED", "REFUNDED", "REFUND_SUCCEEDED", null));

        Assertions.assertEquals(List.of(FulfilmentEvents.AFTER_SALES_CASE_REFUNDED + "|case:" + caseId + "|Refund completed"),
                notifications(order));
        Assertions.assertEquals("Your payment of 12.50 USD for cancelled order #" + order.orderNo() + " was refunded.",
                message(order, FulfilmentEvents.AFTER_SALES_CASE_REFUNDED, caseId));
    }


    /**
     * A notification that cannot be stored fails the event, and its processed_events mark rolls back with it, so the
     * retry notifies.
     */
    @Test
    void notificationOfAMissingOrder_shouldFailTheEventWithoutKeepingItsMark() {
        Order order = order("COMPLETED");
        Order missing = new Order(order.id() + 999_999L, order.orderNo(), order.customerId());
        AfterSalesEventEnvelope opened = event(missing, caseId(), FulfilmentEvents.AFTER_SALES_CASE_OPENED,
                "CUSTOMER_REQUEST", "PENDING_MERCHANT", "OPEN", null);

        Assertions.assertThrows(DataIntegrityViolationException.class,
                () -> afterSalesNotificationService.handle(opened));

        Assertions.assertEquals(0, marks(opened.eventId()));
    }


    private Order order(String status) {
        long suffix = System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        CustomerEntity customer = customerRepository.save(new CustomerEntity(
                null,
                "after-sales-notification-" + suffix + "@example.com",
                "{noop}demo123",
                true,
                "After-Sales",
                "Notification",
                "ACTIVE",
                true,
                0,
                null,
                null,
                now,
                now
        ));
        OrderEntity saved = orderRepository.save(
                new OrderEntity(null, customer.id(), Money.of("12.50", "USD"), status, now));
        Long orderNo = orderRepository.findOrderNoById(saved.id()).orElseThrow();
        return new Order(saved.id(), orderNo, customer.id());
    }


    /**
     * Case ids only name the dedup scope here: no case row is needed, as the notifications reference only the order.
     */
    private static long caseId() {
        return Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L) + 1;
    }


    private static AfterSalesEventEnvelope event(
            Order order,
            long caseId,
            String eventType,
            String kind,
            String status,
            String action,
            String closeReason
    ) {
        BigDecimal amount = new BigDecimal("12.50");
        boolean refunded = "REFUNDED".equals(status);
        AfterSalesCasePayload data = new AfterSalesCasePayload(
                caseId,
                order.id(),
                order.orderNo(),
                order.customerId(),
                1L,
                kind,
                1,
                status,
                action,
                "CUSTOMER",
                order.customerId(),
                1L,
                "MISSING_ITEM",
                null,
                amount,
                null,
                "REFUNDING".equals(status) || refunded ? amount : null,
                refunded ? amount : null,
                "USD",
                null,
                null,
                null,
                closeReason,
                "PENDING_MERCHANT".equals(status) ? DEADLINE : null,
                List.of(new AfterSalesCaseLinePayload(41L, "Whopper Meal", 1, amount, amount, "USD")),
                OPENED_AT
        );
        String eventId = UUID.randomUUID().toString();
        return new AfterSalesEventEnvelope(
                eventId, 1, eventType, "AFTER_SALES_CASE", caseId, 1L, OPENED_AT, eventId, null, data);
    }


    private List<String> notifications(Order order) {
        return jdbcTemplate.query(
                "SELECT event_type, dedup_scope, title FROM order_notifications WHERE order_id = ? ORDER BY id",
                (row, index) -> row.getString("event_type") + "|" + row.getString("dedup_scope") + "|"
                        + row.getString("title"),
                order.id()
        );
    }


    private String message(Order order, String eventType, long caseId) {
        return jdbcTemplate.queryForObject(
                "SELECT message FROM order_notifications WHERE order_id = ? AND event_type = ? AND dedup_scope = ?",
                String.class,
                order.id(),
                eventType,
                "case:" + caseId
        );
    }


    private int marks(String eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE consumer_name = ? AND dedup_key = ?",
                Integer.class,
                AfterSalesNotificationService.AFTER_SALES_NOTIFICATION_CONSUMER,
                eventId
        );
        return count == null ? 0 : count;
    }


    private record Order(Long id, Long orderNo, Long customerId) {
    }
}
