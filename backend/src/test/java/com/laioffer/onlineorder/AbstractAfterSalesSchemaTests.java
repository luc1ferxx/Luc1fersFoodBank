package com.laioffer.onlineorder;


import com.laioffer.onlineorder.aftersales.AfterSalesCaseEntity;
import com.laioffer.onlineorder.aftersales.AfterSalesCaseHistoryEntity;
import com.laioffer.onlineorder.aftersales.AfterSalesCaseHistoryRepository;
import com.laioffer.onlineorder.aftersales.AfterSalesCaseLineEntity;
import com.laioffer.onlineorder.aftersales.AfterSalesCaseLineRepository;
import com.laioffer.onlineorder.aftersales.AfterSalesCaseRepository;
import com.laioffer.onlineorder.aftersales.RefundEntity;
import com.laioffer.onlineorder.aftersales.RefundRepository;
import com.laioffer.onlineorder.entity.OrderPaymentEntity;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantAfterSalesEntity;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantAfterSalesRepository;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.repository.OrderNotificationRepository;
import com.laioffer.onlineorder.repository.OrderPaymentRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;


import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;


/**
 * The V12 tables (ADR 0002 sections 8, 19 and 20) on a real database: every check, unique key and foreign key refuses
 * the rows it exists for, refunds are tied to their case's approved amount, financial rows cannot be deleted from
 * under their refunds, and order notifications deduplicate per order, event type and scope. Also maps every V12 entity
 * through its repository, with the version checks and the lock, claim and page queries. The cases have to hold on
 * PostgreSQL and on H2 (the {@code h2} profile), so each database gets a subclass.
 */
abstract class AbstractAfterSalesSchemaTests {

    /**
     * Before every timestamp the other tests write, so that the claim tests only see their own rows.
     */
    private static final LocalDateTime LONG_AGO = LocalDateTime.of(2000, 1, 1, 12, 0);

    private static final AtomicLong ORDER_NUMBERS = new AtomicLong(8_000_000_000_000_000_000L + System.nanoTime() % 1_000_000_000L);

    private static final AtomicLong RESTAURANT_IDS = new AtomicLong(5_000_000_000L + System.nanoTime() % 1_000_000L);

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    private AfterSalesCaseRepository caseRepository;

    @Autowired
    private AfterSalesCaseLineRepository lineRepository;

    @Autowired
    private AfterSalesCaseHistoryRepository historyRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private MerchantAfterSalesRepository merchantAfterSalesRepository;

    @Autowired
    private OrderPaymentRepository orderPaymentRepository;

    @Autowired
    private OrderNotificationRepository orderNotificationRepository;


    /**
     * Whether this database parses {@code INSERT ... ON CONFLICT (columns) DO NOTHING}, the notification insert. H2
     * only parses the target-less form; the notification consumers do not run in the h2 profile.
     */
    boolean parsesOnConflictTarget() {
        return true;
    }


    @Test
    void orderPayments_shouldKeepOneCapturedNonNegativePaymentPerOrderThatOutlivesNoOrder() {
        Order order = order("COMPLETED", "20.00");
        OrderPaymentEntity payment = orderPaymentRepository.save(new OrderPaymentEntity(null, order.id(),
                order.customerId(), new BigDecimal("20.00"), "USD", OrderPaymentEntity.CAPTURED,
                "SIMPAY-schema-" + order.id(), LocalDateTime.now().withNano(0)));

        Assertions.assertNotNull(payment.id());
        OrderPaymentEntity stored = orderPaymentRepository.findByOrderId(order.id()).orElseThrow();
        Assertions.assertEquals(payment, stored);
        Assertions.assertEquals(Money.of("20.00", "USD"), stored.captured());
        Assertions.assertTrue(orderPaymentRepository.findByOrderId(order.id() + 1_000_000L).isEmpty());

        Order other = order("COMPLETED", "20.00");
        Assertions.assertDoesNotThrow(() -> insert("order_payments", payment(other)));
        assertRejected("a second payment of an order", () -> insert("order_payments", payment(order)));
        Order fresh = order("COMPLETED", "20.00");
        assertRejected("a negative amount", () -> insert("order_payments", with(payment(fresh), "amount", "-0.01")));
        assertRejected("a status other than CAPTURED",
                () -> insert("order_payments", with(payment(fresh), "status", "REFUNDED")));
        assertRejected("a lower-case currency", () -> insert("order_payments", with(payment(fresh), "currency", "usd")));
        assertRejected("a missing order", () -> insert("order_payments", with(payment(fresh), "order_id", 987_654_321_987L)));
        assertRejected("a provider reference of another payment", () -> insert("order_payments",
                with(payment(fresh), "provider_reference", "SIMPAY-schema-" + order.id())));
        Assertions.assertDoesNotThrow(() -> insert("order_payments", with(payment(fresh), "amount", "0.00")));

        assertRejected("deleting an order with a payment", () -> deleteOrder(order.id()));
    }


