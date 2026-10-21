package com.laioffer.onlineorder.fulfilment.merchant;


import com.laioffer.onlineorder.messaging.AfterSalesEventEnvelope;
import com.laioffer.onlineorder.messaging.contract.EventContracts;
import com.laioffer.onlineorder.messaging.contract.EventTopic;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.CustomerService;
import com.laioffer.onlineorder.service.OrderService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * The merchant's side of after-sales (ADR 0002 sections 7, 10.3 and 11.3) on a real database, through HTTP: the inbox
 * built from the after-sales events by the merchant's consumer, the three answers through the order module's seam with
 * a repeat answered unchanged, the 404 of another restaurant's case and of an automatic refund, the 403 of a
 * deactivated merchant, the answer refused from the deadline on, and the consumer applying each event once and never
 * moving a row back. Kafka is off: the tests hand the events the outbox recorded to the inbox service, as the consumer
 * would. The cases have to hold on PostgreSQL and on H2 (the {@code h2} profile), so each database gets a subclass.
 */
abstract class AbstractMerchantAfterSalesIntegrationTests {

    static final String BURGER_KING = "Burger King";

    static final String BURGER_KING_MERCHANT = "merchant.burgerking@laifood.com";

    static final String TOFU_HOUSE_MERCHANT = "merchant.sgdtofu@laifood.com";

    static final String MERCHANT_PASSWORD = "merchant123";

    static final String ADMIN = "demo@laifood.com";

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
    EventContracts eventContracts;

    @Autowired
    MerchantAfterSalesInboxService inboxService;

    @Autowired
    MeterRegistry meterRegistry;


    @Test
    void inbox_shouldListTheRestaurantsRequestsOnceTheirEventsArrive() throws Exception {
        Customer customer = customer("merchant-inbox-");
        CompletedOrder order = completedOrder(customer, menuItem(BURGER_KING, 0), menuItem(BURGER_KING, 0),
                menuItem(BURGER_KING, 1));
        long caseId = openWholeOrder(customer, order);
        MockHttpSession burgerKing = login(BURGER_KING_MERCHANT, MERCHANT_PASSWORD);
        Assertions.assertNull(inboxCase(burgerKing, caseId, null), "the inbox has not seen the case's events yet");

        deliverEvents(caseId);

        JsonNode listed = inboxCase(burgerKing, caseId, null);
        Assertions.assertNotNull(listed);
        Assertions.assertEquals(order.orderId(), listed.get("order_id").asLong());
        Assertions.assertEquals(String.valueOf(order.orderNo()), listed.get("order_no").asString());
        Assertions.assertEquals("PENDING_MERCHANT", listed.get("status").asString());
        Assertions.assertEquals("MISSING_ITEM", listed.get("reason_code").asString());
        Assertions.assertEquals("The fries were missing.", listed.get("description").asString());
        Assertions.assertEquals(0, order.total().compareTo(Money.of(listed.get("requested_amount").decimalValue(), "USD")));
        Assertions.assertEquals("USD", listed.get("currency").asString());
        Assertions.assertEquals(2, listed.get("lines").size());
        List<Integer> quantities = new ArrayList<>();
        listed.get("lines").forEach(line -> {
            quantities.add(line.get("quantity").asInt());
            Assertions.assertEquals("USD", line.get("currency").asString());
            Assertions.assertTrue(line.has("menu_item_name") && line.has("unit_price") && line.has("amount"));
        });
        Assertions.assertEquals(List.of(1, 2), quantities.stream().sorted().toList());
        Assertions.assertEquals(caseDeadline(caseId), LocalDateTime.parse(listed.get("deadline_at").asString()));
        Assertions.assertTrue(listed.has("opened_at") && listed.has("updated_at"));
        Assertions.assertFalse(listed.has("offered_amount") || listed.has("merchant_decision"), listed::toString);

        Assertions.assertNotNull(inboxCase(burgerKing, caseId, "pending_merchant"));
        Assertions.assertNull(inboxCase(burgerKing, caseId, "REFUNDED"));
        mockMvc.perform(get("/merchant/after-sales").param("status", "OPEN").session(burgerKing))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unsupported after-sales status: OPEN"));
        Assertions.assertNull(inboxCase(login(TOFU_HOUSE_MERCHANT, MERCHANT_PASSWORD), caseId, null),
                "another restaurant's merchant never sees the case");
        // Merchants only (AppConfig's /merchant/** matcher): a customer is forbidden, nobody signed in unauthorized.
        mockMvc.perform(get("/merchant/after-sales").session(customer.session())).andExpect(status().isForbidden());
        answer(customer.session(), caseId, "approve", null).andExpect(status().isForbidden());
        mockMvc.perform(get("/merchant/after-sales")).andExpect(status().isUnauthorized());

        // A redelivery changes nothing: one mark per event, the same row.
        String updatedAt = listed.get("updated_at").asString();
        deliverEvents(caseId);
        Assertions.assertEquals(updatedAt, inboxCase(burgerKing, caseId, null).get("updated_at").asString());
        Assertions.assertEquals(1, processedMarks(caseId));
    }


