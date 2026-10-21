package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.messaging.AfterSalesEventEnvelope;
import com.laioffer.onlineorder.messaging.contract.EventContracts;
import com.laioffer.onlineorder.messaging.contract.EventTopic;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderPaymentRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.CustomerService;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import com.laioffer.onlineorder.service.OrderService;
import com.laioffer.onlineorder.statemachine.Decision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;


import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * The after-sales deadlines (ADR 0002 sections 6 and 19.1 to 19.3) on a real database, with the processor called
 * directly instead of from the poller's thread: each of the four timeouts fires with its outcome and amount, and a
 * case entering REFUNDING gets exactly one refund; cases whose deadline has not come, that back off, or that wait in
 * no deadline are left alone; a failure backs the case off without moving its deadline, and a back-off of a stale
 * version changes nothing; the customer's and the merchant's answers are refused from the deadline on even before the
 * poller ran, while withdrawing and arbitrating are not. The merchant's and the admin's commands, which have no
 * endpoints yet, go through the single writer under the case's row lock, as their services will. The times of an
 * opening and a step taken at a clock with nanoseconds are the stored ones in their events. The cases have to hold
 * on PostgreSQL and on H2 (the {@code h2} profile), so each database gets a subclass; every test leaves no case due,
 * so that each run of the processor only meets the test's own cases.
 */
abstract class AbstractAfterSalesDeadlineIntegrationTests {

    static final String BURGER_KING = "Burger King";

    static final String BURGER_KING_MERCHANT = "merchant.burgerking@laifood.com";

    static final String ADMIN = "demo@laifood.com";

    /**
     * app.after-sales.poller.retry-backoff as application.yml sets it.
     */
    static final Duration RETRY_BACKOFF = Duration.ofSeconds(30);

    static final String OFFER = "2.00";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    CustomerService customerService;

    @Autowired
    CartService cartService;

    @Autowired
    OrderService orderService;

    @Autowired
    AfterSalesCaseRepository caseRepository;

    @Autowired
    AfterSalesTransitions transitions;

    @Autowired
    AfterSalesCustomerService afterSalesCustomerService;

    @Autowired
    AfterSalesDeadlineProcessor deadlineProcessor;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    PlatformTransactionManager transactionManager;


    @Test
    void eachTimeout_shouldFireWithItsOutcomeAndAmount_mostOverdueFirst() throws Exception {
        Customer alice = customer("after-sales-timeouts-");
        AfterSalesCaseEntity merchantLate = caseIn(alice, AfterSalesStatus.PENDING_MERCHANT);
        AfterSalesCaseEntity offerLate = caseIn(alice, AfterSalesStatus.COUNTER_OFFERED);
        AfterSalesCaseEntity customerLate = caseIn(alice, AfterSalesStatus.REJECTED);
        AfterSalesCaseEntity arbitrationLate = caseIn(alice, AfterSalesStatus.IN_ARBITRATION);
        // The newest case is the most overdue, so the firing order is the deadlines', not the ids'.
        makeDue(merchantLate.id(), 1);
        makeDue(offerLate.id(), 2);
        makeDue(customerLate.id(), 3);
        makeDue(arbitrationLate.id(), 4);
        Map<AfterSalesTrigger, Double> firedBefore = new EnumMap<>(AfterSalesTrigger.class);
        for (AfterSalesTrigger timeout : List.of(AfterSalesTrigger.MERCHANT_TIMEOUT, AfterSalesTrigger.OFFER_TIMEOUT,
                AfterSalesTrigger.CUSTOMER_TIMEOUT, AfterSalesTrigger.ARBITRATION_TIMEOUT)) {
            firedBefore.put(timeout, deadlineCount(timeout, AfterSalesDeadlineProcessor.FIRED));
        }

        Assertions.assertEquals(4, deadlineProcessor.fireDueDeadlines());

        AfterSalesCaseEntity merchantTimedOut = assertRefunding(merchantLate, AfterSalesResolution.MERCHANT_TIMEOUT,
                merchantLate.requestedAmount());
        Assertions.assertNull(merchantTimedOut.merchantDecision(), "nobody answered for the merchant");
        AfterSalesCaseEntity offerTimedOut = assertRefunding(offerLate, AfterSalesResolution.OFFER_TIMEOUT,
                new BigDecimal(OFFER));
        Assertions.assertEquals("OFFERED", offerTimedOut.merchantDecision());
        AfterSalesCaseEntity arbitrationTimedOut = assertRefunding(arbitrationLate,
                AfterSalesResolution.ARBITRATION_TIMEOUT, arbitrationLate.requestedAmount());
        Assertions.assertNull(arbitrationTimedOut.adminNote());

        AfterSalesCaseEntity closed = reload(customerLate.id());
        Assertions.assertEquals("CLOSED", closed.status());
        Assertions.assertEquals("CUSTOMER_TIMEOUT", closed.closeReason());
        Assertions.assertNull(closed.approvedAmount());
        Assertions.assertNull(closed.resolution());
        assertNoDeadline(closed, customerLate.version() + 1);
        Assertions.assertEquals(0, refunds(customerLate.id()));
        Assertions.assertEquals("CUSTOMER_TIMEOUT|REJECTED->CLOSED|SYSTEM|null|null", lastStep(customerLate.id()));
        Assertions.assertEquals(List.of("aftersales.case_opened", "aftersales.request_rejected",
                "aftersales.case_closed"), eventTypes(customerLate.id()));

        Assertions.assertEquals(
                List.of(arbitrationLate.id(), customerLate.id(), offerLate.id(), merchantLate.id()),
                timeoutEventOrder(List.of(merchantLate.id(), offerLate.id(), customerLate.id(), arbitrationLate.id()))
        );
        firedBefore.forEach((timeout, before) -> Assertions.assertEquals(before + 1,
                deadlineCount(timeout, AfterSalesDeadlineProcessor.FIRED), timeout::name));
        // Nothing is left due.
        Assertions.assertEquals(0, deadlineProcessor.fireDueDeadlines());
    }