    @Test
    void afterSalesCases_shouldRefuseEveryViolationOfTheirChecks() {
        Order order = order("COMPLETED", "20.00");

        Map<String, Map<String, Object>> violations = new LinkedHashMap<>();
        violations.put("an unknown kind", with(pending(order), "kind", "GOODWILL"));
        violations.put("a third attempt", with(pending(order), "attempt_no", 3));
        violations.put("attempt zero", with(pending(order), "attempt_no", 0));
        violations.put("an ORDER_CANCELLED second attempt", with(cancelled(order), "attempt_no", 2));
        violations.put("an unknown status", with(pending(order), "status", "OPEN"));
        violations.put("a request without a restaurant", with(pending(order), "restaurant_id", null));
        violations.put("a request without a reason", with(pending(order), "reason_code", null));
        violations.put("a request without a description", with(pending(order), "description", null));
        violations.put("an unknown reason", with(pending(order), "reason_code", "BAD_MOOD"));
        violations.put("a negative order total", with(pending(order), "order_total", "-1.00"));
        violations.put("a zero request", with(pending(order), "requested_amount", "0.00"));
        violations.put("a request above the order total", with(pending(order), "requested_amount", "20.01"));
        violations.put("an offer of the whole request", with(offered(order), "offered_amount", "5.00"));
        violations.put("a zero offer", with(offered(order), "offered_amount", "0.00"));
        violations.put("an approval above the request", with(refunding(order), "approved_amount", "5.01"));
        violations.put("a zero approval", with(refunding(order), "approved_amount", "0.00"));
        violations.put("REFUNDING without an approved amount", with(refunding(order), "approved_amount", null));
        violations.put("REFUNDED without a refunded amount", with(refunded(order), "refunded_amount", null));
        violations.put("a refunded amount other than the approved one",
                with(refunded(order), "refunded_amount", "4.00"));
        violations.put("a refunded amount before REFUNDED", with(refunding(order), "refunded_amount", "5.00"));
        violations.put("an open status without a deadline", with(pending(order), "deadline_at", null));
        violations.put("REFUNDING with a deadline", with(refunding(order), "deadline_at", LocalDateTime.now()));
        violations.put("CLOSED with a deadline", with(closed(order), "deadline_at", LocalDateTime.now()));
        violations.put("an unknown merchant decision", with(pending(order), "merchant_decision", "MAYBE"));
        violations.put("an unknown resolution", with(refunding(order), "resolution", "GOODWILL"));
        violations.put("an unknown close reason", with(closed(order), "close_reason", "FORGOTTEN"));
        violations.put("CLOSED without a close reason", with(closed(order), "close_reason", null));
        violations.put("a close reason on an open case", with(pending(order), "close_reason", "WITHDRAWN"));
        violations.put("a lower-case currency", with(pending(order), "currency", "usd"));
        violations.put("a currency of digits", with(pending(order), "currency", "U5D"));
        violations.put("a missing order", with(pending(order), "order_id", 987_654_321_987L));
        for (Map.Entry<String, Map<String, Object>> violation : violations.entrySet()) {
            assertRejected(violation.getKey(), () -> insert("after_sales_cases", violation.getValue()));
        }

        // Each valid shape is accepted, one order apiece (an order has one case per kind and attempt).
        for (Map<String, Object> valid : List.of(pending(order("COMPLETED", "20.00")),
                offered(order("COMPLETED", "20.00")), refunding(order("COMPLETED", "20.00")),
                refunded(order("COMPLETED", "20.00")), closed(order("COMPLETED", "20.00")),
                cancelled(order("CANCELLED", "20.00")), with(cancelled(order("CANCELLED", "20.00")), "restaurant_id", null),
                with(pending(order("COMPLETED", "20.00")), "requested_amount", "20.00"))) {
            Assertions.assertDoesNotThrow(() -> insert("after_sales_cases", valid), valid.toString());
        }
    }


    @Test
    void afterSalesCases_shouldAllowOneRequestPerAttemptAndOnlyTwoAttempts() {
        Order order = order("COMPLETED", "20.00");
        insert("after_sales_cases", closed(order));

        assertRejected("a second first attempt", () -> insert("after_sales_cases", pending(order)));
        long second = insert("after_sales_cases", with(pending(order), "attempt_no", 2));
        assertRejected("a second second attempt", () -> insert("after_sales_cases", with(pending(order), "attempt_no", 2)));
        // The kinds are separate keys; the service keeps cancelled and completed orders apart.
        long cancelled = insert("after_sales_cases", with(cancelled(order), "restaurant_id", order.restaurantId()));

        Assertions.assertEquals(List.of(second, cancelled), caseRepository.findAllByOrderId(order.id()).stream()
                .map(AfterSalesCaseEntity::id).toList().subList(1, 3));
        Assertions.assertEquals(List.of(1, 2, 1), caseRepository.findAllByOrderId(order.id()).stream()
                .map(AfterSalesCaseEntity::attemptNo).toList());
        assertRejected("deleting an order with cases", () -> deleteOrder(order.id()));
    }


