package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantAfterSalesInboxService;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;


import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * The customer's after-sales API (ADR 0002 sections 11.2, 19 and 20) on a real database, through HTTP: opening a
 * request under the order's lock with every eligibility and money rule, the one second request after a withdrawal,
 * idempotent replays bound to their case, the answers to a counter-offer and a rejection, the deadlines, and that
 * another customer's orders and cases do not exist for the caller. The merchant answers through the merchant's
 * endpoints, once the merchant's inbox got the case's events. The cases have to hold on PostgreSQL and on H2 (the
 * {@code h2} profile), so each database gets a subclass.
 */
abstract class AbstractAfterSalesApiIntegrationTests {

    static final String BURGER_KING = "Burger King";

    static final String BURGER_KING_MERCHANT = "merchant.burgerking@laifood.com";

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
    AfterSalesTransitions transitions;

    @Autowired
    AfterSalesCaseRepository caseRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    EventContracts eventContracts;

    @Autowired
    MerchantAfterSalesInboxService merchantInbox;


    @Test
    void openWithdrawAndReopen_shouldAllowOneSecondRequestAndBindEveryKeyToItsCase() throws Exception {
        Customer alice = customer("after-sales-reopen-");
        CompletedOrder order = completedOrder(alice, menuItem(BURGER_KING, 0), menuItem(BURGER_KING, 0),
                menuItem(BURGER_KING, 1));
        JsonNode eligible = eligibleOrder(alice, order.orderId());
        Assertions.assertEquals(1, eligible.get("attempt_no").asInt());
        Assertions.assertEquals(String.valueOf(order.orderNo()), eligible.get("order_no").asString());
        Assertions.assertEquals(
                order.completedAt().plusHours(24), LocalDateTime.parse(eligible.get("request_deadline").asString()));

        String firstKey = "open-first-" + order.orderId();
        LocalDateTime beforeOpen = LocalDateTime.now();
        JsonNode first = json(open(alice, order.orderId(), firstKey, wholeOrder())
                .andExpect(status().isOk()));
        long firstId = first.get("id").asLong();
        Assertions.assertEquals("PENDING_MERCHANT", first.get("status").asString());
        Assertions.assertEquals("CUSTOMER_REQUEST", first.get("kind").asString());
        Assertions.assertEquals(1, first.get("attempt_no").asInt());
        Assertions.assertEquals(String.valueOf(order.orderNo()), first.get("order_no").asString());
        Assertions.assertEquals(order.total(), Money.of(first.get("requested_amount").decimalValue(), "USD"));
        Assertions.assertEquals("USD", first.get("currency").asString());
        List<Integer> quantities = new ArrayList<>();
        first.get("lines").forEach(line -> quantities.add(line.get("quantity").asInt()));
        Assertions.assertEquals(List.of(1, 2), quantities.stream().sorted().toList());
        Assertions.assertEquals("MISSING_ITEM", first.get("reason_code").asString());
        assertDeadlineAbout(beforeOpen, Duration.ofHours(24), first.get("deadline_at"));
        Assertions.assertEquals(List.of("OPEN|null->PENDING_MERCHANT|CUSTOMER"), historyOf(first));

        // The replay answers with the case its key opened; nothing else is written.
        JsonNode replay = json(open(alice, order.orderId(), firstKey, wholeOrder()).andExpect(status().isOk()));
        Assertions.assertEquals(firstId, replay.get("id").asLong());
        Assertions.assertEquals(1, idempotencyRows(alice.id(), "after-sales-open:v1:" + order.orderId(), firstKey));
        Assertions.assertEquals(List.of("aftersales.case_opened"), eventTypes(firstId));
        Assertions.assertNull(eligibleOrderOrNull(alice, order.orderId()),
                "an open request takes the order off the list");

        open(alice, order.orderId(), "open-while-open-" + order.orderId(), wholeOrder())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.message").value("An after-sales case is already open for this order"));

        command(alice, firstId, "withdraw", "withdraw-first-" + firstId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.close_reason").value("WITHDRAWN"))
                .andExpect(jsonPath("$.deadline_at").doesNotExist());
        Assertions.assertEquals(2, eligibleOrder(alice, order.orderId()).get("attempt_no").asInt());

        // The first key still answers with the first case, now withdrawn: it never opens the second one.
        open(alice, order.orderId(), firstKey, wholeOrder())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId))
                .andExpect(jsonPath("$.status").value("CLOSED"));

