package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.entity.OrderEntity;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * The admin's after-sales API (ADR 0002 D10, D11, sections 5.2, 5.4, 9 and 11.4) on a real database, through HTTP:
 * the arbitration queue and a case with its whole history, the three decisions on an escalated case with their
 * history, refund and event, an Idempotency-Key per decision, the audit entry only of a decision or a retry that
 * changed something, the soft arbitration deadline, the refunds by status with the gateway's error that customers never
 * see, the retry of a FAILED refund only, and that customers, merchants and anonymous callers cannot reach any of it.
 * The cases have to hold on PostgreSQL and on H2 (the {@code h2} profile), so each database gets a subclass. Every test
 * leaves no case due.
 */
abstract class AbstractAfterSalesAdminIntegrationTests {

    static final String BURGER_KING = "Burger King";

    static final String BURGER_KING_MERCHANT = "merchant.burgerking@laifood.com";

    static final String ADMIN_EMAIL = "demo@laifood.com";

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
    RefundRepository refundRepository;

    @Autowired
    PlatformTransactionManager transactionManager;


    /**
     * A partial refund decided once: the case REFUNDING with the amount, the admin's note on the case and in its
     * history (actor ADMIN, the admin's id), a PENDING refund, an aftersales.refund_approved event and one audit entry.
     * The replay with the key answers the same and writes nothing; the key with another request and a new key on the
     * decided case are refused without an entry.
     */
    @Test
    void partialArbitration_shouldRefundTheAmountOncePerKeyAndAuditOnlyTheDecision() throws Exception {
        Customer alice = customer("admin-partial-");
        CompletedOrder order = completedOrder(alice, menuItem(BURGER_KING, 0), menuItem(BURGER_KING, 1));
        long caseId = escalatedCase(alice, order);
        MockHttpSession admin = admin();

        JsonNode escalated = json(mockMvc.perform(get("/admin/after-sales/{id}", caseId).session(admin))
                .andExpect(status().isOk()));
        Assertions.assertEquals("IN_ARBITRATION", escalated.get("status").asString());
        Assertions.assertEquals(String.valueOf(order.orderNo()), escalated.get("order_no").asString());
        Assertions.assertEquals("The order was complete.", escalated.get("merchant_note").asString());
        Assertions.assertEquals(List.of(
                "OPEN|null->PENDING_MERCHANT|CUSTOMER",
                "MERCHANT_REJECT|PENDING_MERCHANT->REJECTED|MERCHANT",
                "CUSTOMER_ESCALATE|REJECTED->IN_ARBITRATION|CUSTOMER"
        ), historyOf(escalated));
        Assertions.assertEquals("Please look at the photos.", escalated.get("history").get(2).get("note").asString());

        String key = "arbitrate-" + caseId;
        String body = arbitration("PARTIAL", "1.25", "  Half of the fries were missing.  ");
        JsonNode decided = json(arbitrate(admin, caseId, key, body).andExpect(status().isOk()));
        Assertions.assertEquals(caseId, decided.get("id").asLong());
        Assertions.assertEquals("REFUNDING", decided.get("status").asString());
        Assertions.assertEquals(new BigDecimal("1.25"), decided.get("approved_amount").decimalValue().setScale(2));
        Assertions.assertEquals("ARBITRATION_PARTIAL", decided.get("resolution").asString());
        Assertions.assertEquals("Half of the fries were missing.", decided.get("admin_note").asString());
        Assertions.assertFalse(decided.has("deadline_at"));
        JsonNode refund = decided.get("refund");
        Assertions.assertEquals("PENDING", refund.get("status").asString());
        Assertions.assertEquals(new BigDecimal("1.25"), refund.get("amount").decimalValue().setScale(2));
        Assertions.assertEquals("USD", refund.get("currency").asString());
        JsonNode step = decided.get("history").get(3);
        Assertions.assertEquals("ADMIN_APPROVE_PARTIAL|IN_ARBITRATION->REFUNDING|ADMIN", historyOf(decided).get(3));
        Assertions.assertEquals("Half of the fries were missing.", step.get("note").asString());
        Assertions.assertEquals(new BigDecimal("1.25"), step.get("amount").decimalValue().setScale(2));
        Assertions.assertEquals(adminId(), jdbcTemplate.queryForObject("""
                SELECT actor_id
                FROM after_sales_case_history
                WHERE case_id = ? AND action = 'ADMIN_APPROVE_PARTIAL'
                """, Long.class, caseId));
        Assertions.assertEquals(List.of("aftersales.case_opened", "aftersales.request_rejected",
                "aftersales.case_escalated", "aftersales.refund_approved"), eventTypes(caseId));
        Assertions.assertEquals(1, refunds(caseId));

        List<Map<String, Object>> entries = auditEntries("AFTER_SALES_CASE", caseId);
        Assertions.assertEquals(1, entries.size());
        Map<String, Object> entry = entries.get(0);
        Assertions.assertEquals("after_sales_case.arbitrate", entry.get("action"));
        Assertions.assertEquals(adminId(), ((Number) entry.get("actor_customer_id")).longValue());
        Assertions.assertEquals("ROLE_ADMIN", entry.get("actor_role"));
        JsonNode details = objectMapper.readTree((String) entry.get("details"));
        Assertions.assertEquals(order.orderId(), details.get("order_id").asLong());
        Assertions.assertEquals("PARTIAL", details.get("decision").asString());
        Assertions.assertEquals(new BigDecimal("1.25"), details.get("amount").decimalValue().setScale(2));
        Assertions.assertEquals("USD", details.get("currency").asString());
        Assertions.assertEquals("IN_ARBITRATION", details.get("from_status").asString());
        Assertions.assertEquals("REFUNDING", details.get("to_status").asString());
        Assertions.assertEquals(key, details.get("idempotency_key").asString());
        Assertions.assertFalse(details.has("note"), "the note stays on the case");

        // The replay, even with the amount written another way, answers with the case its key decided.
        arbitrate(admin, caseId, key, arbitration("PARTIAL", "1.250", "Half of the fries were missing."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(caseId))
                .andExpect(jsonPath("$.status").value("REFUNDING"));
        arbitrate(admin, caseId, key, arbitration("PARTIAL", "1.50", "Half of the fries were missing."))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Idempotency key was already used with a different request"));
        arbitrate(admin, caseId, "arbitrate-again-" + caseId, arbitration("FULL", null, "Everything, after all."))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_STATUS_CONFLICT"))
                .andExpect(jsonPath("$.message")
                        .value("After-sales case is REFUNDING; only IN_ARBITRATION cases can be arbitrated"));

        Assertions.assertEquals(1, auditEntries("AFTER_SALES_CASE", caseId).size());
        Assertions.assertEquals(1, refunds(caseId));
        Assertions.assertEquals(4, eventTypes(caseId).size());
        Assertions.assertEquals(1, idempotencyRows(adminId(), "after-sales-arbitrate:v1:" + caseId, key));
        Assertions.assertEquals(0, idempotencyRows(adminId(), "after-sales-arbitrate:v1:" + caseId,
                "arbitrate-again-" + caseId), "a refused decision keeps no key");
        // The customer sees the decision and its note.
        mockMvc.perform(get("/after-sales/cases/{id}", caseId).session(alice.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDING"))
                .andExpect(jsonPath("$.admin_note").value("Half of the fries were missing."));
    }


    /**
     * FULL refunds the requested amount; REJECT closes the case, which leaves the customer no second request.
     * Arbitrating has no deadline: past it, until the poller fires the timeout, the admin's decision still applies.
     */
    @Test
    void fullAndRejectArbitrations_shouldRefundTheRequestOrCloseTheCaseEvenPastTheDeadline() throws Exception {
        Customer bob = customer("admin-full-reject-");
        MockHttpSession admin = admin();

        CompletedOrder granted = completedOrder(bob, menuItem(BURGER_KING, 1));
        long grantedCase = escalatedCase(bob, granted);
        JsonNode full = json(arbitrate(admin, grantedCase, "full-" + grantedCase,
                arbitration("FULL", null, "The meal never arrived.")).andExpect(status().isOk()));
        Assertions.assertEquals("REFUNDING", full.get("status").asString());
        Assertions.assertEquals("ARBITRATION_FULL", full.get("resolution").asString());
        Assertions.assertEquals(granted.total(), Money.of(full.get("approved_amount").decimalValue(), "USD"));
        Assertions.assertEquals(granted.total(), Money.of(full.get("refund").get("amount").decimalValue(), "USD"));
        JsonNode fullDetails = objectMapper.readTree(
                (String) auditEntries("AFTER_SALES_CASE", grantedCase).get(0).get("details"));
        Assertions.assertEquals("FULL", fullDetails.get("decision").asString());
        Assertions.assertEquals(granted.total(), Money.of(fullDetails.get("amount").decimalValue(), "USD"));

        CompletedOrder refused = completedOrder(bob, menuItem(BURGER_KING, 1));
        long refusedCase = escalatedCase(bob, refused);
        jdbcTemplate.update("UPDATE after_sales_cases SET deadline_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(5), refusedCase);
        JsonNode rejected = json(arbitrate(admin, refusedCase, "reject-" + refusedCase,
                arbitration("REJECT", null, "The photos show a complete order.")).andExpect(status().isOk()));
        Assertions.assertEquals("CLOSED", rejected.get("status").asString());
        Assertions.assertEquals("ARBITRATION_REJECTED", rejected.get("close_reason").asString());
        Assertions.assertEquals("The photos show a complete order.", rejected.get("admin_note").asString());
        Assertions.assertFalse(rejected.has("approved_amount"));
        Assertions.assertFalse(rejected.has("refund"));
        Assertions.assertFalse(rejected.has("deadline_at"));
        Assertions.assertEquals("ADMIN_REJECT|IN_ARBITRATION->CLOSED|ADMIN", historyOf(rejected).get(3));
        Assertions.assertEquals(0, refunds(refusedCase));
        Assertions.assertEquals("aftersales.case_closed", eventTypes(refusedCase).get(3));
        JsonNode rejectDetails = objectMapper.readTree(
                (String) auditEntries("AFTER_SALES_CASE", refusedCase).get(0).get("details"));
        Assertions.assertEquals("REJECT", rejectDetails.get("decision").asString());
        Assertions.assertEquals("CLOSED", rejectDetails.get("to_status").asString());
        Assertions.assertFalse(rejectDetails.has("amount"));
        Assertions.assertFalse(rejectDetails.has("currency"));

        // A rejected case is not a withdrawn one: no withdrawal afterwards, and no second request.
        mockMvc.perform(post("/after-sales/cases/{id}/withdraw", refusedCase)
                        .session(bob.session())
                        .header("Idempotency-Key", "withdraw-" + refusedCase))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_STATUS_CONFLICT"));
        mockMvc.perform(post("/orders/{id}/after-sales", refused.orderId())
                        .session(bob.session())
                        .header("Idempotency-Key", "reopen-" + refused.orderId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wholeOrder()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.message").value("This order cannot be submitted for after-sales again"));
    }


    /**
     * Every malformed or refused arbitration answers before or without a step: the case stays IN_ARBITRATION at its
     * version, with no refund, no event, no key and no audit entry.
     */
    @Test
    void refusedArbitrations_shouldChangeNothingAndAuditNothing() throws Exception {
        Customer carol = customer("admin-refused-");
        CompletedOrder order = completedOrder(carol, menuItem(BURGER_KING, 1));
        long caseId = escalatedCase(carol, order);
        MockHttpSession admin = admin();
        String requested = order.total().toString();

        arbitrate(admin, caseId, "maybe", arbitration("MAYBE", null, "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Decision must be FULL, PARTIAL or REJECT"));
        arbitrate(admin, caseId, "full-amount", arbitration("FULL", "1.00", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A FULL decision takes no amount"));
        arbitrate(admin, caseId, "reject-amount", arbitration("REJECT", "1.00", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A REJECT decision takes no amount"));
        arbitrate(admin, caseId, "partial-none", arbitration("PARTIAL", null, "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A PARTIAL decision needs an amount"));
        arbitrate(admin, caseId, "partial-cents", arbitration("PARTIAL", "1.005", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Amount must have at most 2 decimal places"));
        for (String amount : List.of("0", "-1.00", order.total().amount().toPlainString())) {
            arbitrate(admin, caseId, "partial-" + amount, arbitration("PARTIAL", amount, "x"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.message")
                            .value("The amount must be more than 0 and less than the requested " + requested));
        }
        arbitrate(admin, caseId, "blank-note", arbitration("FULL", null, " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        arbitrate(admin, caseId, "long-note", arbitration("FULL", null, "x".repeat(1001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/admin/after-sales/{id}/arbitrate", caseId)
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arbitration("FULL", null, "x")))
                .andExpect(status().isBadRequest());
        arbitrate(admin, 987_654_321L, "missing", arbitration("FULL", null, "x"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("After-sales case not found"));
        CompletedOrder waiting = completedOrder(carol, menuItem(BURGER_KING, 1));
        long waitingCase = openWholeOrder(carol, waiting);
        arbitrate(admin, waitingCase, "too-early", arbitration("FULL", null, "x"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_STATUS_CONFLICT"))
                .andExpect(jsonPath("$.message")
                        .value("After-sales case is PENDING_MERCHANT; only IN_ARBITRATION cases can be arbitrated"));

        AfterSalesCaseEntity after = caseRepository.findById(caseId).orElseThrow();
        Assertions.assertEquals("IN_ARBITRATION", after.status());
        Assertions.assertEquals(2L, after.version(), "the merchant's and the customer's steps only");
        Assertions.assertEquals(0, refunds(caseId));
        Assertions.assertEquals(3, eventTypes(caseId).size());
        Assertions.assertEquals("PENDING_MERCHANT", caseRepository.findById(waitingCase).orElseThrow().status());
        Assertions.assertEquals(List.of(), auditEntries("AFTER_SALES_CASE", caseId));
        Assertions.assertEquals(List.of(), auditEntries("AFTER_SALES_CASE", waitingCase));
        Assertions.assertEquals(List.of(), auditEntries("AFTER_SALES_CASE", 987_654_321L));
        Assertions.assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM idempotency_requests
                WHERE customer_id = ? AND scope IN (?, ?, ?)
                """, Integer.class, adminId(), "after-sales-arbitrate:v1:" + caseId,
                "after-sales-arbitrate:v1:" + waitingCase, "after-sales-arbitrate:v1:987654321"));
    }


    /**
     * The queue lists one status, the arbitration queue by default and the nearest deadline first; a status without a
     * deadline lists the newest case first. The list carries no history.
     */
    @Test
    void queue_shouldListOneStatusWithTheNearestDeadlineFirst() throws Exception {
        Customer dave = customer("admin-queue-");
        MockHttpSession admin = admin();
        long later = escalatedCase(dave, completedOrder(dave, menuItem(BURGER_KING, 0)));
        long sooner = escalatedCase(dave, completedOrder(dave, menuItem(BURGER_KING, 1)));
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("UPDATE after_sales_cases SET deadline_at = ? WHERE id = ?", now.plusHours(2), later);
        jdbcTemplate.update("UPDATE after_sales_cases SET deadline_at = ? WHERE id = ?", now.plusHours(1), sooner);

        JsonNode queue = json(mockMvc.perform(get("/admin/after-sales").param("limit", "100").session(admin))
                .andExpect(status().isOk()));
        List<Long> ids = ids(queue);
        Assertions.assertTrue(ids.indexOf(sooner) >= 0 && ids.indexOf(sooner) < ids.indexOf(later), ids::toString);
        queue.forEach(listed -> Assertions.assertEquals("IN_ARBITRATION", listed.get("status").asString()));
        Assertions.assertFalse(queue.get(0).has("history"), "lists carry no history");
        LocalDateTime previous = LocalDateTime.MIN;
        for (JsonNode listed : queue) {
            LocalDateTime deadline = LocalDateTime.parse(listed.get("deadline_at").asString());
            Assertions.assertFalse(deadline.isBefore(previous), "nearest deadline first: " + queue);
            previous = deadline;
        }
        mockMvc.perform(get("/admin/after-sales").param("limit", "1").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        for (long caseId : List.of(later, sooner)) {
            mockMvc.perform(post("/after-sales/cases/{id}/withdraw", caseId)
                            .session(dave.session())
                            .header("Idempotency-Key", "withdraw-" + caseId))
                    .andExpect(status().isOk());
        }
        JsonNode closed = json(mockMvc.perform(get("/admin/after-sales")
                        .param("status", "CLOSED").param("limit", "2").session(admin))
                .andExpect(status().isOk()));
        Assertions.assertEquals(List.of(sooner, later), ids(closed), "the newest case first");
        Assertions.assertEquals("WITHDRAWN", closed.get(0).get("close_reason").asString());
        Assertions.assertFalse(ids(json(mockMvc.perform(get("/admin/after-sales").param("limit", "100")
                .session(admin)).andExpect(status().isOk()))).contains(sooner));
        mockMvc.perform(get("/admin/after-sales").param("status", "ESCALATED").session(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Unsupported after-sales status: ESCALATED"));
        mockMvc.perform(get("/admin/after-sales/{id}", 987_654_321L).session(admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("After-sales case not found"));
    }


    /**
     * A FAILED refund is listed for the staff with the gateway's error, which the customer never sees, and an admin
     * retry makes it due now with its attempts starting over, audited once; a repeat answers PENDING without an entry,
     * and a refund in flight or paid cannot be retried.
     */
    @Test
    void refundRetry_shouldRestartOnlyAFailedRefundAndAuditOnlyThatRetry() throws Exception {
        Customer erin = customer("admin-retry-");
        MockHttpSession admin = admin();
        CompletedOrder order = completedOrder(erin, menuItem(BURGER_KING, 1));
        long caseId = escalatedCase(erin, order);
        arbitrate(admin, caseId, "full-" + caseId, arbitration("FULL", null, "Refund it.")).andExpect(status().isOk());
        long refundId = givenUp(caseId, "simulated gateway timeout");

        JsonNode failed = refundIn(json(mockMvc.perform(get("/admin/refunds").session(admin))
                .andExpect(status().isOk())), refundId);
        Assertions.assertEquals("FAILED", failed.get("status").asString());
        Assertions.assertEquals(8, failed.get("attempts").asInt());
        Assertions.assertEquals("simulated gateway timeout", failed.get("last_error").asString());
        Assertions.assertEquals(caseId, failed.get("case_id").asLong());
        Assertions.assertEquals(String.valueOf(order.orderNo()), failed.get("order_no").asString());
        Assertions.assertEquals("simulated gateway timeout", json(mockMvc.perform(
                        get("/admin/after-sales/{id}", caseId).session(admin)).andExpect(status().isOk()))
                .get("refund").get("last_error").asString());
        JsonNode customerView = json(mockMvc.perform(get("/after-sales/cases/{id}", caseId).session(erin.session()))
                .andExpect(status().isOk()));
        Assertions.assertEquals("FAILED", customerView.get("refund").get("status").asString());
        Assertions.assertFalse(customerView.get("refund").has("last_error"), "the gateway's error is the staff's");
        mockMvc.perform(get("/admin/refunds").param("status", "GIVEN_UP").session(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unsupported refund status: GIVEN_UP"));

        LocalDateTime beforeRetry = LocalDateTime.now().minusSeconds(1);
        JsonNode retried = json(mockMvc.perform(post("/admin/refunds/{id}/retry", refundId).session(admin))
                .andExpect(status().isOk()));
        Assertions.assertEquals(refundId, retried.get("id").asLong());
        Assertions.assertEquals("PENDING", retried.get("status").asString());
        Assertions.assertEquals(0, retried.get("attempts").asInt());
        Assertions.assertEquals("simulated gateway timeout", retried.get("last_error").asString());
        LocalDateTime nextAttemptAt = LocalDateTime.parse(retried.get("next_attempt_at").asString());
        Assertions.assertFalse(nextAttemptAt.isBefore(beforeRetry), nextAttemptAt::toString);
        Assertions.assertFalse(nextAttemptAt.isAfter(LocalDateTime.now()), nextAttemptAt::toString);
        RefundEntity pending = refundRepository.findById(refundId).orElseThrow();
        Assertions.assertNull(pending.claimedUntil());
        Assertions.assertEquals("REFUNDING", caseRepository.findById(caseId).orElseThrow().status());

        List<Map<String, Object>> entries = auditEntries("REFUND", refundId);
        Assertions.assertEquals(1, entries.size());
        Assertions.assertEquals("refund.retry", entries.get(0).get("action"));
        Assertions.assertEquals(adminId(), ((Number) entries.get(0).get("actor_customer_id")).longValue());
        JsonNode details = objectMapper.readTree((String) entries.get(0).get("details"));
        Assertions.assertEquals(caseId, details.get("case_id").asLong());
        Assertions.assertEquals(order.orderId(), details.get("order_id").asLong());
        Assertions.assertEquals(8, details.get("previous_attempts").asInt());
        Assertions.assertEquals("simulated gateway timeout", details.get("last_error").asString());

        // A repeated retry finds it PENDING: answered as it is, not audited again.
        mockMvc.perform(post("/admin/refunds/{id}/retry", refundId).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0));
        Assertions.assertEquals(pending.version(), refundRepository.findById(refundId).orElseThrow().version());
        Assertions.assertTrue(ids(json(mockMvc.perform(get("/admin/refunds").param("status", "PENDING")
                .param("limit", "100").session(admin)).andExpect(status().isOk()))).contains(refundId));

        jdbcTemplate.update("""
                UPDATE refunds
                SET status = 'PROCESSING', attempts = 1, next_attempt_at = NULL, claimed_until = ?,
                    version = version + 1
                WHERE id = ?
                """, LocalDateTime.now().plusMinutes(1), refundId);
        mockMvc.perform(post("/admin/refunds/{id}/retry", refundId).session(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_STATUS_CONFLICT"))
                .andExpect(jsonPath("$.message").value("Refund is PROCESSING; only FAILED refunds can be retried"));
        jdbcTemplate.update("""
                UPDATE refunds
                SET status = 'SUCCEEDED', claimed_until = NULL, provider_reference = 'SIMREF-test',
                    succeeded_at = ?, version = version + 1
                WHERE id = ?
                """, LocalDateTime.now(), refundId);
        mockMvc.perform(post("/admin/refunds/{id}/retry", refundId).session(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_STATUS_CONFLICT"))
                .andExpect(jsonPath("$.message").value("Refund is SUCCEEDED; only FAILED refunds can be retried"));
        mockMvc.perform(post("/admin/refunds/{id}/retry", 987_654_321L).session(admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Refund not found"));

        Assertions.assertEquals(1, auditEntries("REFUND", refundId).size());
        Assertions.assertEquals(List.of(), auditEntries("REFUND", 987_654_321L));
        Assertions.assertEquals("SUCCEEDED", refundRepository.findById(refundId).orElseThrow().status());
    }


    /**
     * There is no blanket /admin rule: each path has its own ROLE_ADMIN matcher. A customer and a merchant are 403
     * and an anonymous caller 401 on every one, and a refused command writes nothing.
     */
    @Test
    void adminAfterSalesEndpoints_shouldBeForbiddenToCustomersAndMerchantsAndNeedALogin() throws Exception {
        Customer frank = customer("admin-roles-");
        long caseId = escalatedCase(frank, completedOrder(frank, menuItem(BURGER_KING, 1)));
        MockHttpSession merchant = login(BURGER_KING_MERCHANT, "merchant123");
        List<Supplier<MockHttpServletRequestBuilder>> requests = List.of(
                () -> get("/admin/after-sales"),
                () -> get("/admin/after-sales").param("status", "CLOSED"),
                () -> get("/admin/after-sales/{id}", caseId),
                () -> post("/admin/after-sales/{id}/arbitrate", caseId)
                        .header("Idempotency-Key", "roles-" + caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arbitration("FULL", null, "Not mine to decide.")),
                () -> get("/admin/refunds"),
                () -> post("/admin/refunds/{id}/retry", 987_654_321L)
        );

        for (Supplier<MockHttpServletRequestBuilder> request : requests) {
            mockMvc.perform(request.get())
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
            for (MockHttpSession session : List.of(frank.session(), merchant)) {
                mockMvc.perform(request.get().session(session))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            }
        }

        AfterSalesCaseEntity untouched = caseRepository.findById(caseId).orElseThrow();
        Assertions.assertEquals("IN_ARBITRATION", untouched.status());
        Assertions.assertEquals(2L, untouched.version());
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_requests WHERE scope = ?", Integer.class,
                "after-sales-arbitrate:v1:" + caseId));
        Assertions.assertEquals(List.of(), auditEntries("AFTER_SALES_CASE", caseId));
        mockMvc.perform(get("/admin/after-sales/{id}", caseId).session(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_ARBITRATION"));
    }


    // ---- helpers ----


    record Customer(Long id, String email, MockHttpSession session) {
    }


    record CompletedOrder(Long orderId, Long orderNo, Money total) {
    }


    Customer customer(String prefix) throws Exception {
        String email = prefix + System.nanoTime() + "@example.com";
        customerService.signUp(email, "demo123", "After", "Sales");
        Long id = jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
        return new Customer(id, email, login(email, "demo123"));
    }


    MockHttpSession admin() throws Exception {
        return login(ADMIN_EMAIL, "demo123");
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


    long adminId() {
        return jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, ADMIN_EMAIL);
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
     * A paid checkout of the menu items, completed: it has a captured payment and can get a request.
     */
    CompletedOrder completedOrder(Customer customer, Long... menuItemIds) {
        for (Long menuItemId : menuItemIds) {
            cartService.addMenuItemToCart(customer.id(), menuItemId);
        }
        OrderDto paid = cartService.checkout(customer.id(), "PAID");
        for (String status : List.of("ACCEPTED", "PREPARING", "COMPLETED")) {
            orderService.transitionOrderStatus(paid.id(), status);
        }
        OrderEntity completed = orderRepository.findById(paid.id()).orElseThrow();
        return new CompletedOrder(paid.id(), completed.orderNo(), completed.total());
    }


    long openWholeOrder(Customer customer, CompletedOrder order) throws Exception {
        return json(mockMvc.perform(post("/orders/{id}/after-sales", order.orderId())
                        .session(customer.session())
                        .header("Idempotency-Key", "open-" + order.orderId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wholeOrder()))
                .andExpect(status().isOk())).get("id").asLong();
    }


    /**
     * A whole-order request the merchant rejected and the customer escalated: IN_ARBITRATION at version 2, with the
     * platform's deadline.
     */
    long escalatedCase(Customer customer, CompletedOrder order) throws Exception {
        long caseId = openWholeOrder(customer, order);
        Long merchantId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE email = ?", Long.class, BURGER_KING_MERCHANT);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            AfterSalesTransitions.Step step = transitions.fire(caseRepository.lockById(caseId),
                    new AfterSalesTransitions.Command(AfterSalesTrigger.MERCHANT_REJECT, AfterSalesActor.MERCHANT,
                            merchantId, null, "The order was complete.", null));
            Assertions.assertTrue(step.applied(), () -> step.decision().toString());
        });
        mockMvc.perform(post("/after-sales/cases/{id}/escalate", caseId)
                        .session(customer.session())
                        .header("Idempotency-Key", "escalate-" + caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\": \"Please look at the photos.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_ARBITRATION"));
        return caseId;
    }


    /**
     * The refund of the case as the executor leaves it when it gives up after its attempts: FAILED with the gateway's
     * error, neither due nor leased.
     */
    long givenUp(long caseId, String lastError) {
        jdbcTemplate.update("""
                UPDATE refunds
                SET status = 'FAILED', attempts = 8, next_attempt_at = NULL, claimed_until = NULL, last_error = ?,
                    version = version + 1, updated_at = ?
                WHERE case_id = ?
                """, lastError, LocalDateTime.now(), caseId);
        return jdbcTemplate.queryForObject("SELECT id FROM refunds WHERE case_id = ?", Long.class, caseId);
    }


    static String wholeOrder() {
        return """
                {"scope": "WHOLE_ORDER", "reason_code": "MISSING_ITEM", "description": "The fries were missing."}
                """;
    }


    String arbitration(String decision, String amount, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", decision);
        if (amount != null) {
            body.put("amount", new BigDecimal(amount));
        }
        body.put("note", note);
        return objectMapper.writeValueAsString(body);
    }


    ResultActions arbitrate(MockHttpSession admin, long caseId, String key, String body) throws Exception {
        return mockMvc.perform(post("/admin/after-sales/{id}/arbitrate", caseId)
                .session(admin)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }


    JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }


    static List<Long> ids(JsonNode list) {
        List<Long> ids = new ArrayList<>();
        list.forEach(found -> ids.add(found.get("id").asLong()));
        return ids;
    }


    static JsonNode refundIn(JsonNode refunds, long refundId) {
        for (JsonNode refund : refunds) {
            if (refund.get("id").asLong() == refundId) {
                return refund;
            }
        }
        throw new AssertionError("Refund " + refundId + " is not listed: " + refunds);
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


    List<String> eventTypes(long caseId) {
        return jdbcTemplate.queryForList("""
                SELECT event_type
                FROM outbox_events
                WHERE aggregate_type = 'AFTER_SALES_CASE'
                  AND aggregate_id = ?
                ORDER BY aggregate_sequence
                """, String.class, caseId);
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


    List<Map<String, Object>> auditEntries(String targetType, long targetId) {
        return jdbcTemplate.queryForList("""
                SELECT actor_customer_id, actor_role, action, details
                FROM audit_log
                WHERE target_type = ? AND target_id = ?
                ORDER BY id
                """, targetType, String.valueOf(targetId));
    }
}