    @Test
    void caseLinesAndHistory_shouldRefuseInvalidRowsAndGoWithTheirCase() {
        Order order = order("COMPLETED", "20.00");
        long orderItem = orderItem(order.id(), "2.50", 4);
        long caseId = insert("after_sales_cases", pending(order));

        Map<String, Map<String, Object>> violations = new LinkedHashMap<>();
        violations.put("a zero quantity", with(with(line(caseId, orderItem), "quantity", 0), "amount", "0.00"));
        violations.put("a negative unit price", with(with(line(caseId, orderItem), "unit_price", "-2.50"),
                "amount", "-5.00"));
        violations.put("an amount other than unit price x quantity", with(line(caseId, orderItem), "amount", "5.01"));
        violations.put("a missing order line", with(line(caseId, orderItem), "order_item_id", 987_654_321_987L));
        violations.put("a missing case", with(line(caseId, orderItem), "case_id", 987_654_321_987L));
        violations.put("a line currency of mixed case", with(line(caseId, orderItem), "currency", "Usd"));
        for (Map.Entry<String, Map<String, Object>> violation : violations.entrySet()) {
            assertRejected(violation.getKey(), () -> insert("after_sales_case_lines", violation.getValue()));
        }
        insert("after_sales_case_lines", line(caseId, orderItem));
        assertRejected("a second line of one order line", () -> insert("after_sales_case_lines", line(caseId, orderItem)));

        Map<String, Map<String, Object>> historyViolations = new LinkedHashMap<>();
        historyViolations.put("an unknown actor", with(history(caseId), "actor_type", "ROBOT"));
        historyViolations.put("a negative amount", with(history(caseId), "amount", "-1.00"));
        historyViolations.put("a lower-case currency", with(history(caseId), "currency", "usd"));
        historyViolations.put("a missing case", with(history(caseId), "case_id", 987_654_321_987L));
        for (Map.Entry<String, Map<String, Object>> violation : historyViolations.entrySet()) {
            assertRejected(violation.getKey(), () -> insert("after_sales_case_history", violation.getValue()));
        }
        insert("after_sales_case_history", history(caseId));
        insert("after_sales_case_history", with(history(caseId), "amount", null));

        // A case without a refund (one that was withdrawn, say) takes its lines and history with it.
        jdbcTemplate.update("DELETE FROM after_sales_cases WHERE id = ?", caseId);
        Assertions.assertEquals(0, count("after_sales_case_lines", caseId));
        Assertions.assertEquals(0, count("after_sales_case_history", caseId));
    }


    @Test
    void refunds_shouldBeOfTheApprovedAmountOfTheirCaseAndOneForEachCaseAndOrder() {
        Order order = order("COMPLETED", "20.00");
        long caseId = insert("after_sales_cases", refunding(order));

        Map<String, Map<String, Object>> violations = new LinkedHashMap<>();
        violations.put("an amount other than the approved one", with(refund(order, caseId), "amount", "4.99"));
        violations.put("more than the approved amount", with(refund(order, caseId), "amount", "5.01"));
        violations.put("another currency than the case's", with(refund(order, caseId), "currency", "EUR"));
        violations.put("a zero amount", with(refund(order, caseId), "amount", "0.00"));
        violations.put("a missing case", with(refund(order, caseId), "case_id", 987_654_321_987L));
        violations.put("an unknown status", with(refund(order, caseId), "status", "DONE"));
        violations.put("negative attempts", with(refund(order, caseId), "attempts", -1));
        violations.put("PENDING without a next attempt", with(refund(order, caseId), "next_attempt_at", null));
        violations.put("PROCESSING without a lease", with(processing(refund(order, caseId)), "claimed_until", null));
        violations.put("SUCCEEDED without a provider reference",
                with(succeeded(refund(order, caseId)), "provider_reference", null));
        violations.put("SUCCEEDED without a time", with(succeeded(refund(order, caseId)), "succeeded_at", null));
        violations.put("a success time before SUCCEEDED", with(with(refund(order, caseId), "status", "FAILED"),
                "succeeded_at", LocalDateTime.now()));
        violations.put("no payment reference", with(refund(order, caseId), "payment_reference", null));
        violations.put("a lower-case currency", with(refund(order, caseId), "currency", "usd"));
        for (Map.Entry<String, Map<String, Object>> violation : violations.entrySet()) {
            assertRejected(violation.getKey(), () -> insert("refunds", violation.getValue()));
        }

        Order open = order("COMPLETED", "20.00");
        long openCase = insert("after_sales_cases", pending(open));
        assertRejected("a refund of a case without an approved amount", () -> insert("refunds", refund(open, openCase)));

        long refundId = insert("refunds", refund(order, caseId));
        assertRejected("a second refund of the case",
                () -> insert("refunds", with(refund(order, caseId), "gateway_key", "after-sales-case:again-" + caseId)));
        long cancelledCase = insert("after_sales_cases", with(cancelled(order), "restaurant_id", order.restaurantId()));
        assertRejected("a second refund of the order", () -> insert("refunds",
                with(with(refund(order, cancelledCase), "amount", "20.00"), "gateway_key", "after-sales-case:" + cancelledCase)));
        Order other = order("COMPLETED", "20.00");
        long otherCase = insert("after_sales_cases", refunding(other));
        assertRejected("a gateway key of another refund",
                () -> insert("refunds", with(refund(other, otherCase), "gateway_key", "after-sales-case:" + caseId)));
        Assertions.assertDoesNotThrow(() -> insert("refunds", succeeded(refund(other, otherCase))));

        // Financial records: the case of a refund can neither go nor change its approved amount or currency.
        assertRejected("deleting a case with a refund",
                () -> jdbcTemplate.update("DELETE FROM after_sales_cases WHERE id = ?", caseId));
        assertRejected("changing the approved amount under a refund",
                () -> jdbcTemplate.update("UPDATE after_sales_cases SET approved_amount = 4.00 WHERE id = ?", caseId));
        assertRejected("deleting the order of a refund", () -> deleteOrder(order.id()));
        Assertions.assertEquals(1, count("refunds", caseId));
        Assertions.assertNotNull(refundRepository.findById(refundId).orElse(null));
    }


