package com.laioffer.onlineorder;


import tools.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.audit.AuditActor;
import com.laioffer.onlineorder.fulfilment.dispatch.DeliveryDto;
import com.laioffer.onlineorder.fulfilment.dispatch.DispatchService;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantInboxService;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantOrderService;
import com.laioffer.onlineorder.fulfilment.order.DeliveryCompletionService;
import com.laioffer.onlineorder.fulfilment.timeout.OrderDeadlineProcessor;
import com.laioffer.onlineorder.fulfilment.timeout.OrderDeadlineService;
import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderStatusHistoryDto;
import com.laioffer.onlineorder.model.UpdateMenuItemBody;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.CustomerService;
import com.laioffer.onlineorder.service.DeadLetterProcessingService;
import com.laioffer.onlineorder.service.MenuItemService;
import com.laioffer.onlineorder.service.OrderEventHandlerService;
import com.laioffer.onlineorder.service.OrderService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;


/**
 * Proves V7 (64-bit keys) on a real database: no id column is 32-bit any more, and an order whose ids, and the ids of
 * everything around it, are past 2^31 goes through checkout, its idempotent retry, notifications, the merchant inbox
 * and accept, dispatch, delivery completion, a payment timeout, the order history, the audit log and the dead-letter
 * store. The cases have to hold on PostgreSQL and on H2 (the {@code h2} profile), so each database gets a subclass
 * that knows how to move its key generators.
 */
abstract class AbstractLargeIdIntegrationTests {

    /**
     * Past the INTEGER maximum (2,147,483,647).
     */
    static final long BEYOND_INT = 3_000_000_000L;

    /**
     * Every table whose id comes from a SERIAL/BIGSERIAL key generator.
     */
    static final List<String> GENERATED_ID_TABLES = List.of(
            "customers", "restaurants", "menu_items", "carts", "order_items", "orders", "order_history_items",
            "authorities", "outbox_events", "processed_events", "order_notifications", "dead_letter_events",
            "idempotency_requests", "merchant_accounts", "merchant_orders", "order_deadlines", "riders", "deliveries",
            "order_status_history", "audit_log"
    );

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderEventHandlerService orderEventHandlerService;

    @Autowired
    private MerchantInboxService merchantInboxService;

    @Autowired
    private MerchantOrderService merchantOrderService;

    @Autowired
    private OrderDeadlineService orderDeadlineService;

    @Autowired
    private OrderDeadlineProcessor orderDeadlineProcessor;

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private DeliveryCompletionService deliveryCompletionService;

    @Autowired
    private MenuItemService menuItemService;

    @Autowired
    private DeadLetterProcessingService deadLetterProcessingService;


    /**
     * Makes the next generated id of {@code table} equal to {@code nextId}.
     */
    abstract void restartIdAt(String table, long nextId);


    /**
     * @return the key generators (sequences or identity columns) that still stop at the INTEGER maximum
     */
    abstract List<String> narrowKeyGenerators();


    /**
     * Whether the order notification consumer runs on this database. Its insert uses ON CONFLICT, which H2 does not
     * parse; it only runs with Kafka, which the h2 profile switches off.
     */
    boolean runsNotificationConsumer() {
        return true;
    }


    @Test
    void schema_shouldHaveNoThirtyTwoBitIdColumnsOrKeyGenerators() {
        List<String> narrowIdColumns = jdbcTemplate.queryForList("""
                SELECT LOWER(table_name) || '.' || LOWER(column_name)
                FROM information_schema.columns
                WHERE table_schema = CURRENT_SCHEMA
                  AND LOWER(table_name) <> 'flyway_schema_history'
                  AND (LOWER(column_name) = 'id' OR LOWER(column_name) LIKE '%\\_id' ESCAPE '\\')
                  AND LOWER(data_type) IN ('integer', 'int', 'int4', 'smallint', 'int2')
                ORDER BY 1
                """, String.class);

        Assertions.assertEquals(List.of(), narrowIdColumns);
        Assertions.assertEquals(List.of(), narrowKeyGenerators());

        // The same catalog query reports the widened columns as BIGINT (so the filter above can match a type).
        for (String column : List.of("orders.id", "orders.customer_id", "deliveries.rider_id", "dispatch_orders.order_id",
                "order_status_history.order_id", "audit_log.id", "riders.customer_id")) {
            String[] parts = column.split("\\.");
            Assertions.assertEquals("bigint", jdbcTemplate.queryForObject("""
                    SELECT LOWER(data_type)
                    FROM information_schema.columns
                    WHERE table_schema = CURRENT_SCHEMA
                      AND LOWER(table_name) = ?
                      AND LOWER(column_name) = ?
                    """, String.class, parts[0], parts[1]), column);
        }
    }