    /**
     * A deadline still ahead, a due case that backs off, and a case in REFUNDING (no deadline) are not claimed. The
     * backing-off case fires once its retry is due, which clears the retry.
     */
    @Test
    void casesNotDue_shouldBeLeftAlone() throws Exception {
        Customer bob = customer("after-sales-not-due-");
        AfterSalesCaseEntity waiting = caseIn(bob, AfterSalesStatus.PENDING_MERCHANT);
        AfterSalesCaseEntity backingOff = caseIn(bob, AfterSalesStatus.PENDING_MERCHANT);
        makeDue(backingOff.id(), 10);
        LocalDateTime retryAt = LocalDateTime.now().plusMinutes(5).withNano(0);
        jdbcTemplate.update("UPDATE after_sales_cases SET deadline_retry_at = ?, deadline_attempts = 2 WHERE id = ?",
                retryAt, backingOff.id());
        AfterSalesCaseEntity refunding = orderCancelledCase(bob);
        List<AfterSalesCaseEntity> before = List.of(reload(waiting.id()), reload(backingOff.id()),
                reload(refunding.id()));

        Assertions.assertEquals(0, deadlineProcessor.fireDueDeadlines());

        Assertions.assertEquals(before, List.of(reload(waiting.id()), reload(backingOff.id()), reload(refunding.id())));
        Assertions.assertEquals(1, refunds(refunding.id()), "the cancelled order keeps its one refund");
        Assertions.assertEquals(List.of("aftersales.case_opened"), eventTypes(waiting.id()));

        jdbcTemplate.update("UPDATE after_sales_cases SET deadline_retry_at = ? WHERE id = ?",
                LocalDateTime.now().minusSeconds(1), backingOff.id());
        Assertions.assertEquals(1, deadlineProcessor.fireDueDeadlines());
        assertRefunding(backingOff, AfterSalesResolution.MERCHANT_TIMEOUT, backingOff.requestedAmount());
        Assertions.assertEquals(before.get(0), reload(waiting.id()));
        Assertions.assertEquals(before.get(2), reload(refunding.id()));
        Assertions.assertEquals(1, refunds(refunding.id()));
    }