    @Test
    void merchantAfterSalesCases_shouldKeepOneRowPerCaseWithValidAmountsAndStatus() {
        long restaurantId = RESTAURANT_IDS.incrementAndGet();
        long caseId = 6_000_000_000L + RESTAURANT_IDS.get();

        Map<String, Map<String, Object>> violations = new LinkedHashMap<>();
        violations.put("an unknown status", with(merchantRow(caseId, restaurantId), "status", "OPEN"));
        violations.put("a negative request", with(merchantRow(caseId, restaurantId), "requested_amount", "-1.00"));
        violations.put("a negative offer", with(merchantRow(caseId, restaurantId), "offered_amount", "-1.00"));
        violations.put("a negative approval", with(merchantRow(caseId, restaurantId), "approved_amount", "-1.00"));
        violations.put("a lower-case currency", with(merchantRow(caseId, restaurantId), "currency", "eur"));
        for (Map.Entry<String, Map<String, Object>> violation : violations.entrySet()) {
            assertRejected(violation.getKey(), () -> insert("merchant_after_sales_cases", violation.getValue()));
        }
        insert("merchant_after_sales_cases", merchantRow(caseId, restaurantId));
        assertRejected("a second row of a case", () -> insert("merchant_after_sales_cases", merchantRow(caseId, restaurantId)));
    }


    @Test
    void orderNotifications_shouldDeduplicatePerOrderEventTypeAndScope() {
        Order order = order("PAID", "20.00");
        LocalDateTime now = LocalDateTime.now();

        if (parsesOnConflictTarget()) {
            // The order and delivery notification insert: the empty scope, one per order and event type.
            Assertions.assertEquals(1, orderNotificationRepository.insertIfAbsent(order.id(), order.customerId(),
                    "order.paid", "Payment confirmed", "Paid.", now));
            Assertions.assertEquals(0, orderNotificationRepository.insertIfAbsent(order.id(), order.customerId(),
                    "order.paid", "Payment confirmed", "Paid again.", now));
        } else {
            insert("order_notifications", notification(order, "order.paid"));
        }
        Assertions.assertEquals(List.of(""), jdbcTemplate.queryForList(
                "SELECT dedup_scope FROM order_notifications WHERE order_id = ?", String.class, order.id()));
        assertRejected("a second notification of an event in the empty scope",
                () -> insert("order_notifications", notification(order, "order.paid")));

        // Scoped notifications of the same event type, one per scope (an order's two after-sales cases).
        insert("order_notifications", with(notification(order, "order.paid"), "dedup_scope", "case:1"));
        insert("order_notifications", with(notification(order, "order.paid"), "dedup_scope", "case:2"));
        assertRejected("a second notification in one scope", () -> insert("order_notifications",
                with(notification(order, "order.paid"), "dedup_scope", "case:1")));
        Assertions.assertEquals(3, count("order_notifications", order.id()));
    }


    @Test
    void afterSalesCaseRepository_shouldSaveWithAVersionCheckAndLockOnlyTheOwnersCase() {
        Order order = order("COMPLETED", "20.00");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        AfterSalesCaseEntity saved = caseRepository.save(new AfterSalesCaseEntity(null, order.id(), order.customerId(),
                order.restaurantId(), "CUSTOMER_REQUEST", 1, "PENDING_MERCHANT", "MISSING_ITEM", "No fries",
                new BigDecimal("20.00"), new BigDecimal("5.00"), null, null, null, "USD", null, null, null, null, null,
                now.plusDays(1), null, 0, null, now, now));

        Assertions.assertEquals(0L, saved.version());
        Assertions.assertEquals(saved, caseRepository.findById(saved.id()).orElseThrow());

        AfterSalesCaseEntity approved = caseRepository.save(new AfterSalesCaseEntity(saved.id(), saved.orderId(),
                saved.customerId(), saved.restaurantId(), saved.kind(), saved.attemptNo(), "REFUNDING",
                saved.reasonCode(), saved.description(), saved.orderTotal(), saved.requestedAmount(), null,
                new BigDecimal("5.00"), null, "USD", "APPROVED", null, "MERCHANT_APPROVED", null, null, null, null, 0,
                saved.version(), saved.createdAt(), now.plusMinutes(1)));
        Assertions.assertEquals(1L, approved.version());
        Assertions.assertEquals("REFUNDING", caseRepository.findById(saved.id()).orElseThrow().status());

        // A writer that read the case before the approval is refused instead of overwriting it.
        AfterSalesCaseEntity stale = new AfterSalesCaseEntity(saved.id(), saved.orderId(), saved.customerId(),
                saved.restaurantId(), saved.kind(), saved.attemptNo(), "CLOSED", saved.reasonCode(),
                saved.description(), saved.orderTotal(), saved.requestedAmount(), null, null, null, "USD", null, null,
                null, "WITHDRAWN", null, null, null, 0, saved.version(), saved.createdAt(), now.plusMinutes(2));
        Assertions.assertThrows(OptimisticLockingFailureException.class, () -> caseRepository.save(stale));
        Assertions.assertEquals(approved, caseRepository.findById(saved.id()).orElseThrow());

        Assertions.assertEquals(approved, caseRepository.lockById(saved.id()));
        Assertions.assertEquals(approved, caseRepository.lockByIdAndCustomerId(saved.id(), order.customerId()));
        Assertions.assertNull(caseRepository.lockByIdAndCustomerId(saved.id(), order.customerId() + 1));
        Assertions.assertEquals(approved, caseRepository.lockByIdAndRestaurantId(saved.id(), order.restaurantId()));
        Assertions.assertNull(caseRepository.lockByIdAndRestaurantId(saved.id(), order.restaurantId() + 1));
        Assertions.assertNull(caseRepository.lockById(987_654_321_987L));
    }