    @Test
    void anOrderBeyondTheIntegerRange_shouldGoThroughCheckoutFulfilmentAndTheAuditTrails() {
        for (String table : GENERATED_ID_TABLES) {
            Long currentMax = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM " + table, Long.class);
            restartIdAt(table, Math.max(BEYOND_INT, currentMax + 1));
        }

        long restaurantId = insertRestaurant("Large Id Diner " + System.nanoTime());
        long menuItemId = insertMenuItem(restaurantId);
        long customerId = signUp("large-id-customer-");
        long merchantCustomerId = signUp("large-id-merchant-");
        jdbcTemplate.update(
                "INSERT INTO authorities (email, authority) SELECT email, 'ROLE_MERCHANT' FROM customers WHERE id = ?",
                merchantCustomerId
        );
        jdbcTemplate.update(
                "INSERT INTO merchant_accounts (customer_id, restaurant_id, created_at) VALUES (?, ?, ?)",
                merchantCustomerId, restaurantId, LocalDateTime.now()
        );
        long riderCustomerId = signUp("large-id-rider-");
        long riderId = insertRider(riderCustomerId);
        assertBeyondInt(restaurantId, menuItemId, customerId, merchantCustomerId, riderCustomerId, riderId,
                idOf("SELECT id FROM carts WHERE customer_id = ?", customerId),
                idOf("SELECT MAX(id) FROM authorities"),
                idOf("SELECT id FROM merchant_accounts WHERE customer_id = ?", merchantCustomerId));

        // Checkout with an idempotency key, and its retry.
        cartService.addMenuItemToCart(customerId, menuItemId);
        assertBeyondInt(idOf("SELECT MAX(id) FROM order_items"));
        OrderDto order = cartService.checkoutWithIdempotency(customerId, "PAID", "large-id-checkout", "large-id-request");
        long orderId = order.id();
        Assertions.assertEquals(orderId, cartService.checkoutWithIdempotency(
                customerId, "PAID", "large-id-checkout", "large-id-request").id());
        Assertions.assertEquals(restaurantId, order.restaurantId());
        assertBeyondInt(orderId,
                idOf("SELECT id FROM order_history_items WHERE order_id = ?", orderId),
                idOf("SELECT id FROM idempotency_requests WHERE order_id = ?", orderId),
                idOf("SELECT MAX(id) FROM outbox_events WHERE aggregate_id = ?", orderId));
        Assertions.assertEquals(
                List.of(orderId),
                orderService.getOrdersByCustomerId(customerId).stream().map(OrderDto::id).toList()
        );

        // The consumers of order.paid: notifications, merchant inbox, deadlines.
        OrderEventEnvelope paid = latestOrderEvent(orderId, "order.paid");
        if (runsNotificationConsumer()) {
            orderEventHandlerService.handle(paid);
            assertBeyondInt(idOf(
                    "SELECT id FROM order_notifications WHERE order_id = ? AND customer_id = ?", orderId, customerId));
        }
        merchantInboxService.handle(paid);
        orderDeadlineService.handle(paid);
        assertBeyondInt(
                idOf("SELECT id FROM merchant_orders WHERE order_id = ? AND restaurant_id = ? AND customer_id = ?",
                        orderId, restaurantId, customerId),
                idOf("SELECT MAX(id) FROM processed_events WHERE aggregate_id = ?", orderId));

        // Merchant accept, dispatch to the rider, pick-up and delivery, completion by the delivery event.
        Assertions.assertEquals("ACCEPTED", merchantOrderService.accept(merchantCustomerId, orderId).status());
        dispatchService.handle(latestOrderEvent(orderId, "order.accepted"));
        DeliveryDto delivery = dispatchService.getDeliveries(orderId, null, 1).get(0);
        Assertions.assertEquals(riderId, delivery.riderId());
        assertBeyondInt(delivery.id(), idOf("SELECT order_id FROM dispatch_orders WHERE order_id = ?", orderId));
        dispatchService.markPickedUp(delivery.id());
        dispatchService.markDelivered(delivery.id());
        deliveryCompletionService.handle(latestDeliveryEvent(delivery.id(), "delivery.delivered"));
        Assertions.assertEquals("COMPLETED", jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId));

        List<OrderStatusHistoryDto> history = orderService.getStatusHistory(orderId, customerId, false);
        Assertions.assertEquals(List.of("PAID", "ACCEPTED", "PREPARING", "COMPLETED"),
                history.stream().map(OrderStatusHistoryDto::toStatus).toList());
        Assertions.assertEquals(List.of(customerId, merchantCustomerId, riderId, riderId),
                history.stream().map(OrderStatusHistoryDto::actorId).toList());
        assertBeyondInt(idOf("SELECT MAX(id) FROM order_status_history WHERE order_id = ?", orderId));