    @Test
    void approve_shouldRefundTheRequestedAmountAndARepeatShouldChangeNothing() throws Exception {
        Customer customer = customer("merchant-approve-");
        CompletedOrder order = completedOrder(customer, menuItem(BURGER_KING, 0), menuItem(BURGER_KING, 1));
        long caseId = openWholeOrder(customer, order);
        deliverEvents(caseId);
        MockHttpSession burgerKing = login(BURGER_KING_MERCHANT, MERCHANT_PASSWORD);
        double approvedBefore = merchantDecisions("approved");

        JsonNode approved = json(answer(burgerKing, caseId, "approve", null).andExpect(status().isOk()));

        Assertions.assertEquals(caseId, approved.get("case_id").asLong());
        Assertions.assertEquals("REFUNDING", approved.get("status").asString());
        Assertions.assertEquals("APPROVED", approved.get("merchant_decision").asString());
        Assertions.assertEquals(0, order.total().compareTo(Money.of(approved.get("approved_amount").decimalValue(), "USD")));
        Assertions.assertFalse(approved.has("deadline_at"), "REFUNDING waits for no answer");
        Map<String, Object> current = caseRow(caseId);
        Assertions.assertEquals("REFUNDING", current.get("status"));
        Assertions.assertEquals("MERCHANT_APPROVED", current.get("resolution"));
        Assertions.assertEquals(1L, ((Number) current.get("version")).longValue());
        Assertions.assertEquals(1, refunds(caseId));
        Assertions.assertEquals("MERCHANT_APPROVE|PENDING_MERCHANT->REFUNDING|MERCHANT|" + merchantId(), lastStep(caseId));
        Assertions.assertEquals(List.of("aftersales.case_opened", "aftersales.refund_approved"), eventTypes(caseId));
        Assertions.assertEquals(1L, projectionVersion(caseId));
        Assertions.assertEquals(approvedBefore + 1, merchantDecisions("approved"));

        // A retry gets the case as it is: no second step, refund, event or count.
        answer(burgerKing, caseId, "approve", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDING"));
        Assertions.assertEquals(1L, ((Number) caseRow(caseId).get("version")).longValue());
        Assertions.assertEquals(1, refunds(caseId));
        Assertions.assertEquals(2, historyRows(caseId));
        Assertions.assertEquals(List.of("aftersales.case_opened", "aftersales.refund_approved"), eventTypes(caseId));
        Assertions.assertEquals(approvedBefore + 1, merchantDecisions("approved"));

        // Any other answer comes too late.
        answer(burgerKing, caseId, "reject", "{\"reason\": \"Changed my mind\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_STATUS_CONFLICT"))
                .andExpect(jsonPath("$.message")
                        .value("After-sales case is REFUNDING; only PENDING_MERCHANT cases can be rejected"));

        // The event of the step the command already wrote into the inbox changes nothing there.
        String updatedAt = inboxCase(burgerKing, caseId, null).get("updated_at").asString();
        deliverEvents(caseId);
        Assertions.assertEquals(updatedAt, inboxCase(burgerKing, caseId, null).get("updated_at").asString());
        Assertions.assertEquals(1L, projectionVersion(caseId));
    }