    @Test
    void afterSalesCaseRepository_shouldPageNewestFirst() {
        Order first = order("COMPLETED", "20.00");
        Order second = order("COMPLETED", "20.00", first.customerId());
        LocalDateTime createdAt = LocalDateTime.of(2999, 1, 1, 0, 0);
        long older = insert("after_sales_cases", with(pending(first), "created_at", createdAt));
        long newer = insert("after_sales_cases", with(closed(second), "created_at", createdAt.plusSeconds(1)));

        Assertions.assertEquals(List.of(newer, older), ids(caseRepository.findPageByCustomerId(first.customerId(), 10)));
        Assertions.assertEquals(List.of(newer), ids(caseRepository.findPageByCustomerId(first.customerId(), 1)));
        Assertions.assertEquals(List.of(newer), ids(caseRepository.findRecent(1)));
        List<AfterSalesCaseEntity> closedCases = caseRepository.findPageByStatus("CLOSED", 100);
        Assertions.assertEquals(newer, closedCases.get(0).id());
        Assertions.assertTrue(closedCases.stream().allMatch(found -> "CLOSED".equals(found.status())));
    }


    @Test
    void afterSalesCaseRepository_shouldClaimTheMostOverdueDeadlineThatIsNotBackingOff() {
        LocalDateTime now = LONG_AGO.plusDays(10);
        long backingOff = insert("after_sales_cases", with(with(pending(order("COMPLETED", "20.00")),
                "deadline_at", LONG_AGO.minusDays(1)), "deadline_retry_at", now.plusMinutes(1)));
        long mostOverdue = insert("after_sales_cases", with(pending(order("COMPLETED", "20.00")),
                "deadline_at", LONG_AGO));
        long overdue = insert("after_sales_cases", with(offered(order("COMPLETED", "20.00")),
                "deadline_at", LONG_AGO.plusDays(1)));
        long retryDue = insert("after_sales_cases", with(with(pending(order("COMPLETED", "20.00")),
                "deadline_at", LONG_AGO.plusDays(2)), "deadline_retry_at", now));
        insert("after_sales_cases", with(pending(order("COMPLETED", "20.00")), "deadline_at", now.plusSeconds(1)));

        AfterSalesCaseEntity claimed = caseRepository.claimNextDue(now);
        Assertions.assertEquals(mostOverdue, claimed.id());

        // A retry only lands on the version it was meant for, and leaves the version alone.
        Assertions.assertEquals(0, caseRepository.rescheduleDeadline(mostOverdue, claimed.version() + 1,
                now.plusMinutes(5), 1));
        Assertions.assertEquals(1, caseRepository.rescheduleDeadline(mostOverdue, claimed.version(),
                now.plusMinutes(5), 1));
        AfterSalesCaseEntity rescheduled = caseRepository.findById(mostOverdue).orElseThrow();
        Assertions.assertEquals(now.plusMinutes(5), rescheduled.deadlineRetryAt());
        Assertions.assertEquals(1, rescheduled.deadlineAttempts());
        Assertions.assertEquals(claimed.version(), rescheduled.version());
        Assertions.assertEquals(LONG_AGO, rescheduled.deadlineAt());

        Assertions.assertEquals(overdue, caseRepository.claimNextDue(now).id());
        jdbcTemplate.update("UPDATE after_sales_cases SET status = 'REFUNDING', approved_amount = offered_amount, "
                + "resolution = 'OFFER_TIMEOUT', deadline_at = NULL WHERE id = ?", overdue);
        Assertions.assertEquals(retryDue, caseRepository.claimNextDue(now).id());
        jdbcTemplate.update("UPDATE after_sales_cases SET deadline_retry_at = ? WHERE id = ?", now.plusSeconds(1),
                retryDue);
        Assertions.assertNull(caseRepository.claimNextDue(now));
        Assertions.assertEquals(backingOff, caseRepository.claimNextDue(now.plusMinutes(1)).id());
    }


    @Test
    void lineAndHistoryRepositories_shouldReadACaseInOrder() {
        Order order = order("COMPLETED", "20.00");
        long fries = orderItem(order.id(), "2.50", 4);
        long burger = orderItem(order.id(), "7.50", 2);
        long caseId = insert("after_sales_cases", with(pending(order), "requested_amount", "12.50"));
        LocalDateTime now = LocalDateTime.now().withNano(0);

        List<AfterSalesCaseLineEntity> lines = lineRepository.saveAll(List.of(
                new AfterSalesCaseLineEntity(null, caseId, fries, "Fries", new BigDecimal("2.50"), 2,
                        new BigDecimal("5.00"), "USD"),
                new AfterSalesCaseLineEntity(null, caseId, burger, "Burger", new BigDecimal("7.50"), 1,
                        new BigDecimal("7.50"), "USD")));
        Assertions.assertEquals(lines, lineRepository.findAllByCaseIdIn(List.of(caseId, 987_654_321_987L)));

        AfterSalesCaseHistoryEntity later = historyRepository.save(new AfterSalesCaseHistoryEntity(null, caseId,
                "PENDING_MERCHANT", "REJECTED", "MERCHANT_REJECT", "MERCHANT", 42L, null, "USD", "Looked fine",
                "0af7651916cd43dd8448eb211c80319c", now.plusMinutes(1)));
        AfterSalesCaseHistoryEntity opened = historyRepository.save(new AfterSalesCaseHistoryEntity(null, caseId, null,
                "PENDING_MERCHANT", "OPEN", "CUSTOMER", order.customerId(), new BigDecimal("12.50"), "USD", null, null,
                now));
        Assertions.assertEquals(List.of(opened, later), historyRepository.findAllByCaseId(caseId));
    }


