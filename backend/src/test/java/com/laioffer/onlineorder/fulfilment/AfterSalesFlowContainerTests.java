package com.laioffer.onlineorder.fulfilment;


import com.laioffer.onlineorder.money.Money;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;


import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * After-sales end to end on PostgreSQL, Redis and Kafka (ADR 0002 section 14, P3), driven through the HTTP API of the
 * customer, the merchant and the admin: an order is paid, accepted, delivered and completed through the fulfilment
 * modules; the customer asks for a refund; the case's events reach the merchant's after-sales inbox through the
 * merchant group and the customer's notifications through the notification group; the merchant (or the platform, or a
 * deadline) settles it; and the after-sales poller pays the refund through the simulated gateway, which fails each
 * refund's first attempt so that every refund also goes through a retry. Covered: approve, counter-offer -> accept,
 * reject -> escalate -> partial arbitration, the merchant's silence -> MERCHANT_TIMEOUT, the automatic refund of a
 * paid order the customer cancels, and withdraw -> second request -> refund.
 *
 * <p>Only the merchant's deadline is short, for the silence test; the others answer far within it. The customer's and
 * the platform's deadlines are long, so that a slow runner never lets a timeout overtake an answer the test gives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class AfterSalesFlowContainerTests extends FulfilmentContainerTestSupport {

    private static final Duration MERCHANT_RESPONSE_TIMEOUT = Duration.ofSeconds(15);

    private static final String BURGER_KING = "Burger King";

    private static final String BURGER_KING_MERCHANT = "merchant.burgerking@laifood.com";

    private static final String AFTER_SALES_TOPIC = "after-sales-events";

    private static final String MERCHANT_AFTER_SALES_GROUP = "onlineorder-merchant-aftersales";

    private static final String OPENED = "aftersales.case_opened";

    private static final String OFFER_MADE = "aftersales.offer_made";

    private static final String REQUEST_REJECTED = "aftersales.request_rejected";

    private static final String CASE_ESCALATED = "aftersales.case_escalated";

    private static final String REFUND_APPROVED = "aftersales.refund_approved";

    private static final String CASE_REFUNDED = "aftersales.case_refunded";

    private static final String CASE_CLOSED = "aftersales.case_closed";

    @Container
    static PostgreSQLContainer POSTGRES = postgres();

    @Container
    static GenericContainer<?> REDIS = redis();

    @Container
    static KafkaContainer KAFKA = kafka();


    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registerInfrastructure(registry, POSTGRES, REDIS, KAFKA);
        // The merchant accepts each order itself, long before an accept timeout could cancel it.
        registry.add("app.fulfilment.accept-timeout", () -> "10m");
        registry.add("app.after-sales.merchant-response-timeout", () -> MERCHANT_RESPONSE_TIMEOUT.toSeconds() + "s");
        registry.add("app.after-sales.customer-response-timeout", () -> "10m");
        registry.add("app.after-sales.arbitration-timeout", () -> "10m");
        registry.add("app.after-sales.poller.enabled", () -> "true");
        registry.add("app.after-sales.poller.interval", () -> "250ms");
        registry.add("app.after-sales.refund-gateway.fail-first-attempts", () -> "1");
        registry.add("app.after-sales.refund.retry-backoff", () -> "500ms");
    }


    @Test
    void requestTheMerchantApproves_shouldBeRefundedByThePollerAndNotified() {
        String customer = signUpAndLogin();
        String burgerKing = login(BURGER_KING_MERCHANT, "merchant123");
        String admin = login("demo@laifood.com", "demo123");
        Long orderId = completedOrder(customer, burgerKing, admin);
        String order = "order #" + orderOf(customer, orderId).get("order_no");
        Money total = money(orderOf(customer, orderId), "total_price");

        Map<?, ?> opened = open(customer, orderId, wholeOrder(), "e2e-open-" + orderId);
        Assertions.assertEquals("PENDING_MERCHANT", opened.get("status"));
        Assertions.assertEquals("CUSTOMER_REQUEST", opened.get("kind"));
        Assertions.assertEquals(1, opened.get("attempt_no"));
        Assertions.assertEquals(total, money(opened, "requested_amount"));
        Long caseId = id(opened);

        // aftersales.case_opened -> merchant after-sales group -> the merchant's inbox, where the merchant answers.
        Map<?, ?> listed = awaitInboxCase(burgerKing, caseId, "PENDING_MERCHANT");
        Assertions.assertEquals(orderId, ((Number) listed.get("order_id")).longValue());
        Assertions.assertEquals(opened.get("order_no"), listed.get("order_no"));
        Assertions.assertEquals(total, money(listed, "requested_amount"));
        Assertions.assertEquals("MISSING_ITEM", listed.get("reason_code"));
        Assertions.assertNotNull(listed.get("deadline_at"));
        ResponseEntity<Map> approved = post("/merchant/after-sales/" + caseId + "/approve", burgerKing);
        Assertions.assertEquals(HttpStatus.OK, approved.getStatusCode(), String.valueOf(approved.getBody()));
        Assertions.assertEquals("REFUNDING", approved.getBody().get("status"));
        Assertions.assertEquals("APPROVED", approved.getBody().get("merchant_decision"));
        Assertions.assertEquals(total, money(approved.getBody(), "approved_amount"));

        // The poller executes the refund: the first gateway call fails, the retry after the backoff succeeds.
        Map<?, ?> refunded = awaitCaseStatus(customer, caseId, "REFUNDED");
        Assertions.assertEquals(total, money(refunded, "refunded_amount"));
        Assertions.assertEquals("MERCHANT_APPROVED", refunded.get("resolution"));
        Assertions.assertEquals(List.of(
                "OPEN|PENDING_MERCHANT|CUSTOMER",
                "MERCHANT_APPROVE|REFUNDING|MERCHANT",
                "REFUND_SUCCEEDED|REFUNDED|SYSTEM"
        ), history(refunded));
        assertRefundSucceeded(refunded, total);

        List<Map<?, ?>> notices = awaitAfterSalesNotifications(customer, orderId,
                List.of(OPENED, REFUND_APPROVED, CASE_REFUNDED));
        Assertions.assertEquals("Refund requested", notices.get(0).get("title"));
        Assertions.assertTrue(String.valueOf(notices.get(0).get("message")).startsWith(
                "Your refund request of " + total + " for " + order + " was sent to the restaurant. If it does not "
                        + "answer by "), () -> String.valueOf(notices.get(0).get("message")));
        assertNotice(notices.get(1), "Refund approved",
                "The restaurant approved your refund request: " + total + " for " + order + " will be refunded.");
        assertNotice(notices.get(2), "Refund completed", total + " for " + order + " was refunded.");

        // The later events bring the merchant's inbox along to the refund.
        Map<?, ?> settled = awaitInboxCase(burgerKing, caseId, "REFUNDED");
        Assertions.assertEquals(total, money(settled, "approved_amount"));
        // The refund changes nothing of the order.
        Assertions.assertEquals("COMPLETED", orderOf(customer, orderId).get("status"));
        Assertions.assertEquals(
                List.of("order.paid", "order.accepted", "order.preparing", "order.completed"), orderEventTypes(orderId));
        Assertions.assertEquals(List.of(OPENED, REFUND_APPROVED, CASE_REFUNDED), caseEventTypes(caseId));
        Assertions.assertTrue(metricCount("onlineorder.aftersales.merchant_decisions", "decision:approved", admin) >= 1);
        Assertions.assertTrue(metricCount("onlineorder.aftersales.refunds", "outcome:retried", admin) >= 1);
        Assertions.assertTrue(metricCount("onlineorder.aftersales.refunds", "outcome:succeeded", admin) >= 1);
    }


    @Test
    void counterOfferTheCustomerAccepts_shouldRefundTheOfferedAmount() {
        String customer = signUpAndLogin();
        String burgerKing = login(BURGER_KING_MERCHANT, "merchant123");
        String admin = login("demo@laifood.com", "demo123");
        Long orderId = completedOrder(customer, burgerKing, admin);
        String order = "order #" + orderOf(customer, orderId).get("order_no");

        Long caseId = id(open(customer, orderId, wholeOrder(), "e2e-open-" + orderId));
        Money requested = money(caseOf(customer, caseId), "requested_amount");
        Money offer = Money.of("2.00", "USD");
        awaitInboxCase(burgerKing, caseId, "PENDING_MERCHANT");
        ResponseEntity<Map> offered = post("/merchant/after-sales/" + caseId + "/offer", burgerKing,
                Map.of("amount", offer.amount(), "note", "The burger was there"), null);
        Assertions.assertEquals(HttpStatus.OK, offered.getStatusCode(), String.valueOf(offered.getBody()));
        Assertions.assertEquals("COUNTER_OFFERED", offered.getBody().get("status"));

        // The customer learns of the offer from the notification, then accepts it.
        Map<?, ?> offerNotice = awaitValue("the offer notification", EVENT_WAIT, () ->
                notificationOf(customer, orderId, OFFER_MADE));
        Assertions.assertEquals("Partial refund offered", offerNotice.get("title"));
        Assertions.assertTrue(String.valueOf(offerNotice.get("message")).startsWith("The restaurant offers " + offer
                        + " instead of the " + requested + " you requested for " + order + ": The burger was there. "
                        + "Reply by "), () -> String.valueOf(offerNotice.get("message")));
        Map<?, ?> counterOffered = caseOf(customer, caseId);
        Assertions.assertEquals("COUNTER_OFFERED", counterOffered.get("status"));
        Assertions.assertEquals(offer, money(counterOffered, "offered_amount"));
        Map<?, ?> accepted = command(customer, caseId, "accept-offer", null);
        Assertions.assertEquals("REFUNDING", accepted.get("status"));
        Assertions.assertEquals("OFFER_ACCEPTED", accepted.get("resolution"));

        Map<?, ?> refunded = awaitCaseStatus(customer, caseId, "REFUNDED");
        Assertions.assertEquals(offer, money(refunded, "approved_amount"));
        Assertions.assertEquals(offer, money(refunded, "refunded_amount"));
        Assertions.assertEquals(List.of(
                "OPEN|PENDING_MERCHANT|CUSTOMER",
                "MERCHANT_OFFER|COUNTER_OFFERED|MERCHANT",
                "CUSTOMER_ACCEPT_OFFER|REFUNDING|CUSTOMER",
                "REFUND_SUCCEEDED|REFUNDED|SYSTEM"
        ), history(refunded));
        assertRefundSucceeded(refunded, offer);

        List<Map<?, ?>> notices = awaitAfterSalesNotifications(customer, orderId,
                List.of(OPENED, OFFER_MADE, REFUND_APPROVED, CASE_REFUNDED));
        assertNotice(notices.get(2), "Refund approved",
                "You accepted the restaurant's offer: " + offer + " for " + order + " will be refunded.");
        assertNotice(notices.get(3), "Refund completed", offer + " for " + order + " was refunded.");
        Map<?, ?> settled = awaitInboxCase(burgerKing, caseId, "REFUNDED");
        Assertions.assertEquals("OFFERED", settled.get("merchant_decision"));
        Assertions.assertEquals(offer, money(settled, "offered_amount"));
        Assertions.assertEquals(offer, money(settled, "approved_amount"));
    }


    @Test
    void rejectionTheCustomerEscalates_shouldBeRefundedInPartByThePlatform() {
        String customer = signUpAndLogin();
        String burgerKing = login(BURGER_KING_MERCHANT, "merchant123");
        String admin = login("demo@laifood.com", "demo123");
        addToCart(customer, lastMenuItemOf(BURGER_KING));
        Long orderId = completedOrder(customer, burgerKing, admin);
        String order = "order #" + orderOf(customer, orderId).get("order_no");
        Map<?, ?> line = lineOf(orderOf(customer, orderId), menuItemOf(BURGER_KING));

        Long caseId = id(open(customer, orderId, items(((Number) line.get("id")).longValue()), "e2e-open-" + orderId));
        Money requested = money(caseOf(customer, caseId), "requested_amount");
        Assertions.assertEquals(money(line, "price"), requested, "one line of two");
        awaitInboxCase(burgerKing, caseId, "PENDING_MERCHANT");
        ResponseEntity<Map> rejected = post("/merchant/after-sales/" + caseId + "/reject", burgerKing,
                Map.of("reason", "The order left complete"), null);
        Assertions.assertEquals(HttpStatus.OK, rejected.getStatusCode(), String.valueOf(rejected.getBody()));
        Assertions.assertEquals("REJECTED", rejected.getBody().get("status"));

        Map<?, ?> rejectedNotice = awaitValue("the rejection notification", EVENT_WAIT, () ->
                notificationOf(customer, orderId, REQUEST_REJECTED));
        Assertions.assertTrue(String.valueOf(rejectedNotice.get("message")).startsWith("The restaurant declined your "
                        + "refund request for " + order + ": The order left complete. You can ask the platform to step "
                        + "in by "), () -> String.valueOf(rejectedNotice.get("message")));
        Map<?, ?> escalated = command(customer, caseId, "escalate", Map.of("note", "The fries were missing"));
        Assertions.assertEquals("IN_ARBITRATION", escalated.get("status"));

        // The case is in the platform's queue; the admin refunds part of it.
        Map<?, ?> queued = findById(getList("/admin/after-sales", admin), "id", caseId);
        Assertions.assertNotNull(queued, "the case in the arbitration queue");
        Assertions.assertEquals("The order left complete", queued.get("merchant_note"));
        Money partial = Money.of("1.50", "USD");
        ResponseEntity<Map> decided = post("/admin/after-sales/" + caseId + "/arbitrate", admin,
                Map.of("decision", "PARTIAL", "amount", partial.amount(), "note", "Half of the fries were missing"),
                "e2e-arbitrate-" + caseId);
        Assertions.assertEquals(HttpStatus.OK, decided.getStatusCode(), String.valueOf(decided.getBody()));
        Assertions.assertEquals("REFUNDING", decided.getBody().get("status"));
        Assertions.assertEquals("ARBITRATION_PARTIAL", decided.getBody().get("resolution"));

        Map<?, ?> refunded = awaitCaseStatus(customer, caseId, "REFUNDED");
        Assertions.assertEquals(requested, money(refunded, "requested_amount"));
        Assertions.assertEquals(partial, money(refunded, "refunded_amount"));
        Assertions.assertEquals("Half of the fries were missing", refunded.get("admin_note"));
        Assertions.assertEquals(List.of(
                "OPEN|PENDING_MERCHANT|CUSTOMER",
                "MERCHANT_REJECT|REJECTED|MERCHANT",
                "CUSTOMER_ESCALATE|IN_ARBITRATION|CUSTOMER",
                "ADMIN_APPROVE_PARTIAL|REFUNDING|ADMIN",
                "REFUND_SUCCEEDED|REFUNDED|SYSTEM"
        ), history(refunded));
        assertRefundSucceeded(refunded, partial);
        Assertions.assertEquals(List.of("after_sales_case.arbitrate"), jdbcTemplate.queryForList("""
                SELECT action
                FROM audit_log
                WHERE target_type = 'AFTER_SALES_CASE'
                  AND target_id = ?
                """, String.class, String.valueOf(caseId)));

        List<Map<?, ?>> notices = awaitAfterSalesNotifications(customer, orderId,
                List.of(OPENED, REQUEST_REJECTED, CASE_ESCALATED, REFUND_APPROVED, CASE_REFUNDED));
        Assertions.assertEquals("Platform review requested", notices.get(2).get("title"));
        assertNotice(notices.get(3), "Refund approved",
                "The platform approved part of your refund request: " + partial + " for " + order + " will be refunded.");
        assertNotice(notices.get(4), "Refund completed", partial + " for " + order + " was refunded.");
        Map<?, ?> settled = awaitInboxCase(burgerKing, caseId, "REFUNDED");
        Assertions.assertEquals("REJECTED", settled.get("merchant_decision"));
        Assertions.assertEquals(partial, money(settled, "approved_amount"));
    }


    @Test
    void requestTheMerchantLeavesUnanswered_shouldBeApprovedByTheMerchantTimeout() {
        String customer = signUpAndLogin();
        String burgerKing = login(BURGER_KING_MERCHANT, "merchant123");
        String admin = login("demo@laifood.com", "demo123");
        Long orderId = completedOrder(customer, burgerKing, admin);
        String order = "order #" + orderOf(customer, orderId).get("order_no");

        Long caseId = id(open(customer, orderId, wholeOrder(), "e2e-open-" + orderId));
        Money requested = money(caseOf(customer, caseId), "requested_amount");
        Assertions.assertNotNull(awaitInboxCase(burgerKing, caseId, "PENDING_MERCHANT").get("deadline_at"));

        // Nobody answers: once the merchant's deadline passes, the poller fires MERCHANT_TIMEOUT and pays the refund.
        Map<?, ?> refunded = awaitValue("the merchant timeout to approve and refund the request",
                MERCHANT_RESPONSE_TIMEOUT.plus(EVENT_WAIT), () -> {
                    Map<?, ?> current = caseOf(customer, caseId);
                    return "REFUNDED".equals(current.get("status")) ? current : null;
                });
        Assertions.assertEquals("MERCHANT_TIMEOUT", refunded.get("resolution"));
        Assertions.assertNull(refunded.get("merchant_decision"));
        Assertions.assertEquals(requested, money(refunded, "approved_amount"));
        Assertions.assertEquals(requested, money(refunded, "refunded_amount"));
        Assertions.assertEquals(List.of(
                "OPEN|PENDING_MERCHANT|CUSTOMER",
                "MERCHANT_TIMEOUT|REFUNDING|SYSTEM",
                "REFUND_SUCCEEDED|REFUNDED|SYSTEM"
        ), history(refunded));
        assertRefundSucceeded(refunded, requested);

        // Too late to answer now.
        ResponseEntity<Map> lateApprove = post("/merchant/after-sales/" + caseId + "/approve", burgerKing);
        Assertions.assertEquals(HttpStatus.CONFLICT, lateApprove.getStatusCode());
        Assertions.assertEquals("AFTER_SALES_STATUS_CONFLICT", lateApprove.getBody().get("code"));
        Assertions.assertEquals("After-sales case is REFUNDED; only PENDING_MERCHANT cases can be approved",
                lateApprove.getBody().get("message"));

        List<Map<?, ?>> notices = awaitAfterSalesNotifications(customer, orderId,
                List.of(OPENED, REFUND_APPROVED, CASE_REFUNDED));
        assertNotice(notices.get(1), "Refund approved", "The restaurant did not answer in time, so your refund "
                + "request was approved: " + requested + " for " + order + " will be refunded.");
        assertNotice(notices.get(2), "Refund completed", requested + " for " + order + " was refunded.");
        Map<?, ?> settled = awaitInboxCase(burgerKing, caseId, "REFUNDED");
        Assertions.assertNull(settled.get("merchant_decision"));
        Assertions.assertEquals(requested, money(settled, "approved_amount"));
        Assertions.assertTrue(metricCount(
                "onlineorder.aftersales.deadlines", "trigger:merchant_timeout&tag=outcome:fired", admin) >= 1);
    }


    /**
     * The customer cancels a paid order after the merchant accepted it and a rider was assigned: the cancel opens the
     * automatic refund of the whole order (ORDER_CANCELLED), which nobody has to answer. The poller pays it, the
     * dispatch module cancels the delivery, and the customer is told about the refund twice: promised with the
     * cancellation, done with the refund. The merchant's after-sales group reads the case's events too, and drops them.
     */
    @Test
    void paidOrderTheCustomerCancels_shouldBeRefundedWholeWithoutAnAnswer() {
        String customer = signUpAndLogin();
        String burgerKing = login(BURGER_KING_MERCHANT, "merchant123");
        String admin = login("demo@laifood.com", "demo123");
        Long orderId = payForItemOf(customer, BURGER_KING, "e2e-cancel-pay-" + System.nanoTime());
        await("the order to reach the Burger King inbox", EVENT_WAIT, () ->
                findById(getList("/merchant/orders?status=PAID", burgerKing), "order_id", orderId) != null);
        Assertions.assertEquals("ACCEPTED", post("/merchant/orders/" + orderId + "/accept", burgerKing).getBody()
                .get("status"));
        assignedDelivery(orderId, admin);
        String order = "order #" + orderOf(customer, orderId).get("order_no");
        Money total = money(orderOf(customer, orderId), "total_price");

        ResponseEntity<Map> cancelled = post("/orders/" + orderId + "/cancel", customer, null,
                "e2e-cancel-" + orderId);
        Assertions.assertEquals(HttpStatus.OK, cancelled.getStatusCode(), String.valueOf(cancelled.getBody()));
        Assertions.assertEquals("CANCELLED", cancelled.getBody().get("status"));
        Assertions.assertEquals("CUSTOMER_CANCELLED", cancelled.getBody().get("cancel_reason"));

        List<?> cases = (List<?>) getMap("/after-sales/cases", customer).get("cases");
        Map<?, ?> automatic = findById(cases, "order_id", orderId);
        Assertions.assertNotNull(automatic, "the automatic refund among the customer's cases");
        Assertions.assertEquals("ORDER_CANCELLED", automatic.get("kind"));
        Long caseId = id(automatic);
        Map<?, ?> refunded = awaitCaseStatus(customer, caseId, "REFUNDED");
        Assertions.assertEquals("ORDER_CANCELLED", refunded.get("resolution"));
        Assertions.assertEquals(total, money(refunded, "requested_amount"));
        Assertions.assertEquals(total, money(refunded, "refunded_amount"));
        Assertions.assertEquals(List.of(
                "ORDER_CANCELLED|REFUNDING|SYSTEM",
                "REFUND_SUCCEEDED|REFUNDED|SYSTEM"
        ), history(refunded));
        assertRefundSucceeded(refunded, total);

        await("the delivery to be cancelled", EVENT_WAIT, () -> "CANCELLED".equals(
                ((Map<?, ?>) getList("/admin/deliveries?order_id=" + orderId, admin).get(0)).get("status")));
        Map<?, ?> cancelNotice = awaitValue("the cancellation notification", EVENT_WAIT, () ->
                notificationOf(customer, orderId, "order.cancelled"));
        assertNotice(cancelNotice, "Order cancelled",
                "Order #" + orderOf(customer, orderId).get("order_no")
                        + " was cancelled. Your payment will be refunded automatically.");
        List<Map<?, ?>> notices = awaitAfterSalesNotifications(customer, orderId, List.of(CASE_REFUNDED));
        assertNotice(notices.get(0), "Refund completed",
                "Your payment of " + total + " for cancelled " + order + " was refunded.");
        Assertions.assertEquals(List.of(OPENED, CASE_REFUNDED), caseEventTypes(caseId));

        // Once the merchant's after-sales group has read the whole topic, the case's events included, the merchant's
        // inbox still knows nothing of it, and the merchant cannot answer it.
        try (Admin kafkaAdmin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            await("the merchant after-sales group to read the whole topic", EVENT_WAIT, () ->
                    readToTheEnd(kafkaAdmin, MERCHANT_AFTER_SALES_GROUP, AFTER_SALES_TOPIC));
        }
        Assertions.assertNull(findById(getList("/merchant/after-sales", burgerKing), "case_id", caseId));
        Assertions.assertEquals(HttpStatus.NOT_FOUND,
                post("/merchant/after-sales/" + caseId + "/approve", burgerKing).getStatusCode());
        await("the merchant's order inbox to show the cancelled order", EVENT_WAIT, () ->
                "CANCELLED".equals(inboxStatus(burgerKing, orderId)));
    }


    /**
     * The customer withdraws the first request and asks once more within the window, with a new key: the second
     * request is attempt 2, the merchant approves it and it is refunded. Each attempt has its own notifications, in
     * the scope of its case.
     */
    @Test
    void withdrawnRequest_shouldLeaveOneMoreAttemptThatIsRefunded() {
        String customer = signUpAndLogin();
        String burgerKing = login(BURGER_KING_MERCHANT, "merchant123");
        String admin = login("demo@laifood.com", "demo123");
        Long orderId = completedOrder(customer, burgerKing, admin);
        String order = "order #" + orderOf(customer, orderId).get("order_no");

        Long firstId = id(open(customer, orderId, wholeOrder(), "e2e-open-first-" + orderId));
        awaitInboxCase(burgerKing, firstId, "PENDING_MERCHANT");
        Map<?, ?> withdrawn = command(customer, firstId, "withdraw", null);
        Assertions.assertEquals("CLOSED", withdrawn.get("status"));
        Assertions.assertEquals("WITHDRAWN", withdrawn.get("close_reason"));
        awaitInboxCase(burgerKing, firstId, "CLOSED");
        ResponseEntity<Map> approveWithdrawn = post("/merchant/after-sales/" + firstId + "/approve", burgerKing);
        Assertions.assertEquals(HttpStatus.CONFLICT, approveWithdrawn.getStatusCode());
        Assertions.assertEquals("AFTER_SALES_STATUS_CONFLICT", approveWithdrawn.getBody().get("code"));

        Map<?, ?> eligible = findById(
                (List<?>) getMap("/after-sales/cases", customer).get("eligible_orders"), "order_id", orderId);
        Assertions.assertNotNull(eligible, "the order is eligible once more");
        Assertions.assertEquals(2, eligible.get("attempt_no"));
        Map<?, ?> second = open(customer, orderId, wholeOrder(), "e2e-open-second-" + orderId);
        Long secondId = id(second);
        Assertions.assertNotEquals(firstId, secondId);
        Assertions.assertEquals(2, second.get("attempt_no"));
        Money requested = money(second, "requested_amount");
        awaitInboxCase(burgerKing, secondId, "PENDING_MERCHANT");
        Assertions.assertEquals(HttpStatus.OK,
                post("/merchant/after-sales/" + secondId + "/approve", burgerKing).getStatusCode());

        Map<?, ?> refunded = awaitCaseStatus(customer, secondId, "REFUNDED");
        Assertions.assertEquals(requested, money(refunded, "refunded_amount"));
        assertRefundSucceeded(refunded, requested);
        Map<?, ?> first = caseOf(customer, firstId);
        Assertions.assertEquals("CLOSED", first.get("status"));
        Assertions.assertNull(first.get("refund"), "the withdrawn request was never refunded");
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refunds WHERE order_id = ?", Integer.class, orderId));

        List<Map<?, ?>> notices = awaitAfterSalesNotifications(customer, orderId,
                List.of(OPENED, CASE_CLOSED, OPENED, REFUND_APPROVED, CASE_REFUNDED));
        assertNotice(notices.get(1), "Refund request closed", "You withdrew your refund request for " + order + ".");
        assertNotice(notices.get(4), "Refund completed", requested + " for " + order + " was refunded.");
        // One notification per event type and case: the second request's opening notified again.
        Map<String, List<String>> byScope = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT dedup_scope, event_type
                FROM order_notifications
                WHERE order_id = ?
                  AND event_type LIKE 'aftersales.%'
                ORDER BY id
                """, row -> {
            byScope.computeIfAbsent(row.getString("dedup_scope"), scope -> new ArrayList<>())
                    .add(row.getString("event_type"));
        }, orderId);
        Assertions.assertEquals(Map.of(
                "case:" + firstId, List.of(OPENED, CASE_CLOSED),
                "case:" + secondId, List.of(OPENED, REFUND_APPROVED, CASE_REFUNDED)
        ), byScope);
        Assertions.assertEquals("REFUNDED", awaitInboxCase(burgerKing, secondId, "REFUNDED").get("status"));
    }


    // ---- helpers ----


    /**
     * A paid Burger King order (with whatever the cart already holds) that the merchant accepts and the admin
     * endpoints deliver: the fulfilment modules complete it through Kafka.
     */
    private Long completedOrder(String customer, String merchant, String admin) {
        Long orderId = payForItemOf(customer, BURGER_KING, "e2e-pay-" + System.nanoTime());
        await("the order to reach the Burger King inbox", EVENT_WAIT, () ->
                findById(getList("/merchant/orders?status=PAID", merchant), "order_id", orderId) != null);
        Assertions.assertEquals("ACCEPTED", post("/merchant/orders/" + orderId + "/accept", merchant).getBody()
                .get("status"));
        Long deliveryId = assignedDelivery(orderId, admin);
        Assertions.assertEquals("PICKED_UP",
                post("/admin/deliveries/" + deliveryId + "/picked-up", admin).getBody().get("status"));
        Assertions.assertEquals("DELIVERED",
                post("/admin/deliveries/" + deliveryId + "/delivered", admin).getBody().get("status"));
        await("the order to be completed", EVENT_WAIT, () ->
                "COMPLETED".equals(orderOf(customer, orderId).get("status")));
        return orderId;
    }


    private Long assignedDelivery(Long orderId, String admin) {
        Map<?, ?> delivery = awaitValue("a rider to be assigned", EVENT_WAIT, () -> {
            List<?> deliveries = getList("/admin/deliveries?order_id=" + orderId, admin);
            return deliveries.isEmpty() ? null : (Map<?, ?>) deliveries.get(0);
        });
        Assertions.assertEquals("ASSIGNED", delivery.get("status"));
        return ((Number) delivery.get("id")).longValue();
    }


    private Long lastMenuItemOf(String restaurantName) {
        return jdbcTemplate.queryForObject("""
                SELECT MAX(menu_items.id)
                FROM menu_items
                JOIN restaurants ON restaurants.id = menu_items.restaurant_id
                WHERE restaurants.name = ?
                """, Long.class, restaurantName);
    }


    private Map<?, ?> open(String customer, Long orderId, Map<String, Object> body, String idempotencyKey) {
        ResponseEntity<Map> opened = post("/orders/" + orderId + "/after-sales", customer, body, idempotencyKey);
        Assertions.assertEquals(HttpStatus.OK, opened.getStatusCode(), String.valueOf(opened.getBody()));
        return opened.getBody();
    }


    private static Map<String, Object> wholeOrder() {
        return Map.of("scope", "WHOLE_ORDER", "reason_code", "MISSING_ITEM", "description", "The fries were missing.");
    }


    private static Map<String, Object> items(Long orderItemId) {
        return Map.of(
                "scope", "ITEMS",
                "lines", List.of(Map.of("order_item_id", orderItemId, "quantity", 1)),
                "reason_code", "QUALITY_ISSUE",
                "description", "The fries were cold."
        );
    }


    /**
     * A customer command on a case, with a key of its own.
     */
    private Map<?, ?> command(String customer, Long caseId, String command, Object body) {
        ResponseEntity<Map> response = post("/after-sales/cases/" + caseId + "/" + command, customer, body,
                "e2e-" + command + "-" + caseId);
        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode(), String.valueOf(response.getBody()));
        return response.getBody();
    }


    private ResponseEntity<Map> post(String path, String session, Object body, String idempotencyKey) {
        HttpHeaders headers = headers(session);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return restTemplate.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }


    private Map<?, ?> getMap(String path, String session) {
        ResponseEntity<Map> response = restTemplate.exchange(
                url(path), HttpMethod.GET, new HttpEntity<>(headers(session)), Map.class);
        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode(), path);
        return response.getBody();
    }


    private Map<?, ?> caseOf(String customer, Long caseId) {
        return getMap("/after-sales/cases/" + caseId, customer);
    }


    private Map<?, ?> awaitCaseStatus(String customer, Long caseId, String status) {
        return awaitValue("case " + caseId + " to be " + status, EVENT_WAIT, () -> {
            Map<?, ?> current = caseOf(customer, caseId);
            return status.equals(current.get("status")) ? current : null;
        });
    }


    /**
     * The case in the merchant's after-sales inbox once the inbox has seen it reach {@code status}.
     */
    private Map<?, ?> awaitInboxCase(String merchant, Long caseId, String status) {
        return awaitValue("case " + caseId + " to be " + status + " in the merchant's inbox", EVENT_WAIT, () -> {
            Map<?, ?> listed = findById(getList("/merchant/after-sales", merchant), "case_id", caseId);
            return listed != null && status.equals(listed.get("status")) ? listed : null;
        });
    }


    /**
     * The customer's after-sales notifications of the order, oldest first, once there are as many as expected.
     */
    private List<Map<?, ?>> awaitAfterSalesNotifications(String customer, Long orderId, List<String> eventTypes) {
        List<Map<?, ?>> notifications = awaitValue("the after-sales notifications " + eventTypes, EVENT_WAIT, () -> {
            List<Map<?, ?>> found = new ArrayList<>();
            // Newest first, so each one goes before the ones found so far.
            for (Object item : getList("/notifications", customer)) {
                Map<?, ?> notification = (Map<?, ?>) item;
                if (String.valueOf(notification.get("event_type")).startsWith("aftersales.")
                        && orderId.equals(((Number) notification.get("order_id")).longValue())) {
                    found.add(0, notification);
                }
            }
            return found.size() >= eventTypes.size() ? found : null;
        });
        Assertions.assertEquals(eventTypes,
                notifications.stream().map(notification -> String.valueOf(notification.get("event_type"))).toList());
        return notifications;
    }


    private static void assertNotice(Map<?, ?> notification, String title, String message) {
        Assertions.assertEquals(title, notification.get("title"));
        Assertions.assertEquals(message, notification.get("message"));
    }


    /**
     * The refund of a REFUNDED case as its customer sees it: paid on the second attempt, after the simulated gateway
     * failed the first, without the gateway's error.
     */
    private static void assertRefundSucceeded(Map<?, ?> refundedCase, Money amount) {
        Map<?, ?> refund = (Map<?, ?>) refundedCase.get("refund");
        Assertions.assertNotNull(refund, "the refund of the case");
        Assertions.assertEquals("SUCCEEDED", refund.get("status"));
        Assertions.assertEquals(amount, money(refund, "amount"));
        Assertions.assertEquals(2, refund.get("attempts"));
        Assertions.assertTrue(String.valueOf(refund.get("provider_reference")).startsWith("SIMREF-"),
                () -> String.valueOf(refund.get("provider_reference")));
        Assertions.assertFalse(refund.containsKey("last_error"), "the gateway's error is for the platform's staff");
        Assertions.assertNotNull(refund.get("succeeded_at"));
    }


    /**
     * "action|to|actor" of every step of the case, oldest first.
     */
    private static List<String> history(Map<?, ?> current) {
        List<String> steps = new ArrayList<>();
        for (Object item : (List<?>) current.get("history")) {
            Map<?, ?> step = (Map<?, ?>) item;
            steps.add(step.get("action") + "|" + step.get("to_status") + "|" + step.get("actor_type"));
        }
        return steps;
    }


    private List<String> caseEventTypes(Long caseId) {
        return jdbcTemplate.queryForList("""
                SELECT event_type
                FROM outbox_events
                WHERE aggregate_type = 'AFTER_SALES_CASE'
                  AND aggregate_id = ?
                ORDER BY aggregate_sequence
                """, String.class, caseId);
    }


    private static Map<?, ?> lineOf(Map<?, ?> order, Long menuItemId) {
        Map<?, ?> line = findById((List<?>) order.get("items"), "menu_item_id", menuItemId);
        Assertions.assertNotNull(line, "the order line of menu item " + menuItemId);
        return line;
    }


    private static Long id(Map<?, ?> body) {
        return ((Number) body.get("id")).longValue();
    }


    /**
     * An amount of a JSON body in the body's currency. The JSON numbers arrive as doubles, whose shortest decimal
     * form is the amount the server wrote.
     */
    private static Money money(Map<?, ?> body, String field) {
        Object amount = body.get(field);
        Assertions.assertNotNull(amount, field);
        return Money.of(new BigDecimal(String.valueOf(amount)), String.valueOf(body.get("currency")));
    }


    /**
     * True once {@code group} has committed, on every partition of {@code topic}, the partition's end: it has read
     * every record the topic holds so far.
     */
    private static boolean readToTheEnd(Admin kafkaAdmin, String group, String topic) {
        try {
            Map<TopicPartition, OffsetAndMetadata> committed = kafkaAdmin.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS);
            TopicDescription description = kafkaAdmin.describeTopics(List.of(topic))
                    .allTopicNames()
                    .get(10, TimeUnit.SECONDS)
                    .get(topic);
            Map<TopicPartition, OffsetSpec> ends = new HashMap<>();
            for (TopicPartitionInfo partition : description.partitions()) {
                ends.put(new TopicPartition(topic, partition.partition()), OffsetSpec.latest());
            }
            Map<TopicPartition, ListOffsetsResultInfo> endOffsets =
                    kafkaAdmin.listOffsets(ends).all().get(10, TimeUnit.SECONDS);
            return endOffsets.entrySet().stream().allMatch(end -> {
                OffsetAndMetadata position = committed.get(end.getKey());
                return end.getValue().offset() == 0 || position != null && position.offset() >= end.getValue().offset();
            });
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading the offsets of " + group, ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not read the offsets of " + group + " on " + topic, ex);
        }
    }
}