        String secondKey = "open-second-" + order.orderId();
        JsonNode second = json(open(alice, order.orderId(), secondKey, items(order.itemIds().get(1), 1))
                .andExpect(status().isOk()));
        long secondId = second.get("id").asLong();
        Assertions.assertNotEquals(firstId, secondId);
        Assertions.assertEquals(2, second.get("attempt_no").asInt());
        Assertions.assertEquals(1, second.get("lines").size());
        open(alice, order.orderId(), secondKey, items(order.itemIds().get(1), 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(secondId));
        open(alice, order.orderId(), firstKey, wholeOrder())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId));
        // A known key with another request is refused, whatever case it opened.
        open(alice, order.orderId(), secondKey, wholeOrder())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Idempotency key was already used with a different request"));

        command(alice, secondId, "withdraw", "withdraw-second-" + secondId).andExpect(status().isOk());
        open(alice, order.orderId(), "open-third-" + order.orderId(), wholeOrder())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.message").value("This order cannot be submitted for after-sales again"));
        Assertions.assertNull(eligibleOrderOrNull(alice, order.orderId()));

        Assertions.assertEquals(List.of(1, 2), caseRepository.findAllByOrderId(order.orderId()).stream()
                .map(AfterSalesCaseEntity::attemptNo).toList());
        Assertions.assertEquals(List.of("aftersales.case_opened", "aftersales.case_closed"), eventTypes(firstId));
        Assertions.assertEquals(List.of("aftersales.case_opened", "aftersales.case_closed"), eventTypes(secondId));
        Assertions.assertEquals(List.of(1L, 2L), aggregateSequences(secondId));
        Assertions.assertEquals(0, refunds(firstId) + refunds(secondId));
        JsonNode overview = overview(alice);
        Assertions.assertEquals(List.of(secondId, firstId), ids(overview.get("cases")));
        Assertions.assertFalse(overview.get("cases").get(0).has("history"), "lists carry no history");
    }


    @Test
    void customerAnswers_shouldAcceptDeclineEscalateAndKeepToTheirDeadlines() throws Exception {
        Customer bob = customer("after-sales-answers-");

        // Accepting a counter-offer refunds the offered amount.
        CompletedOrder offered = completedOrder(bob, menuItem(BURGER_KING, 1));
        long offeredCase = openWholeOrder(bob, offered);
        merchantOffers(offeredCase, "2.00");
        LocalDateTime beforeAccept = LocalDateTime.now();
        JsonNode accepted = json(command(bob, offeredCase, "accept-offer", "accept-" + offeredCase)
                .andExpect(status().isOk()));
        Assertions.assertEquals("REFUNDING", accepted.get("status").asString());
        Assertions.assertEquals(new BigDecimal("2.00"), accepted.get("approved_amount").decimalValue().setScale(2));
        Assertions.assertEquals("OFFER_ACCEPTED", accepted.get("resolution").asString());
        Assertions.assertFalse(accepted.has("deadline_at"));
        JsonNode refund = accepted.get("refund");
        Assertions.assertEquals("PENDING", refund.get("status").asString());
        Assertions.assertEquals(new BigDecimal("2.00"), refund.get("amount").decimalValue().setScale(2));
        Assertions.assertEquals("USD", refund.get("currency").asString());
        Assertions.assertEquals(0, refund.get("attempts").asInt());
        Assertions.assertEquals(String.valueOf(offered.orderNo()), refund.get("order_no").asString());
        Assertions.assertFalse(LocalDateTime.parse(refund.get("next_attempt_at").asString()).isBefore(
                beforeAccept.minusSeconds(1)));
        Assertions.assertEquals(
                "after-sales-case:" + offeredCase,
                jdbcTemplate.queryForObject(
                        "SELECT gateway_key FROM refunds WHERE case_id = ?", String.class, offeredCase)
        );
        Assertions.assertEquals(
                jdbcTemplate.queryForObject("SELECT provider_reference FROM order_payments WHERE order_id = ?",
                        String.class, offered.orderId()),
                jdbcTemplate.queryForObject("SELECT payment_reference FROM refunds WHERE case_id = ?", String.class,
                        offeredCase)
        );
        command(bob, offeredCase, "accept-offer", "accept-" + offeredCase)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDING"));
        Assertions.assertEquals(1, refunds(offeredCase));
        command(bob, offeredCase, "withdraw", "withdraw-refunding-" + offeredCase)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_STATUS_CONFLICT"))
                .andExpect(jsonPath("$.message").value("After-sales case is REFUNDING; only PENDING_MERCHANT or "
                        + "COUNTER_OFFERED or REJECTED or IN_ARBITRATION cases can be withdrawn"));
        Assertions.assertEquals(
                List.of("aftersales.case_opened", "aftersales.offer_made", "aftersales.refund_approved"),
                eventTypes(offeredCase)
        );
        Assertions.assertEquals(
                List.of(
                        "OPEN|null->PENDING_MERCHANT|CUSTOMER",
                        "MERCHANT_OFFER|PENDING_MERCHANT->COUNTER_OFFERED|MERCHANT",
                        "CUSTOMER_ACCEPT_OFFER|COUNTER_OFFERED->REFUNDING|CUSTOMER"
                ),
                historyOf(caseJson(bob, offeredCase))
        );

        // Declining it leaves a rejection the customer can escalate, once.
        CompletedOrder declined = completedOrder(bob, menuItem(BURGER_KING, 1));
        long declinedCase = openWholeOrder(bob, declined);
        command(bob, declinedCase, "decline-offer", "decline-early-" + declinedCase)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_STATUS_CONFLICT"))
                .andExpect(jsonPath("$.message")
                        .value("After-sales case is PENDING_MERCHANT; only COUNTER_OFFERED cases can be declined"));
        Assertions.assertEquals(0, idempotencyRows(bob.id(), "after-sales-decline-offer:v1:" + declinedCase,
                "decline-early-" + declinedCase), "a refused command keeps no key");
        merchantOffers(declinedCase, "1.00");
        LocalDateTime beforeDecline = LocalDateTime.now();
        JsonNode rejected = json(command(bob, declinedCase, "decline-offer", "decline-" + declinedCase)
                .andExpect(status().isOk()));
        Assertions.assertEquals("REJECTED", rejected.get("status").asString());
        Assertions.assertEquals("OFFERED", rejected.get("merchant_decision").asString());
        assertDeadlineAbout(beforeDecline, Duration.ofHours(48), rejected.get("deadline_at"));
        LocalDateTime beforeEscalate = LocalDateTime.now();
        JsonNode escalated = json(mockMvc.perform(post("/after-sales/cases/{id}/escalate", declinedCase)
                        .session(bob.session())
                        .header("Idempotency-Key", "escalate-" + declinedCase)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\": \"  The offer does not cover the missing meal.  \"}"))
                .andExpect(status().isOk()));
        Assertions.assertEquals("IN_ARBITRATION", escalated.get("status").asString());
        assertDeadlineAbout(beforeEscalate, Duration.ofHours(72), escalated.get("deadline_at"));
        JsonNode escalation = escalated.get("history").get(3);
        Assertions.assertEquals("CUSTOMER_ESCALATE", escalation.get("action").asString());
        Assertions.assertEquals("The offer does not cover the missing meal.", escalation.get("note").asString());
        // Escalating again with another key is the same step: answered unchanged.
        mockMvc.perform(post("/after-sales/cases/{id}/escalate", declinedCase)
                        .session(bob.session())
                        .header("Idempotency-Key", "escalate-again-" + declinedCase))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_ARBITRATION"));
        Assertions.assertEquals(List.of("aftersales.case_opened", "aftersales.offer_made",
                "aftersales.request_rejected", "aftersales.case_escalated"), eventTypes(declinedCase));

        // Past the deadline the customer can no longer answer, but can still withdraw.
        CompletedOrder late = completedOrder(bob, menuItem(BURGER_KING, 1));
        long lateCase = openWholeOrder(bob, late);
        merchantRejects(lateCase, "The order was complete.");
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(5).withNano(0);
        jdbcTemplate.update("UPDATE after_sales_cases SET deadline_at = ? WHERE id = ?", deadline, lateCase);
        command(bob, lateCase, "escalate", "escalate-late-" + lateCase)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_DEADLINE_PASSED"))
                .andExpect(jsonPath("$.message")
                        .value("The deadline of this after-sales step passed at "
                                + AfterSalesErrors.timestamp(deadline)));
        command(bob, lateCase, "withdraw", "withdraw-late-" + lateCase)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.merchant_note").value("The order was complete."));
    }


    @Test
    void anotherCustomersOrdersAndCases_shouldNotExistForTheCaller() throws Exception {
        Customer owner = customer("after-sales-owner-");
        Customer stranger = customer("after-sales-stranger-");
        CompletedOrder order = completedOrder(owner, menuItem(BURGER_KING, 0));
        long caseId = openWholeOrder(owner, order);

        mockMvc.perform(get("/after-sales/cases/{id}", caseId).session(stranger.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("After-sales case not found"));
        for (String command : List.of("withdraw", "accept-offer", "decline-offer", "escalate")) {
            command(stranger, caseId, command, "stranger-" + command + "-" + caseId)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("After-sales case not found"));
        }
        open(stranger, order.orderId(), "stranger-open-" + order.orderId(), wholeOrder())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Order not found"));
        Assertions.assertEquals(List.of(), ids(overview(stranger).get("cases")));
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_requests WHERE customer_id = ?", Integer.class, stranger.id()));
        mockMvc.perform(get("/after-sales/cases/{id}", caseId).session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_MERCHANT"));

        mockMvc.perform(get("/after-sales/cases"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(post("/orders/{id}/after-sales", order.orderId())
                        .header("Idempotency-Key", "anonymous")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wholeOrder()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/after-sales/cases/{id}/withdraw", caseId).header("Idempotency-Key", "anonymous"))
                .andExpect(status().isUnauthorized());
    }


    @Test
    void money_shouldFollowTheLinesAndStayWithinTheCapturedPayment() throws Exception {
        Customer carol = customer("after-sales-money-");
        Long whopper = menuItem(BURGER_KING, 1);
        BigDecimal whopperPrice = price(whopper);
        CompletedOrder order = completedOrder(carol, whopper, whopper, menuItem(BURGER_KING, 0));

        JsonNode partial = json(open(carol, order.orderId(), "partial-" + order.orderId(),
                items(lineOf(order, whopper), 1)).andExpect(status().isOk()));
        Assertions.assertEquals(whopperPrice, partial.get("requested_amount").decimalValue().setScale(2));
        JsonNode line = partial.get("lines").get(0);
        Assertions.assertEquals(lineOf(order, whopper), line.get("order_item_id").asLong());
        Assertions.assertEquals(1, line.get("quantity").asInt());
        Assertions.assertEquals(whopperPrice, line.get("unit_price").decimalValue().setScale(2));
        Assertions.assertEquals(whopperPrice, line.get("amount").decimalValue().setScale(2));
        Assertions.assertEquals("USD", line.get("currency").asString());
        Assertions.assertEquals(order.total(), Money.of(
                jdbcTemplate.queryForObject("SELECT order_total FROM after_sales_cases WHERE id = ?", BigDecimal.class,
                        partial.get("id").asLong()), "USD"));

        // A request of free items only refunds nothing: refused before anything is written.
        Long freebie = freeMenuItem(BURGER_KING);
        CompletedOrder withFreebie = completedOrder(carol, freebie, whopper);
        String zeroKey = "zero-" + withFreebie.orderId();
        open(carol, withFreebie.orderId(), zeroKey, items(lineOf(withFreebie, freebie), 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Refund amount must be greater than zero"));
        Assertions.assertEquals(List.of(), caseRepository.findAllByOrderId(withFreebie.orderId()));
        Assertions.assertEquals(0,
                idempotencyRows(carol.id(), "after-sales-open:v1:" + withFreebie.orderId(), zeroKey));

        // The lines must be the order's and within what was ordered.
        open(carol, withFreebie.orderId(), "foreign-" + withFreebie.orderId(), items(lineOf(order, whopper), 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Order item " + lineOf(order, whopper) + " is not part of this order"));
        open(carol, withFreebie.orderId(), "too-many-" + withFreebie.orderId(), items(lineOf(withFreebie, whopper), 2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Order item " + lineOf(withFreebie, whopper)
                        + " was ordered 1 times; at most that many can be refunded"));
        open(carol, withFreebie.orderId(), "zero-quantity-" + withFreebie.orderId(),
                items(lineOf(withFreebie, whopper), 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        open(carol, withFreebie.orderId(), "no-description-" + withFreebie.orderId(), """
                {"scope": "WHOLE_ORDER", "reason_code": "OTHER", "description": " "}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        open(carol, withFreebie.orderId(), "unknown-reason-" + withFreebie.orderId(), """
                {"scope": "WHOLE_ORDER", "reason_code": "BAD_MOOD", "description": "Meh."}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unsupported reason code: BAD_MOOD"));
        mockMvc.perform(post("/orders/{id}/after-sales", withFreebie.orderId())
                        .session(carol.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wholeOrder()))
                .andExpect(status().isBadRequest());

        // The order's refunds never exceed its captured payment.
        jdbcTemplate.update("UPDATE order_payments SET amount = 1.00 WHERE order_id = ?", withFreebie.orderId());
        open(carol, withFreebie.orderId(), "over-" + withFreebie.orderId(), wholeOrder())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.message").value("A refund of " + Money.of(whopperPrice, "USD")
                        + " would exceed the 1.00 USD left of the order's captured payment"));
        Assertions.assertEquals(List.of(), caseRepository.findAllByOrderId(withFreebie.orderId()));
    }


    @Test
    void eligibility_shouldNeedACompletedOrderWithACapturedPaymentWithinItsWindow() throws Exception {
        Customer dave = customer("after-sales-eligibility-");

        Long paidOrderId = paidOrder(dave, menuItem(BURGER_KING, 0)).id();
        open(dave, paidOrderId, "paid-" + paidOrderId, wholeOrder())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.message")
                        .value("Only COMPLETED orders can get after-sales; this order is PAID"));

        CompletedOrder late = completedOrder(dave, menuItem(BURGER_KING, 0));
        LocalDateTime completedAt = LocalDateTime.now().minusHours(25).withNano(0);
        jdbcTemplate.update("UPDATE orders SET updated_at = ? WHERE id = ?", completedAt, late.orderId());
        Assertions.assertNull(eligibleOrderOrNull(dave, late.orderId()));
        open(dave, late.orderId(), "late-" + late.orderId(), wholeOrder())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.message")
                        .value("The after-sales window of this order closed at "
                                + AfterSalesErrors.timestamp(completedAt.plusHours(24))));

        CompletedOrder unpaid = completedOrder(dave, menuItem(BURGER_KING, 0));
        jdbcTemplate.update("DELETE FROM order_payments WHERE order_id = ?", unpaid.orderId());
        Assertions.assertNull(eligibleOrderOrNull(dave, unpaid.orderId()));
        open(dave, unpaid.orderId(), "unpaid-" + unpaid.orderId(), wholeOrder())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.message").value("Order has no captured payment"));

        Assertions.assertEquals(List.of(), ids(overview(dave).get("cases")));
    }


    /**
     * The open command picks the attempt number under the order's lock; uq_after_sales_case_attempt refuses a request
     * that would get in without it, and the service maps that refusal by the constraint's name.
     */
    @Test
    void secondRequestOfTheSameAttempt_shouldRunIntoTheAttemptKey() throws Exception {
        Customer erin = customer("after-sales-attempt-key-");
        CompletedOrder order = completedOrder(erin, menuItem(BURGER_KING, 0));
        openWholeOrder(erin, order);

        DuplicateKeyException refused = Assertions.assertThrows(DuplicateKeyException.class,
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    OrderEntity locked = orderRepository.lockById(order.orderId());
                    transitions.open(new AfterSalesTransitions.CustomerRequest(
                            locked,
                            1,
                            AfterSalesReason.OTHER,
                            "Again",
                            List.of(),
                            locked.total(),
                            LocalDateTime.now(),
                            null
                    ));
                }));
        Assertions.assertTrue(
                String.valueOf(refused.getMessage()).toLowerCase(Locale.ROOT).contains("uq_after_sales_case_attempt"),
                refused::getMessage
        );
        Assertions.assertEquals(1, caseRepository.findAllByOrderId(order.orderId()).size());
    }


    // ---- helpers ----


    record Customer(Long id, String email, MockHttpSession session) {
    }


    /**
     * @param itemIds the order lines (order_history_items.id), in their order
     */
    record CompletedOrder(Long orderId, Long orderNo, Money total, LocalDateTime completedAt, List<Long> itemIds) {
    }


    Customer customer(String prefix) throws Exception {
        String email = prefix + System.nanoTime() + "@example.com";
        customerService.signUp(email, "demo123", "After", "Sales");
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


    /**
     * The {@code index}-th menu item of the restaurant, by id.
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


    BigDecimal price(Long menuItemId) {
        return jdbcTemplate.queryForObject("SELECT price FROM menu_items WHERE id = ?", BigDecimal.class, menuItemId)
                .setScale(2);
    }


    /**
     * A paid checkout of the menu items (an item listed twice is one line of quantity two); it records the payment
     * capture.
     */
    OrderDto paidOrder(Customer customer, Long... menuItemIds) {
        for (Long menuItemId : menuItemIds) {
            cartService.addMenuItemToCart(customer.id(), menuItemId);
        }
        return cartService.checkout(customer.id(), "PAID");
    }


    CompletedOrder completedOrder(Customer customer, Long... menuItemIds) {
        OrderDto paid = paidOrder(customer, menuItemIds);
        for (String status : List.of("ACCEPTED", "PREPARING", "COMPLETED")) {
            orderService.transitionOrderStatus(paid.id(), status);
        }
        OrderEntity completed = orderRepository.findById(paid.id()).orElseThrow();
        List<Long> itemIds = jdbcTemplate.queryForList(
                "SELECT id FROM order_history_items WHERE order_id = ? ORDER BY id", Long.class, paid.id());
        return new CompletedOrder(paid.id(), completed.orderNo(), completed.total(), completed.updatedAt(), itemIds);
    }


    /**
     * The order line (order_history_items.id) of the menu item.
     */
    Long lineOf(CompletedOrder order, Long menuItemId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM order_history_items WHERE order_id = ? AND menu_item_id = ?",
                Long.class,
                order.orderId(),
                menuItemId
        );
    }


    ResultActions open(Customer customer, Long orderId, String key, String body) throws Exception {
        return mockMvc.perform(post("/orders/{id}/after-sales", orderId)
                .session(customer.session())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }


    long openWholeOrder(Customer customer, CompletedOrder order) throws Exception {
        return json(open(customer, order.orderId(), "open-" + order.orderId(), wholeOrder())
                .andExpect(status().isOk())).get("id").asLong();
    }


    ResultActions command(Customer customer, long caseId, String command, String key) throws Exception {
        return mockMvc.perform(post("/after-sales/cases/{id}/" + command, caseId)
                .session(customer.session())
                .header("Idempotency-Key", key));
    }


    static String wholeOrder() {
        return """
                {"scope": "WHOLE_ORDER", "reason_code": "MISSING_ITEM", "description": "The fries were missing."}
                """;
    }


    static String items(Long orderItemId, int quantity) {
        return """
                {"scope": "ITEMS", "lines": [{"order_item_id": %d, "quantity": %d}], "reason_code": "QUALITY_ISSUE",
                 "description": "It was cold."}
                """.formatted(orderItemId, quantity);
    }


    /**
     * The Burger King merchant's counter-offer through the merchant's endpoint.
     */
    void merchantOffers(long caseId, String amount) throws Exception {
        merchantAnswers(caseId, "offer", objectMapper.writeValueAsString(
                Map.of("amount", new BigDecimal(amount), "note", "We can refund part.")));
    }


    /**
     * The Burger King merchant's rejection through the merchant's endpoint.
     */
    void merchantRejects(long caseId, String reason) throws Exception {
        merchantAnswers(caseId, "reject", objectMapper.writeValueAsString(Map.of("reason", reason)));
    }


    /**
     * The merchant answers a case of their inbox. Kafka is off, so the case's events first go to the merchant's inbox
     * the way its consumer gets them (each once).
     */
    private void merchantAnswers(long caseId, String command, String body) throws Exception {
        jdbcTemplate.queryForList("""
                        SELECT payload
                        FROM outbox_events
                        WHERE aggregate_type = 'AFTER_SALES_CASE'
                          AND aggregate_id = ?
                        ORDER BY aggregate_sequence
                        """, String.class, caseId)
                .forEach(payload -> merchantInbox.handle(
                        eventContracts.read(EventTopic.AFTER_SALES_EVENTS, payload, AfterSalesEventEnvelope.class)));
        MockHttpSession merchant = (MockHttpSession) mockMvc.perform(post("/login")
                        .param("username", BURGER_KING_MERCHANT)
                        .param("password", "merchant123"))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
        mockMvc.perform(post("/merchant/after-sales/{id}/" + command, caseId)
                        .session(merchant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case_id").value(caseId));
    }


    JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }


    JsonNode overview(Customer customer) throws Exception {
        return json(mockMvc.perform(get("/after-sales/cases").session(customer.session())).andExpect(status().isOk()));
    }


    JsonNode caseJson(Customer customer, long caseId) throws Exception {
        return json(mockMvc.perform(get("/after-sales/cases/{id}", caseId).session(customer.session()))
                .andExpect(status().isOk()));
    }


    JsonNode eligibleOrder(Customer customer, Long orderId) throws Exception {
        JsonNode found = eligibleOrderOrNull(customer, orderId);
        Assertions.assertNotNull(found, "order " + orderId + " is not eligible");
        return found;
    }


    JsonNode eligibleOrderOrNull(Customer customer, Long orderId) throws Exception {
        for (JsonNode eligible : overview(customer).get("eligible_orders")) {
            if (eligible.get("order_id").asLong() == orderId) {
                return eligible;
            }
        }
        return null;
    }


    static List<Long> ids(JsonNode cases) {
        List<Long> ids = new ArrayList<>();
        cases.forEach(found -> ids.add(found.get("id").asLong()));
        return ids;
    }


    /**
     * The case's history as "action|from->to|actor".
     */
    static List<String> historyOf(JsonNode current) {
        List<String> steps = new ArrayList<>();
        current.get("history").forEach(step -> steps.add(step.get("action").asString() + "|"
                + (step.has("from_status") ? step.get("from_status").asString() : "null") + "->"
                + step.get("to_status").asString() + "|" + step.get("actor_type").asString()));
        return steps;
    }


    static void assertDeadlineAbout(LocalDateTime before, Duration timeout, JsonNode deadlineAt) {
        LocalDateTime deadline = LocalDateTime.parse(deadlineAt.asString());
        Assertions.assertFalse(deadline.isBefore(before.plus(timeout).minusSeconds(1)), deadline::toString);
        Assertions.assertFalse(deadline.isAfter(LocalDateTime.now().plus(timeout)), deadline::toString);
    }


    List<String> eventTypes(long caseId) {
        return jdbcTemplate.queryForList("""
                SELECT event_type
                FROM outbox_events
                WHERE aggregate_type = 'AFTER_SALES_CASE'
                  AND aggregate_id = ?
                ORDER BY aggregate_sequence
                """, String.class, caseId);
    }


    List<Long> aggregateSequences(long caseId) {
        return jdbcTemplate.queryForList("""
                SELECT aggregate_sequence
                FROM outbox_events
                WHERE aggregate_type = 'AFTER_SALES_CASE'
                  AND aggregate_id = ?
                ORDER BY aggregate_sequence
                """, Long.class, caseId);
    }


    int refunds(long caseId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refunds WHERE case_id = ?", Integer.class, caseId);
    }


    int idempotencyRows(Long customerId, String scope, String key) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM idempotency_requests
                WHERE customer_id = ?
                  AND scope = ?
                  AND idempotency_key = ?
                """, Integer.class, customerId, scope, key);
    }
}