    /**
     * The timeout of a case whose order lost its payment record cannot create the refund: the step rolls back whole,
     * and the case backs off by the retry backoff with one more attempt. deadline_at stays, so the merchant's answer
     * stays refused meanwhile. Once the payment is back and the retry due, the timeout fires and clears the retry.
     */
    @Test
    void failingTimeout_shouldBackOffWithoutMovingTheDeadline() throws Exception {
        Customer carol = customer("after-sales-failing-");
        AfterSalesCaseEntity late = caseIn(carol, AfterSalesStatus.PENDING_MERCHANT);
        LocalDateTime deadline = makeDue(late.id(), 1);
        Map<String, Object> payment = jdbcTemplate.queryForMap(
                "SELECT * FROM order_payments WHERE order_id = ?", late.orderId());
        jdbcTemplate.update("DELETE FROM order_payments WHERE order_id = ?", late.orderId());
        double failedBefore = deadlineCount(AfterSalesTrigger.MERCHANT_TIMEOUT, AfterSalesDeadlineProcessor.FAILED);

        LocalDateTime beforeRun = LocalDateTime.now();
        Assertions.assertEquals(1, deadlineProcessor.fireDueDeadlines());
        LocalDateTime afterRun = LocalDateTime.now();

        AfterSalesCaseEntity backedOff = reload(late.id());
        Assertions.assertEquals("PENDING_MERCHANT", backedOff.status());
        Assertions.assertEquals(deadline, backedOff.deadlineAt(), "the deadline itself never moves");
        Assertions.assertEquals(late.version(), backedOff.version(), "a back-off is not a step");
        Assertions.assertEquals(1, backedOff.deadlineAttempts());
        assertRetryAbout(backedOff.deadlineRetryAt(), beforeRun, afterRun);
        Assertions.assertNull(backedOff.approvedAmount());
        Assertions.assertEquals(0, refunds(late.id()));
        Assertions.assertEquals(List.of("OPEN|null->PENDING_MERCHANT|CUSTOMER|" + carol.id() + "|"
                + late.requestedAmount().setScale(2)), history(late.id()));
        Assertions.assertEquals(List.of("aftersales.case_opened"), eventTypes(late.id()));
        Assertions.assertEquals(failedBefore + 1,
                deadlineCount(AfterSalesTrigger.MERCHANT_TIMEOUT, AfterSalesDeadlineProcessor.FAILED));
        // Not claimed again before its retry is due, and the merchant's answer is still too late.
        Assertions.assertEquals(0, deadlineProcessor.fireDueDeadlines());
        Assertions.assertEquals("CONFLICT|AFTER_SALES_DEADLINE_PASSED|The deadline of this after-sales step passed at "
                + AfterSalesErrors.timestamp(deadline), outcome(() -> merchantAnswers(
                late.id(), AfterSalesTrigger.MERCHANT_APPROVE, null, null)));

        // Failing again counts another attempt from the retry on.
        jdbcTemplate.update("UPDATE after_sales_cases SET deadline_retry_at = ? WHERE id = ?",
                LocalDateTime.now().minusSeconds(1), late.id());
        Assertions.assertEquals(1, deadlineProcessor.fireDueDeadlines());
        Assertions.assertEquals(2, reload(late.id()).deadlineAttempts());
        Assertions.assertEquals(deadline, reload(late.id()).deadlineAt());

        jdbcTemplate.update("""
                INSERT INTO order_payments (order_id, customer_id, amount, currency, status, provider_reference,
                                            captured_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, payment.get("order_id"), payment.get("customer_id"), payment.get("amount"),
                payment.get("currency"), payment.get("status"), payment.get("provider_reference"),
                payment.get("captured_at"));
        jdbcTemplate.update("UPDATE after_sales_cases SET deadline_retry_at = ? WHERE id = ?",
                LocalDateTime.now().minusSeconds(1), late.id());
        Assertions.assertEquals(1, deadlineProcessor.fireDueDeadlines());
        assertRefunding(late, AfterSalesResolution.MERCHANT_TIMEOUT, late.requestedAmount());
        Assertions.assertEquals(payment.get("provider_reference"), jdbcTemplate.queryForObject(
                "SELECT payment_reference FROM refunds WHERE case_id = ?", String.class, late.id()));
    }


    /**
     * The back-off is guarded by the version the poller claimed (section 19.3): a stale version changes nothing,
     * neither before nor after a command moved the case on; the claimed version backs off without a new version.
     */
    @Test
    void rescheduleDeadline_shouldOnlyBackOffTheClaimedVersion() throws Exception {
        Customer dave = customer("after-sales-reschedule-");
        AfterSalesCaseEntity open = caseIn(dave, AfterSalesStatus.PENDING_MERCHANT);
        LocalDateTime retryAt = LocalDateTime.now().plusMinutes(1).withNano(0);

        Assertions.assertEquals(0, reschedule(open.id(), open.version() + 1, retryAt, 3));
        Assertions.assertEquals(open, reload(open.id()));

        Assertions.assertEquals(1, reschedule(open.id(), open.version(), retryAt, 3));
        AfterSalesCaseEntity backedOff = reload(open.id());
        Assertions.assertEquals(retryAt, backedOff.deadlineRetryAt());
        Assertions.assertEquals(3, backedOff.deadlineAttempts());
        Assertions.assertEquals(open.version(), backedOff.version());
        Assertions.assertEquals(open.deadlineAt(), backedOff.deadlineAt());
        Assertions.assertEquals(open.status(), backedOff.status());

        // The merchant's offer moves the case on and clears the retry; the stale back-off then finds nothing.
        Assertions.assertEquals("COUNTER_OFFERED",
                merchantAnswers(open.id(), AfterSalesTrigger.MERCHANT_OFFER, Money.of(OFFER, "USD"), null));
        AfterSalesCaseEntity offered = reload(open.id());
        Assertions.assertNull(offered.deadlineRetryAt());
        Assertions.assertEquals(0, offered.deadlineAttempts());
        Assertions.assertEquals(0, reschedule(open.id(), open.version(), retryAt, 4));
        Assertions.assertEquals(offered, reload(open.id()));
    }


    /**
     * Section 19.1: past the deadline, before the poller ran, the merchant's and the customer's answers are refused
     * (409 AFTER_SALES_DEADLINE_PASSED) and change nothing, while the customer's withdrawal and an admin's decision
     * still go through. The poller then fires only the cases still waiting.
     */
    @Test
    void answersAfterTheDeadline_shouldBeRefusedButWithdrawalAndArbitrationGoThrough() throws Exception {
        Customer erin = customer("after-sales-hard-soft-");
        AfterSalesCaseEntity merchantLate = caseIn(erin, AfterSalesStatus.PENDING_MERCHANT);
        AfterSalesCaseEntity offerLate = caseIn(erin, AfterSalesStatus.COUNTER_OFFERED);
        AfterSalesCaseEntity withdrawnLate = caseIn(erin, AfterSalesStatus.REJECTED);
        AfterSalesCaseEntity arbitratedLate = caseIn(erin, AfterSalesStatus.IN_ARBITRATION);
        LocalDateTime merchantDeadline = makeDue(merchantLate.id(), 2);
        LocalDateTime offerDeadline = makeDue(offerLate.id(), 2);
        makeDue(withdrawnLate.id(), 2);
        makeDue(arbitratedLate.id(), 2);
        String merchantTooLate = "CONFLICT|AFTER_SALES_DEADLINE_PASSED|The deadline of this after-sales step passed at "
                + AfterSalesErrors.timestamp(merchantDeadline);

        Assertions.assertEquals(merchantTooLate, outcome(() -> merchantAnswers(
                merchantLate.id(), AfterSalesTrigger.MERCHANT_APPROVE, null, null)));
        Assertions.assertEquals(merchantTooLate, outcome(() -> merchantAnswers(
                merchantLate.id(), AfterSalesTrigger.MERCHANT_REJECT, null, "Too late anyway.")));
        Assertions.assertEquals(merchantTooLate, outcome(() -> merchantAnswers(
                merchantLate.id(), AfterSalesTrigger.MERCHANT_OFFER, Money.of("1.00", "USD"), null)));
        Assertions.assertEquals(merchantLate.version(), reload(merchantLate.id()).version());

        String offerTooLate = "The deadline of this after-sales step passed at " + AfterSalesErrors.timestamp(offerDeadline);
        for (String answer : List.of("accept-offer", "decline-offer")) {
            command(erin, offerLate.id(), answer, answer + "-late-" + offerLate.id())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("AFTER_SALES_DEADLINE_PASSED"))
                    .andExpect(jsonPath("$.message").value(offerTooLate));
            Assertions.assertEquals(0, idempotencyRows(erin.id(), answer + "-late-" + offerLate.id()),
                    "a refused answer keeps no key");
        }
        Assertions.assertEquals(offerLate.version(), reload(offerLate.id()).version());

        command(erin, withdrawnLate.id(), "withdraw", "withdraw-late-" + withdrawnLate.id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.close_reason").value("WITHDRAWN"));
        Assertions.assertEquals("REFUNDING", adminDecides(arbitratedLate.id(),
                AfterSalesTrigger.ADMIN_APPROVE_PARTIAL, Money.of("1.00", "USD"), "Half of it was there."));

        Assertions.assertEquals(2, deadlineProcessor.fireDueDeadlines());
        assertRefunding(merchantLate, AfterSalesResolution.MERCHANT_TIMEOUT, merchantLate.requestedAmount());
        assertRefunding(offerLate, AfterSalesResolution.OFFER_TIMEOUT, new BigDecimal(OFFER));
        Assertions.assertEquals("CUSTOMER_WITHDRAW|REJECTED->CLOSED|CUSTOMER|" + erin.id() + "|null",
                lastStep(withdrawnLate.id()));
        AfterSalesCaseEntity arbitrated = reload(arbitratedLate.id());
        Assertions.assertEquals("ARBITRATION_PARTIAL", arbitrated.resolution());
        Assertions.assertEquals(new BigDecimal("1.00"), arbitrated.approvedAmount().setScale(2));
        Assertions.assertEquals(new BigDecimal("1.00"), refundAmount(arbitratedLate.id()));
        Assertions.assertEquals("ADMIN_APPROVE_PARTIAL|IN_ARBITRATION->REFUNDING|ADMIN|" + adminId() + "|1.00",
                lastStep(arbitratedLate.id()));
        Assertions.assertEquals(0, deadlineProcessor.fireDueDeadlines());
    }


    /**
     * A clock with nanoseconds, as on Linux, is read in microseconds, what the timestamp columns keep (the database
     * would round the rest): the opening's and the step's events carry the times of the stored case, occurred_at its
     * created_at or updated_at, data.opened_at and data.deadline_at its own, and the history rows have them too.
     */
    @Test
    void openingAndStepAtAClockWithNanoseconds_shouldCarryTheStoredTimesInTheirEvents() throws Exception {
        // After the order's completion and long before its window closes, with nanoseconds below the microsecond.
        LocalDateTime withNanos = LocalDateTime.now().plusSeconds(1).withNano(123_456_789);
        LocalDateTime inMicros = withNanos.withNano(123_456_000);
        AfterSalesTransitions writer = transitionsAt(() -> withNanos);
        Customer frank = customer("after-sales-micros-");
        OrderDto paid = paidOrder(frank);
        for (String next : List.of("ACCEPTED", "PREPARING", "COMPLETED")) {
            orderService.transitionOrderStatus(paid.id(), next);
        }
        AfterSalesCustomerService customers = new AfterSalesCustomerService(
                caseRepository,
                applicationContext.getBean(OrderRepository.class),
                applicationContext.getBean(OrderHistoryItemRepository.class),
                applicationContext.getBean(OrderPaymentRepository.class),
                writer,
                applicationContext.getBean(IdempotentRequests.class),
                applicationContext.getBean(AfterSalesCaseViews.class),
                applicationContext.getBean(AfterSalesDeadlines.class),
                () -> withNanos
        );
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        Long caseId = transaction.execute(status -> customers.open(frank.id(), paid.id(), new OpenAfterSalesBody(
                "WHOLE_ORDER", List.of(), "MISSING_ITEM", "The fries were missing."), "open-" + paid.id()).id());

        AfterSalesCaseEntity opened = reload(caseId);
        Assertions.assertEquals(List.of(inMicros, inMicros), List.of(opened.createdAt(), opened.updatedAt()));
        AfterSalesEventEnvelope openedEvent = lastEvent(caseId);
        Assertions.assertEquals("aftersales.case_opened", openedEvent.eventType());
        Assertions.assertEquals(opened.createdAt(), openedEvent.occurredAt());
        Assertions.assertEquals(opened.createdAt(), openedEvent.data().openedAt());
        Assertions.assertEquals(opened.deadlineAt(), openedEvent.data().deadlineAt());

        Long merchantId = customerIdOf(BURGER_KING_MERCHANT);
        transaction.executeWithoutResult(status -> Assertions.assertTrue(writer.fire(
                caseRepository.lockById(caseId),
                new AfterSalesTransitions.Command(AfterSalesTrigger.MERCHANT_REJECT, AfterSalesActor.MERCHANT,
                        merchantId, null, "Nothing was missing.", null)
        ).applied()));

        AfterSalesCaseEntity rejected = reload(caseId);
        Assertions.assertEquals("REJECTED", rejected.status());
        Assertions.assertEquals(inMicros, rejected.updatedAt());
        AfterSalesEventEnvelope rejectedEvent = lastEvent(caseId);
        Assertions.assertEquals("aftersales.request_rejected", rejectedEvent.eventType());
        Assertions.assertEquals(rejected.updatedAt(), rejectedEvent.occurredAt());
        Assertions.assertEquals(rejected.createdAt(), rejectedEvent.data().openedAt());
        Assertions.assertEquals(rejected.deadlineAt(), rejectedEvent.data().deadlineAt());
        Assertions.assertEquals(List.of(inMicros, inMicros), jdbcTemplate.queryForList(
                "SELECT occurred_at FROM after_sales_case_history WHERE case_id = ? ORDER BY id",
                LocalDateTime.class, caseId));
    }


    // ---- helpers ----


    record Customer(Long id, MockHttpSession session) {
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
        return new Customer(id, session);
    }


    Long customerIdOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
    }


    Long adminId() {
        return customerIdOf(ADMIN);
    }


    /**
     * A paid checkout at Burger King of its first two priced menu items.
     */
    OrderDto paidOrder(Customer customer) {
        for (Long menuItemId : jdbcTemplate.queryForList("""
                SELECT menu_items.id
                FROM menu_items
                JOIN restaurants ON restaurants.id = menu_items.restaurant_id
                WHERE restaurants.name = ?
                  AND menu_items.price > 0
                ORDER BY menu_items.id
                LIMIT 2
                """, Long.class, BURGER_KING)) {
            cartService.addMenuItemToCart(customer.id(), menuItemId);
        }
        return cartService.checkout(customer.id(), "PAID");
    }


    /**
     * A customer's whole-order request on a new completed order, moved into {@code status} the way the merchant, the
     * customer and an admin get it there: a counter-offer of {@value #OFFER}, a rejection, and an escalation of it.
     */
    AfterSalesCaseEntity caseIn(Customer customer, AfterSalesStatus status) {
        OrderDto paid = paidOrder(customer);
        for (String next : List.of("ACCEPTED", "PREPARING", "COMPLETED")) {
            orderService.transitionOrderStatus(paid.id(), next);
        }
        long caseId = afterSalesCustomerService.open(customer.id(), paid.id(), new OpenAfterSalesBody(
                "WHOLE_ORDER", List.of(), "MISSING_ITEM", "The fries were missing."), "open-" + paid.id()).id();
        switch (status) {
            case PENDING_MERCHANT -> {
            }
            case COUNTER_OFFERED ->
                    merchantAnswers(caseId, AfterSalesTrigger.MERCHANT_OFFER, Money.of(OFFER, "USD"), null);
            case REJECTED -> merchantAnswers(caseId, AfterSalesTrigger.MERCHANT_REJECT, null, "Nothing was missing.");
            case IN_ARBITRATION -> {
                merchantAnswers(caseId, AfterSalesTrigger.MERCHANT_REJECT, null, "Nothing was missing.");
                afterSalesCustomerService.escalate(customer.id(), caseId, "Please look again.", "escalate-" + caseId);
            }
            default -> throw new IllegalArgumentException("No open case in " + status);
        }
        AfterSalesCaseEntity current = reload(caseId);
        Assertions.assertEquals(status.name(), current.status());
        return current;
    }


    /**
     * The automatic refund of a paid order the customer cancelled: REFUNDING, with its refund and no deadline.
     */
    AfterSalesCaseEntity orderCancelledCase(Customer customer) {
        OrderDto paid = paidOrder(customer);
        orderService.idempotentCancelOrder(customer.id(), paid.id(), "cancel-" + paid.id());
        List<AfterSalesCaseEntity> cases = caseRepository.findAllByOrderId(paid.id());
        Assertions.assertEquals(1, cases.size());
        Assertions.assertEquals("REFUNDING", cases.get(0).status());
        Assertions.assertNull(cases.get(0).deadlineAt());
        return cases.get(0);
    }


    /**
     * Moves the case's deadline {@code minutesAgo} into the past, to the second, and returns it.
     */
    LocalDateTime makeDue(long caseId, int minutesAgo) {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(minutesAgo).withNano(0);
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE after_sales_cases SET deadline_at = ? WHERE id = ?", deadline, caseId));
        return deadline;
    }


    /**
     * The merchant's answer as the merchant module will give it: under the case's row lock, found by the restaurant,
     * through the single writer; a refusal is the 409 the merchant gets.
     *
     * @return the status the answer moved the case to
     */
    String merchantAnswers(long caseId, AfterSalesTrigger trigger, Money amount, String note) {
        Long restaurantId = jdbcTemplate.queryForObject(
                "SELECT restaurant_id FROM after_sales_cases WHERE id = ?", Long.class, caseId);
        Long merchantId = customerIdOf(BURGER_KING_MERCHANT);
        return new TransactionTemplate(transactionManager).execute(status -> {
            AfterSalesCaseEntity locked = caseRepository.lockByIdAndRestaurantId(caseId, restaurantId);
            return applied(locked, transitions.fire(locked, new AfterSalesTransitions.Command(
                    trigger, AfterSalesActor.MERCHANT, merchantId, amount, note, null)));
        });
    }


    /**
     * An admin's decision as the admin API will take it: under the case's row lock, through the single writer.
     *
     * @return the status the decision moved the case to
     */
    String adminDecides(long caseId, AfterSalesTrigger trigger, Money amount, String note) {
        Long adminId = adminId();
        return new TransactionTemplate(transactionManager).execute(status -> {
            AfterSalesCaseEntity locked = caseRepository.lockById(caseId);
            return applied(locked, transitions.fire(locked, new AfterSalesTransitions.Command(
                    trigger, AfterSalesActor.ADMIN, adminId, amount, note, "admin-" + caseId)));
        });
    }


    private static String applied(AfterSalesCaseEntity locked, AfterSalesTransitions.Step step) {
        if (step.decision() instanceof Decision.Rejected<AfterSalesStatus, AfterSalesTrigger> rejected) {
            throw AfterSalesErrors.refused(rejected, locked);
        }
        Assertions.assertTrue(step.applied(), () -> step.decision().toString());
        return step.current().status();
    }


    /**
     * What a command answered: its result, or "CONFLICT|code|message" when it was refused.
     */
    static String outcome(Callable<?> command) {
        try {
            return String.valueOf(command.call());
        } catch (ConflictException ex) {
            return "CONFLICT|" + ex.getCode() + "|" + ex.getMessage();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }


    ResultActions command(Customer customer, long caseId, String command, String key) throws Exception {
        return mockMvc.perform(post("/after-sales/cases/{id}/" + command, caseId)
                .session(customer.session())
                .header("Idempotency-Key", key));
    }


    int reschedule(Long caseId, Long version, LocalDateTime retryAt, int attempts) {
        Integer rows = new TransactionTemplate(transactionManager).execute(status ->
                caseRepository.rescheduleDeadline(caseId, version, retryAt, attempts));
        return rows == null ? -1 : rows;
    }


    AfterSalesCaseEntity reload(long caseId) {
        return caseRepository.findById(caseId).orElseThrow();
    }


    /**
     * The case the timeout moved from {@code waiting} into REFUNDING with {@code approved}, with one refund of that
     * amount, its SYSTEM history row and its event.
     */
    AfterSalesCaseEntity assertRefunding(
            AfterSalesCaseEntity waiting,
            AfterSalesResolution resolution,
            BigDecimal approved
    ) {
        AfterSalesCaseEntity refunding = reload(waiting.id());
        Assertions.assertEquals("REFUNDING", refunding.status(), () -> "case " + waiting.id());
        Assertions.assertEquals(resolution.name(), refunding.resolution());
        Assertions.assertEquals(Money.of(approved, "USD"), Money.of(refunding.approvedAmount(), refunding.currency()));
        Assertions.assertNull(refunding.closeReason());
        assertNoDeadline(refunding, waiting.version() + 1);
        Assertions.assertEquals(1, refunds(waiting.id()), "exactly one refund");
        Assertions.assertEquals(approved.setScale(2), refundAmount(waiting.id()));
        Assertions.assertEquals("PENDING|0|after-sales-case:" + waiting.id(), jdbcTemplate.queryForObject(
                "SELECT status || '|' || attempts || '|' || gateway_key FROM refunds WHERE case_id = ?",
                String.class, waiting.id()));
        // A timeout's history action is the trigger, which has the name of the resolution it sets.
        Assertions.assertEquals(resolution.name() + "|" + waiting.status() + "->REFUNDING|SYSTEM|null|"
                + approved.setScale(2), lastStep(waiting.id()));
        List<String> events = eventTypes(waiting.id());
        Assertions.assertEquals("aftersales.refund_approved", events.get(events.size() - 1));
        Assertions.assertEquals(1, events.stream().filter("aftersales.refund_approved"::equals).count());
        return refunding;
    }


    static void assertNoDeadline(AfterSalesCaseEntity moved, long version) {
        Assertions.assertNull(moved.deadlineAt());
        Assertions.assertNull(moved.deadlineRetryAt());
        Assertions.assertEquals(0, moved.deadlineAttempts());
        Assertions.assertEquals(version, moved.version());
    }


    static void assertRetryAbout(LocalDateTime retryAt, LocalDateTime before, LocalDateTime after) {
        Assertions.assertNotNull(retryAt);
        Assertions.assertFalse(retryAt.isBefore(before.plus(RETRY_BACKOFF).minusSeconds(1)), retryAt::toString);
        Assertions.assertFalse(retryAt.isAfter(after.plus(RETRY_BACKOFF).plusSeconds(1)), retryAt::toString);
    }


    /**
     * The case's history as "action|from->to|actor|actorId|amount".
     */
    List<String> history(long caseId) {
        return jdbcTemplate.query("""
                SELECT action, from_status, to_status, actor_type, actor_id, amount
                FROM after_sales_case_history
                WHERE case_id = ?
                ORDER BY id
                """, (row, index) -> {
            BigDecimal amount = row.getBigDecimal("amount");
            return row.getString("action") + "|" + row.getString("from_status") + "->" + row.getString("to_status")
                    + "|" + row.getString("actor_type") + "|" + row.getObject("actor_id") + "|"
                    + (amount == null ? null : amount.setScale(2));
        }, caseId);
    }


    String lastStep(long caseId) {
        List<String> steps = history(caseId);
        return steps.get(steps.size() - 1);
    }


    /**
     * The case's latest event, read back with its contract as a consumer reads it.
     */
    AfterSalesEventEnvelope lastEvent(long caseId) {
        String payload = jdbcTemplate.queryForObject("""
                SELECT payload
                FROM outbox_events
                WHERE aggregate_type = 'AFTER_SALES_CASE'
                  AND aggregate_id = ?
                ORDER BY aggregate_sequence DESC
                LIMIT 1
                """, String.class, caseId);
        return applicationContext.getBean(EventContracts.class)
                .read(EventTopic.AFTER_SALES_EVENTS, payload, AfterSalesEventEnvelope.class);
    }


    /**
     * The single writer of this context at {@code clock} instead of the system clock.
     */
    AfterSalesTransitions transitionsAt(Supplier<LocalDateTime> clock) {
        return new AfterSalesTransitions(
                caseRepository,
                applicationContext.getBean(AfterSalesCaseLineRepository.class),
                applicationContext.getBean(AfterSalesCaseHistoryRepository.class),
                applicationContext.getBean(RefundRepository.class),
                applicationContext.getBean(OrderRepository.class),
                applicationContext.getBean(OrderPaymentRepository.class),
                applicationContext.getBean(OrderEventOutboxService.class),
                applicationContext.getBean(ApplicationMetricsService.class),
                applicationContext.getBean(AfterSalesDeadlines.class),
                applicationContext.getBeanProvider(Tracer.class),
                applicationContext.getEnvironment().getProperty("app.after-sales.topic", "after-sales-events"),
                clock
        );
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


    /**
     * The cases in the order their timeout events were written.
     */
    List<Long> timeoutEventOrder(List<Long> caseIds) {
        List<Long> order = new ArrayList<>();
        jdbcTemplate.query("""
                SELECT aggregate_id, id
                FROM outbox_events
                WHERE aggregate_type = 'AFTER_SALES_CASE'
                  AND event_type IN ('aftersales.refund_approved', 'aftersales.case_closed')
                ORDER BY id
                """, row -> {
            long caseId = row.getLong("aggregate_id");
            if (caseIds.contains(caseId)) {
                order.add(caseId);
            }
        });
        return order;
    }


    int refunds(long caseId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refunds WHERE case_id = ?", Integer.class, caseId);
    }


    BigDecimal refundAmount(long caseId) {
        return jdbcTemplate.queryForObject("SELECT amount FROM refunds WHERE case_id = ?", BigDecimal.class, caseId)
                .setScale(2);
    }


    int idempotencyRows(Long customerId, String key) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_requests WHERE customer_id = ? AND idempotency_key = ?",
                Integer.class, customerId, key);
    }


    double deadlineCount(AfterSalesTrigger trigger, String outcome) {
        Counter counter = meterRegistry.find("onlineorder.aftersales.deadlines")
                .tags("trigger", trigger.name().toLowerCase(Locale.ROOT), "outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }
}