    @Test
    void refundRepository_shouldClaimExpiredLeasesBeforeDueRefundsWithAVersionCheck() {
        LocalDateTime now = LONG_AGO.plusDays(10);
        RefundEntity due = saveRefund(refundOfANewCase("PENDING", LONG_AGO, null));
        RefundEntity expired = saveRefund(refundOfANewCase("PROCESSING", null, now.minusSeconds(1)));
        RefundEntity leased = saveRefund(refundOfANewCase("PROCESSING", null, now.plusSeconds(1)));
        RefundEntity failed = saveRefund(refundOfANewCase("FAILED", null, null));
        RefundEntity later = saveRefund(refundOfANewCase("PENDING", now.plusSeconds(1), null));

        // An expired lease is claimed before a due PENDING refund, although the PENDING one has the lower id.
        Assertions.assertEquals(expired, refundRepository.claimNextDue(now));
        refundRepository.save(withStatus(expired, "PROCESSING", now.plusMinutes(1)));

        Assertions.assertEquals(0L, due.version());
        Assertions.assertEquals(due, refundRepository.findById(due.id()).orElseThrow());
        RefundEntity claimed = refundRepository.claimNextDue(now);
        Assertions.assertEquals(due, claimed);
        RefundEntity processing = refundRepository.save(withStatus(claimed, "PROCESSING", now.plusMinutes(1)));
        Assertions.assertEquals(1L, processing.version());

        // The result of an attempt whose lease another instance took over is refused.
        Assertions.assertThrows(OptimisticLockingFailureException.class,
                () -> refundRepository.save(withStatus(claimed, "FAILED", null)));
        Assertions.assertEquals(processing, refundRepository.lockById(due.id()));

        Assertions.assertNull(refundRepository.claimNextDue(now));
        // At now + 1s the lease of leased has expired and later is due: the expired lease still goes first.
        Assertions.assertEquals(leased.id(), refundRepository.claimNextDue(now.plusSeconds(1)).id());

        Assertions.assertEquals(List.of(due.id(), failed.id(), later.id()), refundRepository.findAllByCaseIdIn(
                List.of(later.caseId(), failed.caseId(), due.caseId())).stream().map(RefundEntity::id).toList());
        List<RefundEntity> failedPage = refundRepository.findPageByStatus("FAILED", 100);
        Assertions.assertEquals(failed.id(), failedPage.get(0).id());
        Assertions.assertTrue(failedPage.stream().allMatch(found -> "FAILED".equals(found.status())));
    }


    @Test
    void merchantAfterSalesRepository_shouldInsertOnceAndOnlyMoveForward() {
        long restaurantId = RESTAURANT_IDS.incrementAndGet();
        long caseId = 7_000_000_000L + RESTAURANT_IDS.get();
        long otherCaseId = caseId + 1;
        LocalDateTime openedAt = LocalDateTime.now().withNano(0);

        Assertions.assertEquals(1, insertMerchantRow(caseId, restaurantId, openedAt));
        Assertions.assertEquals(0, insertMerchantRow(caseId, restaurantId, openedAt));
        Assertions.assertEquals(1, insertMerchantRow(otherCaseId, restaurantId, openedAt.plusMinutes(1)));

        Assertions.assertEquals(0, merchantAfterSalesRepository.updateIfNewer(caseId, "COUNTER_OFFERED",
                new BigDecimal("2.00"), null, "OFFERED", "Half", null, 0L, openedAt));
        Assertions.assertEquals(1, merchantAfterSalesRepository.updateIfNewer(caseId, "REFUNDING", null,
                new BigDecimal("5.00"), "APPROVED", null, null, 2L, openedAt.plusMinutes(2)));
        Assertions.assertEquals(0, merchantAfterSalesRepository.updateIfNewer(caseId, "COUNTER_OFFERED",
                new BigDecimal("2.00"), null, "OFFERED", "Half", openedAt.plusDays(1), 1L, openedAt.plusMinutes(3)));

        MerchantAfterSalesEntity row = merchantAfterSalesRepository.lockByCaseIdAndRestaurantId(caseId, restaurantId);
        Assertions.assertEquals("REFUNDING", row.status());
        Assertions.assertEquals(0, new BigDecimal("5.00").compareTo(row.approvedAmount()));
        Assertions.assertEquals("APPROVED", row.merchantDecision());
        Assertions.assertNull(row.deadlineAt());
        Assertions.assertEquals(2L, row.caseVersion());
        Assertions.assertEquals("[{\"order_item_id\":1,\"quantity\":2}]", row.lines());
        Assertions.assertNull(merchantAfterSalesRepository.lockByCaseIdAndRestaurantId(caseId, restaurantId + 1));

        Assertions.assertEquals(List.of(otherCaseId, caseId), merchantAfterSalesRepository
                .findPageByRestaurantId(restaurantId, 10).stream().map(MerchantAfterSalesEntity::caseId).toList());
        Assertions.assertEquals(List.of(otherCaseId), merchantAfterSalesRepository
                .findPageByRestaurantId(restaurantId, 1).stream().map(MerchantAfterSalesEntity::caseId).toList());
        Assertions.assertEquals(List.of(caseId), merchantAfterSalesRepository
                .findPageByRestaurantIdAndStatus(restaurantId, "REFUNDING", 10).stream()
                .map(MerchantAfterSalesEntity::caseId).toList());
    }


    private int insertMerchantRow(long caseId, long restaurantId, LocalDateTime openedAt) {
        return merchantAfterSalesRepository.insertIfAbsent(caseId, caseId + 100, 9_123_456_789L, restaurantId, 77L,
                "PENDING_MERCHANT", "MISSING_ITEM", "No fries", "[{\"order_item_id\":1,\"quantity\":2}]",
                new BigDecimal("5.00"), null, null, "USD", null, null, openedAt.plusDays(1), 0L, openedAt, openedAt);
    }