        // A second order, left unpaid until its payment deadline fires.
        cartService.addMenuItemToCart(customerId, menuItemId);
        long placedOrderId = cartService.checkout(customerId, "PLACED").id();
        orderDeadlineService.handle(latestOrderEvent(placedOrderId, "order.created"));
        long deadlineId = idOf("SELECT id FROM order_deadlines WHERE order_id = ?", placedOrderId);
        assertBeyondInt(placedOrderId, deadlineId);
        jdbcTemplate.update("UPDATE order_deadlines SET due_at = ? WHERE id = ?", LocalDateTime.now().minusMinutes(1), deadlineId);
        orderDeadlineProcessor.fireDueDeadlines();
        Assertions.assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, placedOrderId));

        // An audited admin action on a large id, and a stored dead letter.
        menuItemService.updateMenuItem(menuItemId, new UpdateMenuItemBody(null, null, new BigDecimal("12.50")),
                AuditActor.admin(customerId));
        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT id, actor_customer_id FROM audit_log WHERE target_type = 'MENU_ITEM' AND target_id = ?",
                String.valueOf(menuItemId));
        assertBeyondInt(((Number) audit.get("id")).longValue(), ((Number) audit.get("actor_customer_id")).longValue());
        deadLetterProcessingService.handle("order-events", "order-events.dlt", String.valueOf(orderId),
                "{\"event_id\":\"large-id-dead-letter-" + System.nanoTime() + "\"}", "boom");
        assertBeyondInt(idOf("SELECT MAX(id) FROM dead_letter_events"));
    }


    private long insertRestaurant(String name) {
        jdbcTemplate.update("INSERT INTO restaurants (name, address, image_url, phone) VALUES (?, 'Somewhere', NULL, NULL)", name);
        return idOf("SELECT id FROM restaurants WHERE name = ?", name);
    }


    private long insertMenuItem(long restaurantId) {
        jdbcTemplate.update(
                "INSERT INTO menu_items (restaurant_id, name, price, description, image_url) VALUES (?, 'Big Burger', 9.99, 'Large', NULL)",
                restaurantId
        );
        return idOf("SELECT id FROM menu_items WHERE restaurant_id = ?", restaurantId);
    }


    /**
     * A rider that dispatch picks: the only active, on-duty one (V8), so the other riders are switched off for this
     * database. It is bound to a login whose customer id is past the INTEGER range too (riders.customer_id, V8).
     */
    private long insertRider(long customerId) {
        String name = "Large Id Rider " + System.nanoTime();
        jdbcTemplate.update("UPDATE riders SET active = FALSE, on_duty = FALSE");
        jdbcTemplate.update(
                "INSERT INTO riders (name, phone, active, on_duty, active_deliveries, customer_id) "
                        + "VALUES (?, NULL, TRUE, TRUE, 0, ?)",
                name, customerId
        );
        long riderId = idOf("SELECT id FROM riders WHERE name = ?", name);
        Assertions.assertEquals(customerId, idOf("SELECT customer_id FROM riders WHERE id = ?", riderId));
        return riderId;
    }


    private long signUp(String prefix) {
        String email = prefix + System.nanoTime() + "@example.com";
        customerService.signUp(email, "demo123", "Large", "Id");
        return idOf("SELECT id FROM customers WHERE email = ?", email);
    }


    private long idOf(String sql, Object... args) {
        Long id = jdbcTemplate.queryForObject(sql, Long.class, args);
        Assertions.assertNotNull(id, sql);
        return id;
    }


    private static void assertBeyondInt(long... ids) {
        for (long id : ids) {
            Assertions.assertTrue(id > Integer.MAX_VALUE, "id " + id + " should be past the INTEGER range");
        }
    }


    private OrderEventEnvelope latestOrderEvent(long orderId, String eventType) {
        return read(jdbcTemplate.queryForObject("""
                SELECT payload FROM outbox_events
                WHERE aggregate_type = 'ORDER' AND aggregate_id = ? AND event_type = ?
                ORDER BY id DESC LIMIT 1
                """, String.class, orderId, eventType), OrderEventEnvelope.class);
    }


    private DeliveryEventEnvelope latestDeliveryEvent(long deliveryId, String eventType) {
        return read(jdbcTemplate.queryForObject("""
                SELECT payload FROM outbox_events
                WHERE aggregate_type = 'DELIVERY' AND aggregate_id = ? AND event_type = ?
                ORDER BY id DESC LIMIT 1
                """, String.class, deliveryId, eventType), DeliveryEventEnvelope.class);
    }


    private <T> T read(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
