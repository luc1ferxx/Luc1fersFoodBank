package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.messaging.AfterSalesEventEnvelope;
import com.laioffer.onlineorder.messaging.contract.EventContracts;
import com.laioffer.onlineorder.messaging.contract.EventTopic;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.CustomerService;
import com.laioffer.onlineorder.service.OrderService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * The automatic refund of a cancelled paid order (ADR 0002 D5, D12, D16, sections 9 and 19.7) on a real database: the
 * cancel of a PAID or ACCEPTED order with a captured payment and a total above 0.00 opens, in its own transaction, an
 * ORDER_CANCELLED case in REFUNDING for the whole order with its lines, history row, refund and event; a cancel of
 * any other order opens nothing and goes through as before. The cases have to hold on PostgreSQL and on H2 (the
 * {@code h2} profile), so each database gets a subclass; the PostgreSQL one adds every other cancel path, the race and
 * the rollback.
 */
abstract class AbstractCancellationRefundIntegrationTests {

    static final String BURGER_KING = "Burger King";

    static final String AFTER_SALES_TOPIC = "after-sales-events";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    CustomerService customerService;

    @Autowired
    CartService cartService;

    @Autowired
    OrderService orderService;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    AfterSalesCaseRepository caseRepository;

    @Autowired
    RefundRepository refundRepository;

    @Autowired
    EventContracts eventContracts;

    @Autowired
    PlatformTransactionManager transactionManager;