    private record Order(long id, long customerId, long restaurantId) {
    }


    private Order order(String status, String total) {
        return order(status, total, customer());
    }


    private Order order(String status, String total, long customerId) {
        long restaurantId = RESTAURANT_IDS.incrementAndGet();
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("order_no", ORDER_NUMBERS.incrementAndGet());
        order.put("customer_id", customerId);
        order.put("total_price", new BigDecimal(total));
        order.put("currency", "USD");
        order.put("status", status);
        order.put("restaurant_id", restaurantId);
        order.put("created_at", now);
        order.put("updated_at", now);
        return new Order(insert("orders", order), customerId, restaurantId);
    }


    private long customer() {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("email", "after-sales-schema-" + ORDER_NUMBERS.incrementAndGet() + "@example.com");
        customer.put("password", "{noop}secret");
        return insert("customers", customer);
    }


    private long orderItem(long orderId, String price, int quantity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("order_id", orderId);
        item.put("menu_item_id", 1L);
        item.put("restaurant_id", 1L);
        item.put("price", new BigDecimal(price));
        item.put("quantity", quantity);
        item.put("menu_item_name", "Fries");
        item.put("currency", "USD");
        return insert("order_history_items", item);
    }


    private static Map<String, Object> payment(Order order) {
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("order_id", order.id());
        payment.put("customer_id", order.customerId());
        payment.put("amount", new BigDecimal("20.00"));
        payment.put("currency", "USD");
        payment.put("status", "CAPTURED");
        payment.put("provider_reference", "SIMPAY-schema-" + order.id());
        payment.put("captured_at", LocalDateTime.now());
        return payment;
    }