    @Test
    void reject_shouldGiveTheCustomerTheReasonAndTheChanceToEscalate() throws Exception {
        Customer customer = customer("merchant-reject-");
        long caseId = openWholeOrder(customer, completedOrder(customer, menuItem(BURGER_KING, 0)));
        deliverEvents(caseId);
        MockHttpSession burgerKing = login(BURGER_KING_MERCHANT, MERCHANT_PASSWORD);

        answer(burgerKing, caseId, "reject", "{\"reason\": \"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        answer(burgerKing, caseId, "reject", "{\"reason\": \"  Nothing was missing.  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.merchant_decision").value("REJECTED"))
                .andExpect(jsonPath("$.merchant_note").value("Nothing was missing."))
                .andExpect(jsonPath("$.deadline_at").exists());

        JsonNode seenByCustomer = json(mockMvc.perform(get("/after-sales/cases/{id}", caseId)
                .session(customer.session())).andExpect(status().isOk()));
        Assertions.assertEquals("REJECTED", seenByCustomer.get("status").asString());
        Assertions.assertEquals("Nothing was missing.", seenByCustomer.get("merchant_note").asString());
        Assertions.assertEquals("MERCHANT_REJECT|PENDING_MERCHANT->REJECTED|MERCHANT|" + merchantId(),
                lastStep(caseId));

        // A repeat keeps the first reason; a counter-offer after it is refused.
        answer(burgerKing, caseId, "reject", "{\"reason\": \"Something else\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchant_note").value("Nothing was missing."));
        answer(burgerKing, caseId, "offer", "{\"amount\": 1.00}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_STATUS_CONFLICT"))
                .andExpect(jsonPath("$.message")
                        .value("After-sales case is REJECTED; only PENDING_MERCHANT cases can be given a counter-offer"));
        Assertions.assertEquals(List.of("aftersales.case_opened", "aftersales.request_rejected"), eventTypes(caseId));
        Assertions.assertEquals(0, refunds(caseId));

        // The customer is not bound by it: the platform can step in.
        mockMvc.perform(post("/after-sales/cases/{id}/escalate", caseId)
                        .session(customer.session())
                        .header("Idempotency-Key", "escalate-" + caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_ARBITRATION"));
    }


    @Test
    void offer_shouldOfferPartOfTheRequestOnce() throws Exception {
        Customer customer = customer("merchant-offer-");
        CompletedOrder order = completedOrder(customer, menuItem(BURGER_KING, 0), menuItem(BURGER_KING, 1));
        long caseId = openWholeOrder(customer, order);
        deliverEvents(caseId);
        MockHttpSession burgerKing = login(BURGER_KING_MERCHANT, MERCHANT_PASSWORD);
        String outOfRange = "The amount must be more than 0 and less than the requested " + order.total();

        answer(burgerKing, caseId, "offer", "{\"amount\": 0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(outOfRange));
        answer(burgerKing, caseId, "offer", "{\"amount\": " + order.total().amount().toPlainString() + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(outOfRange));
        answer(burgerKing, caseId, "offer", "{\"amount\": 1.005}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Amount must have at most 2 decimal places"));
        answer(burgerKing, caseId, "offer", "{\"note\": \"No amount\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        Assertions.assertEquals("PENDING_MERCHANT", caseRow(caseId).get("status"), "a refused offer writes nothing");
        Assertions.assertEquals(0L, projectionVersion(caseId));

        answer(burgerKing, caseId, "offer", "{\"amount\": 2.00, \"note\": \"We can refund the fries.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COUNTER_OFFERED"))
                .andExpect(jsonPath("$.offered_amount").value(2.00))
                .andExpect(jsonPath("$.merchant_decision").value("OFFERED"))
                .andExpect(jsonPath("$.merchant_note").value("We can refund the fries."));
        Assertions.assertEquals("MERCHANT_OFFER|PENDING_MERCHANT->COUNTER_OFFERED|MERCHANT|" + merchantId(),
                lastStep(caseId));

        // The same amount, written differently, is the same offer; another amount is a second answer.
        answer(burgerKing, caseId, "offer", "{\"amount\": 2.0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COUNTER_OFFERED"));
        answer(burgerKing, caseId, "offer", "{\"amount\": 3.00}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_STATUS_CONFLICT"))
                .andExpect(jsonPath("$.message").value(
                        "After-sales case is COUNTER_OFFERED; only PENDING_MERCHANT cases can be given a counter-offer"));
        answer(burgerKing, caseId, "approve", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("After-sales case is COUNTER_OFFERED; only PENDING_MERCHANT cases can be approved"));
        Assertions.assertEquals(List.of("aftersales.case_opened", "aftersales.offer_made"), eventTypes(caseId));
        Assertions.assertEquals(0, new BigDecimal("2.00").compareTo((BigDecimal) caseRow(caseId).get("offered_amount")));
        Assertions.assertEquals(1L, projectionVersion(caseId));
    }


    @Test
    void anotherRestaurantsCaseAMissingCaseAndAnAutomaticRefund_shouldNotExistForTheMerchant() throws Exception {
        Customer customer = customer("merchant-not-found-");
        long caseId = openWholeOrder(customer, completedOrder(customer, menuItem(BURGER_KING, 0)));
        deliverEvents(caseId);

        answer(login(TOFU_HOUSE_MERCHANT, MERCHANT_PASSWORD), caseId, "approve", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("After-sales case not found"));
        MockHttpSession burgerKing = login(BURGER_KING_MERCHANT, MERCHANT_PASSWORD);
        answer(burgerKing, Long.MAX_VALUE, "approve", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("After-sales case not found"));
        Assertions.assertEquals("PENDING_MERCHANT", caseRow(caseId).get("status"));
        Assertions.assertEquals(1, historyRows(caseId));

        // The automatic refund of a paid order the customer cancelled is nobody's to answer.
        OrderDto paid = paidOrder(customer, menuItem(BURGER_KING, 0));
        mockMvc.perform(post("/orders/{id}/cancel", paid.id())
                        .session(customer.session())
                        .header("Idempotency-Key", "cancel-" + paid.id()))
                .andExpect(status().isOk());
        long refundCaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM after_sales_cases WHERE order_id = ? AND kind = 'ORDER_CANCELLED'", Long.class,
                paid.id());
        deliverEvents(refundCaseId);
        Assertions.assertNull(inboxCase(burgerKing, refundCaseId, null));
        Assertions.assertEquals(0, processedMarks(refundCaseId), "dropped before the mark");
        answer(burgerKing, refundCaseId, "approve", null).andExpect(status().isNotFound());
    }


    @Test
    void aDeactivatedMerchant_shouldBeForbiddenOnTheirNextRequestUntilReactivated() throws Exception {
        MockHttpSession admin = login(ADMIN, "demo123");
        String email = "after-sales-shift-" + System.nanoTime() + "@example.com";
        long accountId = json(mockMvc.perform(post("/admin/merchants")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "pass-1", "first_name": "Olive", "last_name": "Owner",
                                 "restaurant_id": %d}
                                """.formatted(email, restaurantId(BURGER_KING))))
                .andExpect(status().isCreated())).get("merchant_id").asLong();
        MockHttpSession merchant = login(email, "pass-1");
        Customer customer = customer("merchant-deactivated-");
        long caseId = openWholeOrder(customer, completedOrder(customer, menuItem(BURGER_KING, 0)));
        deliverEvents(caseId);
        Assertions.assertNotNull(inboxCase(merchant, caseId, null));

        setActive(admin, accountId, false);

        // The session still carries ROLE_MERCHANT; the module checks the account on every request.
        mockMvc.perform(get("/merchant/after-sales").session(merchant))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        answer(merchant, caseId, "approve", null).andExpect(status().isForbidden());
        answer(merchant, caseId, "reject", "{\"reason\": \"No\"}").andExpect(status().isForbidden());
        answer(merchant, caseId, "offer", "{\"amount\": 1.00}").andExpect(status().isForbidden());
        Assertions.assertEquals("PENDING_MERCHANT", caseRow(caseId).get("status"));
        Assertions.assertEquals(1, historyRows(caseId));

        setActive(admin, accountId, true);
        answer(merchant, caseId, "approve", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDING"));
    }


    @Test
    void anAnswerFromTheDeadlineOn_shouldBeTooLateEvenBeforeTheTimeoutFired() throws Exception {
        Customer customer = customer("merchant-late-");
        long caseId = openWholeOrder(customer, completedOrder(customer, menuItem(BURGER_KING, 0)));
        deliverEvents(caseId);
        MockHttpSession burgerKing = login(BURGER_KING_MERCHANT, MERCHANT_PASSWORD);
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(1).withNano(0);
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE after_sales_cases SET deadline_at = ? WHERE id = ?", deadline, caseId));
        String tooLate = "The deadline of this after-sales step passed at "
                + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(deadline);

        answer(burgerKing, caseId, "approve", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_DEADLINE_PASSED"))
                .andExpect(jsonPath("$.message").value(tooLate));
        answer(burgerKing, caseId, "reject", "{\"reason\": \"No\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_DEADLINE_PASSED"));
        answer(burgerKing, caseId, "offer", "{\"amount\": 1.00}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_DEADLINE_PASSED"));

        Map<String, Object> current = caseRow(caseId);
        Assertions.assertEquals("PENDING_MERCHANT", current.get("status"), "only the poller fires the timeout");
        Assertions.assertEquals(0L, ((Number) current.get("version")).longValue());
        Assertions.assertEquals(0, refunds(caseId));
        Assertions.assertEquals(0L, projectionVersion(caseId));

        // Withdrawing has no deadline; it leaves no case due for the other tests of the database.
        mockMvc.perform(post("/after-sales/cases/{id}/withdraw", caseId)
                        .session(customer.session())
                        .header("Idempotency-Key", "withdraw-" + caseId))
                .andExpect(status().isOk());
    }


    @Test
    void theConsumer_shouldApplyEachEventOnceAndNeverMoveARowBack() throws Exception {
        Customer customer = customer("merchant-consumer-");
        long caseId = openWholeOrder(customer, completedOrder(customer, menuItem(BURGER_KING, 0)));
        mockMvc.perform(post("/after-sales/cases/{id}/withdraw", caseId)
                        .session(customer.session())
                        .header("Idempotency-Key", "withdraw-" + caseId))
                .andExpect(status().isOk());
        List<AfterSalesEventEnvelope> events = events(caseId);
        Assertions.assertEquals(List.of(1L, 2L), events.stream().map(AfterSalesEventEnvelope::sequence).toList());
        AfterSalesEventEnvelope opened = events.get(0);
        AfterSalesEventEnvelope closed = events.get(1);

        // The closing arrives first (the opening was dead-lettered): it adds the row with the whole case.
        inboxService.handle(closed);
        Assertions.assertEquals("CLOSED|1", projection(caseId));
        Assertions.assertEquals("MISSING_ITEM", jdbcTemplate.queryForObject(
                "SELECT reason_code FROM merchant_after_sales_cases WHERE case_id = ?", String.class, caseId));

        // The replayed opening is older: marked, but skipped.
        inboxService.handle(opened);
        Assertions.assertEquals("CLOSED|1", projection(caseId));
        Assertions.assertEquals(2, processedMarks(caseId));
        Assertions.assertEquals(2L, jdbcTemplate.queryForObject("""
                SELECT last_sequence
                FROM consumer_aggregate_sequences
                WHERE consumer_name = 'merchant-aftersales-consumer'
                  AND aggregate_type = 'AFTER_SALES_CASE'
                  AND aggregate_id = ?
                """, Long.class, caseId));

        // A duplicate is a no-op, and an older version without a sequence still cannot move the row back.
        inboxService.handle(closed);
        inboxService.handle(new AfterSalesEventEnvelope("unsequenced-" + opened.eventId(), opened.eventVersion(),
                opened.eventType(), opened.aggregateType(), opened.aggregateId(), null, opened.occurredAt(),
                opened.correlationId(), opened.idempotencyKey(), opened.data()));
        Assertions.assertEquals("CLOSED|1", projection(caseId));
        Assertions.assertEquals(3, processedMarks(caseId));
    }


    // ---- helpers ----


    record Customer(Long id, MockHttpSession session) {
    }


    /**
     * @param total the order total, which a whole-order request asks for
     */
    record CompletedOrder(Long orderId, Long orderNo, Money total) {
    }


    Customer customer(String prefix) throws Exception {
        String email = prefix + System.nanoTime() + "@example.com";
        customerService.signUp(email, "demo123", "After", "Sales");
        return new Customer(customerIdOf(email), login(email, "demo123"));
    }


    MockHttpSession login(String email, String password) throws Exception {
        return (MockHttpSession) mockMvc.perform(post("/login")
                        .param("username", email)
                        .param("password", password))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
    }


    Long customerIdOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
    }


    Long merchantId() {
        return customerIdOf(BURGER_KING_MERCHANT);
    }


    Long restaurantId(String name) {
        return jdbcTemplate.queryForObject("SELECT id FROM restaurants WHERE name = ?", Long.class, name);
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


    /**
     * A paid checkout of the menu items (an item listed twice is one line of quantity two), with its payment capture.
     */
    OrderDto paidOrder(Customer customer, Long... menuItemIds) {
        for (Long menuItemId : menuItemIds) {
            cartService.addMenuItemToCart(customer.id(), menuItemId);
        }
        return cartService.checkout(customer.id(), "PAID");
    }


    CompletedOrder completedOrder(Customer customer, Long... menuItemIds) {
        OrderDto paid = paidOrder(customer, menuItemIds);
        for (String next : List.of("ACCEPTED", "PREPARING", "COMPLETED")) {
            orderService.transitionOrderStatus(paid.id(), next);
        }
        Long orderNo = jdbcTemplate.queryForObject("SELECT order_no FROM orders WHERE id = ?", Long.class, paid.id());
        BigDecimal total = jdbcTemplate.queryForObject(
                "SELECT total_price FROM orders WHERE id = ?", BigDecimal.class, paid.id());
        return new CompletedOrder(paid.id(), orderNo, Money.of(total, "USD"));
    }


    long openWholeOrder(Customer customer, CompletedOrder order) throws Exception {
        return json(mockMvc.perform(post("/orders/{id}/after-sales", order.orderId())
                        .session(customer.session())
                        .header("Idempotency-Key", "open-" + order.orderId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope": "WHOLE_ORDER", "reason_code": "MISSING_ITEM",
                                 "description": "The fries were missing."}
                                """))
                .andExpect(status().isOk())).get("id").asLong();
    }


    ResultActions answer(MockHttpSession merchant, long caseId, String command, String body) throws Exception {
        var request = post("/merchant/after-sales/{id}/" + command, caseId).session(merchant);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }


    void setActive(MockHttpSession admin, long accountId, boolean active) throws Exception {
        mockMvc.perform(patch("/admin/merchants/{id}", accountId)
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": " + active + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(active));
    }


    /**
     * The case as the merchant's inbox lists it (at most the newest 100, optionally of one status), or null.
     */
    JsonNode inboxCase(MockHttpSession merchant, long caseId, String statusFilter) throws Exception {
        var request = get("/merchant/after-sales").param("limit", "100").session(merchant);
        if (statusFilter != null) {
            request = request.param("status", statusFilter);
        }
        for (JsonNode listed : json(mockMvc.perform(request).andExpect(status().isOk()))) {
            if (listed.get("case_id").asLong() == caseId) {
                return listed;
            }
        }
        return null;
    }


    /**
     * The case's after-sales events as the outbox recorded them, in the order of the case.
     */
    List<AfterSalesEventEnvelope> events(long caseId) {
        return jdbcTemplate.queryForList("""
                        SELECT payload
                        FROM outbox_events
                        WHERE aggregate_type = 'AFTER_SALES_CASE'
                          AND aggregate_id = ?
                        ORDER BY aggregate_sequence
                        """, String.class, caseId).stream()
                .map(payload -> eventContracts.read(EventTopic.AFTER_SALES_EVENTS, payload, AfterSalesEventEnvelope.class))
                .toList();
    }


    /**
     * Hands the case's events to the merchant's inbox in order, as its consumer would.
     */
    void deliverEvents(long caseId) {
        events(caseId).forEach(inboxService::handle);
    }


    List<String> eventTypes(long caseId) {
        return events(caseId).stream().map(AfterSalesEventEnvelope::eventType).toList();
    }


    Map<String, Object> caseRow(long caseId) {
        return jdbcTemplate.queryForMap("SELECT * FROM after_sales_cases WHERE id = ?", caseId);
    }


    LocalDateTime caseDeadline(long caseId) {
        return jdbcTemplate.queryForObject(
                "SELECT deadline_at FROM after_sales_cases WHERE id = ?", LocalDateTime.class, caseId);
    }


    /**
     * The case's last history row as "action|from->to|actor|actor id".
     */
    String lastStep(long caseId) {
        return jdbcTemplate.queryForObject("""
                SELECT action || '|' || COALESCE(from_status, 'null') || '->' || to_status || '|' || actor_type || '|'
                    || COALESCE(CAST(actor_id AS VARCHAR), 'null')
                FROM after_sales_case_history
                WHERE case_id = ?
                ORDER BY id DESC
                LIMIT 1
                """, String.class, caseId);
    }


    int historyRows(long caseId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM after_sales_case_history WHERE case_id = ?", Integer.class, caseId);
    }


    int refunds(long caseId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refunds WHERE case_id = ?", Integer.class, caseId);
    }


    Long projectionVersion(long caseId) {
        return jdbcTemplate.queryForObject(
                "SELECT case_version FROM merchant_after_sales_cases WHERE case_id = ?", Long.class, caseId);
    }


    /**
     * The inbox row of the case as "status|case version".
     */
    String projection(long caseId) {
        return jdbcTemplate.queryForObject(
                "SELECT status || '|' || CAST(case_version AS VARCHAR) FROM merchant_after_sales_cases WHERE case_id = ?",
                String.class, caseId);
    }


    /**
     * The merchant inbox's processed_events marks of the case's events.
     */
    int processedMarks(long caseId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM processed_events
                WHERE consumer_name = 'merchant-aftersales-consumer'
                  AND aggregate_type = 'AFTER_SALES_CASE'
                  AND aggregate_id = ?
                """, Integer.class, caseId);
    }


    double merchantDecisions(String decision) {
        Counter counter = meterRegistry.find("onlineorder.aftersales.merchant_decisions")
                .tag("decision", decision)
                .counter();
        return counter == null ? 0 : counter.count();
    }


    JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