    @Test
    void customerCancel_ofAPaidOrder_shouldOpenTheRefundOfTheWholeOrder() throws Exception {
        Customer alice = customer("cancel-refund-paid-");
        OrderDto paid = paidOrder(alice, menuItem(BURGER_KING, 0), menuItem(BURGER_KING, 0), menuItem(BURGER_KING, 1));

        cancel(alice, paid.id(), "cancel-" + paid.id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancel_reason").value("CUSTOMER_CANCELLED"));

        AfterSalesCaseEntity opened = assertRefundOpened(paid.id());
        // The customer sees the automatic refund among their cases, with its refund.
        JsonNode detail = json(mockMvc.perform(get("/after-sales/cases/{id}", opened.id()).session(alice.session()))
                .andExpect(status().isOk()));
        Assertions.assertEquals("ORDER_CANCELLED", detail.get("kind").asString());
        Assertions.assertEquals("REFUNDING", detail.get("status").asString());
        Assertions.assertEquals("ORDER_CANCELLED", detail.get("resolution").asString());
        Assertions.assertEquals(Money.of(paid.totalPrice(), paid.currency()),
                Money.of(detail.get("approved_amount").decimalValue(), detail.get("currency").asString()));
        Assertions.assertEquals("PENDING", detail.get("refund").get("status").asString());
        Assertions.assertEquals(2, detail.get("lines").size());

        // A retry with the same key answers with the cancelled order and opens nothing more.
        cancel(alice, paid.id(), "cancel-" + paid.id()).andExpect(status().isOk());
        assertRefundOpened(paid.id());
    }


    /**
     * PLACED -> CANCELLED: the order was never paid, so neither the customer's cancel nor an admin's opens anything.
     */
    @Test
    void cancel_ofAPlacedOrder_shouldOpenNothing() throws Exception {
        Customer bob = customer("cancel-refund-placed-");
        OrderDto byCustomer = placedOrder(bob, menuItem(BURGER_KING, 0));
        cancel(bob, byCustomer.id(), "cancel-" + byCustomer.id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        OrderDto byAdmin = placedOrder(bob, menuItem(BURGER_KING, 1));
        orderService.idempotentTransitionOrderStatus(adminId(), byAdmin.id(), "CANCELLED", "admin-" + byAdmin.id());

        assertNothingOpened(byCustomer.id());
        assertNothingOpened(byAdmin.id());
    }


    /**
     * ADR 0002 D12 and D16: an order an admin marked PAID has no captured payment, and a 0.00 order nothing to refund;
     * both cancel as before without a case.
     */
    @Test
    void cancel_ofAPaidOrderWithoutACaptureOrOfZero_shouldOpenNothing() throws Exception {
        Customer carol = customer("cancel-refund-nothing-");
        OrderDto markedPaid = placedOrder(carol, menuItem(BURGER_KING, 0));
        orderService.transitionOrderStatus(markedPaid.id(), "PAID");
        OrderDto free = paidOrder(carol, freeMenuItem(BURGER_KING));
        Assertions.assertEquals(Money.of("0.00", "USD"), Money.of(free.totalPrice(), free.currency()));
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_payments WHERE order_id = ?", Integer.class, free.id()));

        cancel(carol, markedPaid.id(), "cancel-" + markedPaid.id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        cancel(carol, free.id(), "cancel-" + free.id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertNothingOpened(markedPaid.id());
        assertNothingOpened(free.id());
    }


    /**
     * D5: COMPLETED and CANCELLED exclude each other, so an order never has both an ORDER_CANCELLED case and a
     * customer's request. A cancelled order cannot get a request, and an order with a request cannot be cancelled.
     */
    @Test
    void anOrderCancelledCase_andACustomerRequest_shouldNeverMeetOnOneOrder() throws Exception {
        Customer dave = customer("cancel-refund-exclusive-");
        OrderDto cancelled = paidOrder(dave, menuItem(BURGER_KING, 0));
        cancel(dave, cancelled.id(), "cancel-" + cancelled.id()).andExpect(status().isOk());
        AfterSalesCaseEntity refund = assertRefundOpened(cancelled.id());

        openRequest(dave, cancelled.id(), "request-" + cancelled.id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.message")
                        .value("Only COMPLETED orders can get after-sales; this order is CANCELLED"));
        Assertions.assertEquals(List.of(refund.id()), caseIds(cancelled.id()));

        OrderDto completed = paidOrder(dave, menuItem(BURGER_KING, 1));
        for (String status : List.of("ACCEPTED", "PREPARING", "COMPLETED")) {
            orderService.transitionOrderStatus(completed.id(), status);
        }
        long requestId = json(openRequest(dave, completed.id(), "request-" + completed.id())
                .andExpect(status().isOk())).get("id").asLong();
        cancel(dave, completed.id(), "cancel-" + completed.id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Invalid order status transition: COMPLETED -> CANCELLED"));
        Assertions.assertThrows(ConflictException.class,
                () -> orderService.idempotentTransitionOrderStatus(
                        adminId(), completed.id(), "CANCELLED", "admin-" + completed.id()));
        Assertions.assertEquals(List.of(requestId), caseIds(completed.id()));
        Assertions.assertEquals("CUSTOMER_REQUEST", caseRepository.findById(requestId).orElseThrow().kind());
        Assertions.assertEquals(0, refunds(completed.id()));
    }


    // ---- assertions ----


    /**
     * The order is CANCELLED with exactly one case, an ORDER_CANCELLED one in REFUNDING for the order total in the
     * order's currency, opened by SYSTEM at the time of the cancel with every order line in full, one PENDING refund
     * of that amount due at once against the order's captured payment, and one aftersales.case_opened event that
     * passes its contract and follows the order's own order.cancelled.
     */
    AfterSalesCaseEntity assertRefundOpened(Long orderId) {
        OrderEntity order = orderRepository.findById(orderId).orElseThrow();
        Assertions.assertEquals("CANCELLED", order.status());
        Money total = order.total();
        LocalDateTime cancelledAt = order.updatedAt();
        List<AfterSalesCaseEntity> cases = caseRepository.findAllByOrderId(orderId);
        Assertions.assertEquals(1, cases.size(), () -> "cases of order " + orderId + ": " + cases);
        AfterSalesCaseEntity opened = cases.get(0);
        Assertions.assertEquals("ORDER_CANCELLED", opened.kind());
        Assertions.assertEquals(1, opened.attemptNo());
        Assertions.assertEquals("REFUNDING", opened.status());
        Assertions.assertEquals(order.customerId(), opened.customerId());
        Assertions.assertEquals(order.restaurantId(), opened.restaurantId());
        Assertions.assertEquals(order.currency(), opened.currency());
        Assertions.assertEquals(total, Money.of(opened.orderTotal(), opened.currency()));
        Assertions.assertEquals(total, Money.of(opened.requestedAmount(), opened.currency()));
        Assertions.assertEquals(total, Money.of(opened.approvedAmount(), opened.currency()));
        Assertions.assertNull(opened.offeredAmount());
        Assertions.assertNull(opened.refundedAmount());
        Assertions.assertEquals("ORDER_CANCELLED", opened.resolution());
        Assertions.assertNull(opened.reasonCode());
        Assertions.assertNull(opened.description());
        Assertions.assertNull(opened.merchantDecision());
        Assertions.assertNull(opened.closeReason());
        Assertions.assertNull(opened.deadlineAt());
        Assertions.assertEquals(0L, opened.version());
        Assertions.assertEquals(cancelledAt, opened.createdAt(), "opened at the time of the cancel");

        // Every order line, in full.
        List<String> orderLines = jdbcTemplate.query("""
                SELECT id, menu_item_name, quantity, price, currency
                FROM order_history_items
                WHERE order_id = ?
                ORDER BY id
                """, (row, index) -> line(row.getLong("id"), row.getString("menu_item_name"), row.getInt("quantity"),
                Money.of(row.getBigDecimal("price"), row.getString("currency"))), orderId);
        List<String> caseLines = jdbcTemplate.query("""
                SELECT order_item_id, menu_item_name, quantity, unit_price, amount, currency
                FROM after_sales_case_lines
                WHERE case_id = ?
                ORDER BY order_item_id
                """, (row, index) -> {
            Money unitPrice = Money.of(row.getBigDecimal("unit_price"), row.getString("currency"));
            Assertions.assertEquals(unitPrice.times(row.getInt("quantity")),
                    Money.of(row.getBigDecimal("amount"), row.getString("currency")));
            return line(row.getLong("order_item_id"), row.getString("menu_item_name"), row.getInt("quantity"),
                    unitPrice);
        }, opened.id());
        Assertions.assertFalse(orderLines.isEmpty());
        Assertions.assertEquals(orderLines, caseLines);

        Map<String, Object> history = jdbcTemplate.queryForMap("""
                SELECT from_status, to_status, action, actor_type, actor_id, amount, currency, occurred_at
                FROM after_sales_case_history
                WHERE case_id = ?
                """, opened.id());
        Assertions.assertNull(history.get("from_status"));
        Assertions.assertEquals("REFUNDING", history.get("to_status"));
        Assertions.assertEquals("ORDER_CANCELLED", history.get("action"));
        Assertions.assertEquals("SYSTEM", history.get("actor_type"));
        Assertions.assertNull(history.get("actor_id"));
        Assertions.assertEquals(total, Money.of((BigDecimal) history.get("amount"), String.valueOf(history.get("currency"))));
        Assertions.assertEquals(cancelledAt, jdbcTemplate.queryForObject(
                "SELECT occurred_at FROM after_sales_case_history WHERE case_id = ?", LocalDateTime.class, opened.id()));

        List<RefundEntity> refunds = refundRepository.findAllByCaseIdIn(List.of(opened.id()));
        Assertions.assertEquals(1, refunds.size());
        RefundEntity refund = refunds.get(0);
        Assertions.assertEquals(orderId, refund.orderId());
        Assertions.assertEquals(order.customerId(), refund.customerId());
        Assertions.assertEquals("after-sales-case:" + opened.id(), refund.gatewayKey());
        Assertions.assertEquals(jdbcTemplate.queryForObject(
                "SELECT provider_reference FROM order_payments WHERE order_id = ?", String.class, orderId),
                refund.paymentReference());
        Assertions.assertEquals(total, Money.of(refund.amount(), refund.currency()));
        Assertions.assertEquals("PENDING", refund.status());
        Assertions.assertEquals(0, refund.attempts());
        Assertions.assertEquals(cancelledAt, refund.nextAttemptAt(), "due at once");
        Assertions.assertNull(refund.claimedUntil());
        Assertions.assertNull(refund.providerReference());
        Assertions.assertNull(refund.succeededAt());

        List<Map<String, Object>> events = jdbcTemplate.queryForList("""
                SELECT id, topic, event_key, event_type, aggregate_sequence, payload
                FROM outbox_events
                WHERE aggregate_type = 'AFTER_SALES_CASE'
                  AND aggregate_id = ?
                """, opened.id());
        Assertions.assertEquals(1, events.size());
        Map<String, Object> row = events.get(0);
        Assertions.assertEquals(AFTER_SALES_TOPIC, row.get("topic"));
        Assertions.assertEquals(String.valueOf(orderId), row.get("event_key"));
        Assertions.assertEquals("aftersales.case_opened", row.get("event_type"));
        Assertions.assertEquals(1L, ((Number) row.get("aggregate_sequence")).longValue());
        Long orderCancelledEventId = jdbcTemplate.queryForObject("""
                SELECT MAX(id)
                FROM outbox_events
                WHERE aggregate_type = 'ORDER'
                  AND aggregate_id = ?
                  AND event_type = 'order.cancelled'
                """, Long.class, orderId);
        Assertions.assertNotNull(orderCancelledEventId);
        Assertions.assertTrue(orderCancelledEventId < ((Number) row.get("id")).longValue(),
                "the after-sales event follows the order's own event");
        AfterSalesEventEnvelope event = eventContracts.read(
                EventTopic.AFTER_SALES_EVENTS, String.valueOf(row.get("payload")), AfterSalesEventEnvelope.class);
        Assertions.assertEquals(opened.id(), event.aggregateId());
        Assertions.assertEquals(cancelledAt, event.occurredAt());
        Assertions.assertEquals("ORDER_CANCELLED", event.data().kind());
        Assertions.assertEquals("REFUNDING", event.data().status());
        Assertions.assertEquals("ORDER_CANCELLED", event.data().action());
        Assertions.assertEquals("SYSTEM", event.data().actorType());
        Assertions.assertEquals(order.orderNo(), event.data().orderNo());
        Assertions.assertEquals(total, Money.of(event.data().approvedAmount(), event.data().currency()));
        Assertions.assertEquals(orderLines.size(), event.data().lines().size());
        return opened;
    }


    /**
     * No case, refund or after-sales event for the order.
     */
    void assertNothingOpened(Long orderId) {
        Assertions.assertEquals(List.of(), caseIds(orderId));
        Assertions.assertEquals(0, refunds(orderId));
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE topic = ? AND event_key = ?",
                Integer.class, AFTER_SALES_TOPIC, String.valueOf(orderId)));
    }


    // ---- helpers ----


    record Customer(Long id, String email, MockHttpSession session) {
    }


    Customer customer(String prefix) throws Exception {
        String email = prefix + System.nanoTime() + "@example.com";
        customerService.signUp(email, "demo123", "Cancel", "Refund");
        Long id = jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
        MockHttpSession session = (MockHttpSession) mockMvc.perform(post("/login")
                        .param("username", email)
                        .param("password", "demo123"))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
        return new Customer(id, email, session);
    }


    Long adminId() {
        return jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = 'demo@laifood.com'", Long.class);
    }


    /**
     * The {@code index}-th priced menu item of the restaurant, by id.
     */
    Long menuItem(String restaurantName, int index) {
        return jdbcTemplate.queryForList("""
                SELECT menu_items.id
                FROM menu_items
                JOIN restaurants ON restaurants.id = menu_items.restaurant_id
                WHERE restaurants.name = ?
                  AND menu_items.price > 0
                ORDER BY menu_items.id
                """, Long.class, restaurantName).get(index);
    }


    Long freeMenuItem(String restaurantName) {
        String name = "Free Napkins " + System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO menu_items (restaurant_id, name, price, description, currency)
                SELECT id, ?, 0.00, 'Free', currency
                FROM restaurants
                WHERE name = ?
                """, name, restaurantName);
        return jdbcTemplate.queryForObject("SELECT id FROM menu_items WHERE name = ?", Long.class, name);
    }


    /**
     * A paid checkout of the menu items (an item listed twice is one line of quantity two), which records the
     * payment capture.
     */
    OrderDto paidOrder(Customer customer, Long... menuItemIds) {
        for (Long menuItemId : menuItemIds) {
            cartService.addMenuItemToCart(customer.id(), menuItemId);
        }
        OrderDto paid = cartService.checkout(customer.id(), "PAID");
        Assertions.assertEquals("PAID", paid.status());
        return paid;
    }


    /**
     * A checkout without payment: PLACED, and no payment capture.
     */
    OrderDto placedOrder(Customer customer, Long... menuItemIds) {
        for (Long menuItemId : menuItemIds) {
            cartService.addMenuItemToCart(customer.id(), menuItemId);
        }
        OrderDto placed = cartService.checkout(customer.id());
        Assertions.assertEquals("PLACED", placed.status());
        return placed;
    }


    ResultActions cancel(Customer customer, Long orderId, String key) throws Exception {
        return mockMvc.perform(post("/orders/{id}/cancel", orderId)
                .session(customer.session())
                .header("Idempotency-Key", key));
    }


    ResultActions openRequest(Customer customer, Long orderId, String key) throws Exception {
        return mockMvc.perform(post("/orders/{id}/after-sales", orderId)
                .session(customer.session())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"scope": "WHOLE_ORDER", "reason_code": "MISSING_ITEM", "description": "The fries were missing."}
                        """));
    }


    JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }


    List<Long> caseIds(Long orderId) {
        return caseRepository.findAllByOrderId(orderId).stream().map(AfterSalesCaseEntity::id).toList();
    }


    int refunds(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refunds WHERE order_id = ?", Integer.class, orderId);
    }


    private static String line(long orderItemId, String name, int quantity, Money unitPrice) {
        return orderItemId + "|" + name + "|" + quantity + "|" + unitPrice + "|" + unitPrice.times(quantity);
    }
}