    /**
     * A customer's request of 5.00 on a 20.00 order, waiting for the merchant.
     */
    private static Map<String, Object> pending(Order order) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> afterSalesCase = new LinkedHashMap<>();
        afterSalesCase.put("order_id", order.id());
        afterSalesCase.put("customer_id", order.customerId());
        afterSalesCase.put("restaurant_id", order.restaurantId());
        afterSalesCase.put("kind", "CUSTOMER_REQUEST");
        afterSalesCase.put("attempt_no", 1);
        afterSalesCase.put("status", "PENDING_MERCHANT");
        afterSalesCase.put("reason_code", "MISSING_ITEM");
        afterSalesCase.put("description", "The fries were missing");
        afterSalesCase.put("order_total", new BigDecimal("20.00"));
        afterSalesCase.put("requested_amount", new BigDecimal("5.00"));
        afterSalesCase.put("offered_amount", null);
        afterSalesCase.put("approved_amount", null);
        afterSalesCase.put("refunded_amount", null);
        afterSalesCase.put("currency", "USD");
        afterSalesCase.put("merchant_decision", null);
        afterSalesCase.put("resolution", null);
        afterSalesCase.put("close_reason", null);
        afterSalesCase.put("deadline_at", now.plusDays(1));
        afterSalesCase.put("version", 0L);
        afterSalesCase.put("created_at", now);
        afterSalesCase.put("updated_at", now);
        return afterSalesCase;
    }


    private static Map<String, Object> offered(Order order) {
        Map<String, Object> afterSalesCase = with(pending(order), "status", "COUNTER_OFFERED");
        afterSalesCase.put("offered_amount", new BigDecimal("2.00"));
        afterSalesCase.put("merchant_decision", "OFFERED");
        return afterSalesCase;
    }


    private static Map<String, Object> refunding(Order order) {
        Map<String, Object> afterSalesCase = with(pending(order), "status", "REFUNDING");
        afterSalesCase.put("approved_amount", new BigDecimal("5.00"));
        afterSalesCase.put("merchant_decision", "APPROVED");
        afterSalesCase.put("resolution", "MERCHANT_APPROVED");
        afterSalesCase.put("deadline_at", null);
        return afterSalesCase;
    }


    private static Map<String, Object> refunded(Order order) {
        return with(with(refunding(order), "status", "REFUNDED"), "refunded_amount", new BigDecimal("5.00"));
    }


    private static Map<String, Object> closed(Order order) {
        return with(with(with(pending(order), "status", "CLOSED"), "close_reason", "WITHDRAWN"), "deadline_at", null);
    }


    /**
     * The automatic refund of the whole 20.00 of a cancelled paid order.
     */
    private static Map<String, Object> cancelled(Order order) {
        Map<String, Object> afterSalesCase = refunding(order);
        afterSalesCase.put("kind", "ORDER_CANCELLED");
        afterSalesCase.put("reason_code", null);
        afterSalesCase.put("description", null);
        afterSalesCase.put("requested_amount", new BigDecimal("20.00"));
        afterSalesCase.put("approved_amount", new BigDecimal("20.00"));
        afterSalesCase.put("merchant_decision", null);
        afterSalesCase.put("resolution", "ORDER_CANCELLED");
        return afterSalesCase;
    }


    /**
     * Two of the order line's units.
     */
    private static Map<String, Object> line(long caseId, long orderItemId) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("case_id", caseId);
        line.put("order_item_id", orderItemId);
        line.put("menu_item_name", "Fries");
        line.put("unit_price", new BigDecimal("2.50"));
        line.put("quantity", 2);
        line.put("amount", new BigDecimal("5.00"));
        line.put("currency", "USD");
        return line;
    }


    private static Map<String, Object> history(long caseId) {
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("case_id", caseId);
        history.put("from_status", null);
        history.put("to_status", "PENDING_MERCHANT");
        history.put("action", "OPEN");
        history.put("actor_type", "CUSTOMER");
        history.put("actor_id", 1L);
        history.put("amount", new BigDecimal("5.00"));
        history.put("currency", "USD");
        history.put("occurred_at", LocalDateTime.now());
        return history;
    }


    /**
     * The refund of a {@link #refunding} case: its approved 5.00, due now.
     */
    private static Map<String, Object> refund(Order order, long caseId) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> refund = new LinkedHashMap<>();
        refund.put("case_id", caseId);
        refund.put("order_id", order.id());
        refund.put("customer_id", order.customerId());
        refund.put("gateway_key", "after-sales-case:" + caseId);
        refund.put("payment_reference", "SIMPAY-schema-" + order.id());
        refund.put("amount", new BigDecimal("5.00"));
        refund.put("currency", "USD");
        refund.put("status", "PENDING");
        refund.put("attempts", 0);
        refund.put("next_attempt_at", now);
        refund.put("claimed_until", null);
        refund.put("provider_reference", null);
        refund.put("succeeded_at", null);
        refund.put("version", 0L);
        refund.put("created_at", now);
        refund.put("updated_at", now);
        return refund;
    }


    private static Map<String, Object> processing(Map<String, Object> refund) {
        return with(with(refund, "status", "PROCESSING"), "claimed_until", LocalDateTime.now().plusMinutes(1));
    }


    private static Map<String, Object> succeeded(Map<String, Object> refund) {
        Map<String, Object> succeeded = with(refund, "status", "SUCCEEDED");
        succeeded.put("provider_reference", "SIMREF-0123456789abcdef0123");
        succeeded.put("succeeded_at", LocalDateTime.now());
        return succeeded;
    }


    private RefundEntity refundOfANewCase(String status, LocalDateTime nextAttemptAt, LocalDateTime claimedUntil) {
        Order order = order("COMPLETED", "20.00");
        long caseId = insert("after_sales_cases", refunding(order));
        LocalDateTime now = LocalDateTime.now().withNano(0);
        return new RefundEntity(null, caseId, order.id(), order.customerId(), "after-sales-case:" + caseId,
                "SIMPAY-schema-" + order.id(), new BigDecimal("5.00"), "USD", status, 1, nextAttemptAt, claimedUntil,
                null, "FAILED".equals(status) ? "simulated gateway timeout" : null, null, now, now, null);
    }


    private RefundEntity saveRefund(RefundEntity refund) {
        return refundRepository.save(refund);
    }


    private static RefundEntity withStatus(RefundEntity refund, String status, LocalDateTime claimedUntil) {
        return new RefundEntity(refund.id(), refund.caseId(), refund.orderId(), refund.customerId(),
                refund.gatewayKey(), refund.paymentReference(), refund.amount(), refund.currency(), status,
                refund.attempts() + 1, refund.nextAttemptAt(), claimedUntil, refund.providerReference(),
                refund.lastError(), refund.version(), refund.createdAt(), refund.updatedAt(), refund.succeededAt());
    }


    private static Map<String, Object> merchantRow(long caseId, long restaurantId) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("case_id", caseId);
        row.put("order_id", caseId + 100);
        row.put("restaurant_id", restaurantId);
        row.put("customer_id", 77L);
        row.put("status", "PENDING_MERCHANT");
        row.put("reason_code", "MISSING_ITEM");
        row.put("description", "No fries");
        row.put("lines", "[]");
        row.put("requested_amount", new BigDecimal("5.00"));
        row.put("offered_amount", null);
        row.put("approved_amount", null);
        row.put("currency", "USD");
        row.put("deadline_at", now.plusDays(1));
        row.put("case_version", 0L);
        row.put("opened_at", now);
        row.put("updated_at", now);
        return row;
    }


    private static Map<String, Object> notification(Order order, String eventType) {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("order_id", order.id());
        notification.put("customer_id", order.customerId());
        notification.put("event_type", eventType);
        notification.put("title", "Payment confirmed");
        notification.put("message", "Paid.");
        notification.put("created_at", LocalDateTime.now());
        return notification;
    }


    private static Map<String, Object> with(Map<String, Object> values, String column, Object value) {
        Map<String, Object> changed = new LinkedHashMap<>(values);
        changed.put(column, value instanceof String text && looksLikeAnAmount(text) ? new BigDecimal(text) : value);
        return changed;
    }


    private static boolean looksLikeAnAmount(String text) {
        return text.matches("-?\\d+\\.\\d{2}");
    }


    /**
     * Inserts one row with the given column values.
     *
     * @return its generated id
     */
    private long insert(String table, Map<String, Object> values) {
        List<String> columns = new ArrayList<>(values.keySet());
        String sql = "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                + String.join(", ", columns.stream().map(column -> "?").toList()) + ")";
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            int index = 1;
            for (Object value : values.values()) {
                statement.setObject(index++, value);
            }
            return statement;
        }, keys);
        return keys.getKeyAs(Number.class).longValue();
    }


    private void deleteOrder(long orderId) {
        jdbcTemplate.update("DELETE FROM orders WHERE id = ?", orderId);
    }


    /**
     * @return the rows of {@code table} that belong to the case (or, for notifications, the order) {@code id}
     */
    private int count(String table, long id) {
        String column = "order_notifications".equals(table) ? "order_id" : "case_id";
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, id);
        return rows == null ? 0 : rows;
    }


    private static List<Long> ids(List<AfterSalesCaseEntity> cases) {
        return cases.stream().map(AfterSalesCaseEntity::id).toList();
    }


    private static void assertRejected(String violation, Executable insert) {
        Assertions.assertThrows(DataIntegrityViolationException.class, insert, violation + " must be refused");
    }
}
