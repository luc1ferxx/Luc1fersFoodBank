package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.messaging.AfterSalesEventEnvelope;
import com.laioffer.onlineorder.messaging.contract.EventContracts;
import com.laioffer.onlineorder.messaging.contract.EventTopic;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.OrderPaymentRepository;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.CustomerService;
import com.laioffer.onlineorder.service.OrderService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;


import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * The refund executor (ADR 0002 D8, sections 9, 19.4 and 19.9) on a real database, called directly instead of from
 * the poller's thread, with the simulated gateway behind a spy: a due refund succeeds and refunds its case (REFUNDED,
 * refunded_amount, history and aftersales.case_refunded); a retryable failure waits for an exponential backoff and
 * succeeds later; a permanent failure gives the refund up and leaves its case in REFUNDING; an expired lease is
 * reclaimed with the same gateway key, or given up once the attempts are used up; a refund above the order's capture
 * is given up before the gateway is called; the customer never sees the gateway's error; expired leases are claimed
 * before due PENDING refunds; and a refund whose claim transaction fails backs off alone (section 24). The refunds
 * come from the two ways a case enters REFUNDING: the cancel of a paid order and a merchant's approval. The cases have
 * to hold on PostgreSQL and on H2 (the {@code h2} profile), so each database gets a subclass; every test leaves no
 * refund due, so that each run of the executor only meets the test's own refunds.
 */
abstract class AbstractRefundExecutorIntegrationTests {

    static final String BURGER_KING = "Burger King";

    static final String BURGER_KING_MERCHANT = "merchant.burgerking@laifood.com";

    static final String AFTER_SALES_TOPIC = "after-sales-events";

    /**
     * app.after-sales.refund.retry-backoff as application.yml sets it.
     */
    static final Duration RETRY_BACKOFF = Duration.ofSeconds(10);

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
    AfterSalesCaseRepository caseRepository;

    @Autowired
    RefundRepository refundRepository;

    @Autowired
    AfterSalesTransitions transitions;

    @Autowired
    AfterSalesCustomerService afterSalesCustomerService;

    @Autowired
    RefundExecutor refundExecutor;

    @Autowired
    EventContracts eventContracts;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    OrderPaymentRepository orderPaymentRepository;

    @Autowired
    ApplicationMetricsService metricsService;

    /**
     * The simulated gateway, which a test can make fail.
     */
    @MockitoSpyBean
    RefundGateway gateway;


    /**
     * Both ways into REFUNDING end in REFUNDED: the refund SUCCEEDED with the gateway's reference after one call, the
     * case REFUNDED with refunded_amount, its SYSTEM history row and its aftersales.case_refunded event, and the
     * customer sees the refund completed. A second run finds nothing.
     */
    @Test
    void dueRefunds_shouldSucceedAndRefundTheirCases() throws Exception {
        Customer alice = customer("refund-success-");
        AfterSalesCaseEntity cancelled = cancelledCase(alice);
        AfterSalesCaseEntity approved = approvedRequest(alice);
        double succeededBefore = refundCount(RefundExecutor.SUCCEEDED);

        Assertions.assertEquals(2, refundExecutor.runDueRefunds());

        for (AfterSalesCaseEntity refunding : List.of(cancelled, approved)) {
            RefundEntity refund = assertSucceeded(refunding, 1);
            Mockito.verify(gateway).refund(new RefundGatewayRequest(refund.gatewayKey(), refunding.orderId(),
                    Money.of(refunding.approvedAmount(), refunding.currency()), 1, refund.paymentReference()));
            JsonNode detail = json(mockMvc.perform(get("/after-sales/cases/{id}", refunding.id())
                            .session(alice.session()))
                    .andExpect(status().isOk()));
            Assertions.assertEquals("REFUNDED", detail.get("status").asString());
            Assertions.assertEquals(Money.of(refunding.approvedAmount(), refunding.currency()),
                    Money.of(detail.get("refunded_amount").decimalValue(), detail.get("currency").asString()));
            Assertions.assertEquals("SUCCEEDED", detail.get("refund").get("status").asString());
            Assertions.assertEquals(refund.providerReference(),
                    detail.get("refund").get("provider_reference").asString());
        }
        Assertions.assertEquals(succeededBefore + 2, refundCount(RefundExecutor.SUCCEEDED));
        Assertions.assertEquals(0, refundExecutor.runDueRefunds());
        Mockito.verify(gateway, Mockito.times(2)).refund(Mockito.any());
    }


    /**
     * A retryable failure sends the refund back to PENDING for retry-backoff x 2^(attempts - 1), with the gateway's
     * error kept for the staff and hidden from the customer; it is not claimed before then, and succeeds on the third
     * attempt.
     */
    @Test
    void retryableFailures_shouldBackOffExponentiallyAndThenSucceed() throws Exception {
        Customer bob = customer("refund-retry-");
        AfterSalesCaseEntity refunding = cancelledCase(bob);
        Mockito.doReturn(new RefundGatewayResult.RetryableFailure("gateway unavailable"))
                .doReturn(new RefundGatewayResult.RetryableFailure("gateway still unavailable"))
                .doCallRealMethod()
                .when(gateway).refund(Mockito.any());
        double retriedBefore = refundCount(RefundExecutor.RETRIED);

        LocalDateTime before = LocalDateTime.now();
        Assertions.assertEquals(1, refundExecutor.runDueRefunds());
        LocalDateTime after = LocalDateTime.now();

        RefundEntity waiting = refundOf(refunding.id());
        Assertions.assertEquals("PENDING|1|gateway unavailable|null", state(waiting));
        assertAbout(waiting.nextAttemptAt(), before.plus(RETRY_BACKOFF), after.plus(RETRY_BACKOFF));
        Assertions.assertEquals(refunding, reload(refunding.id()), "the case waits in REFUNDING");
        Assertions.assertEquals(List.of("aftersales.case_opened"), eventTypes(refunding.id()));
        Assertions.assertEquals(retriedBefore + 1, refundCount(RefundExecutor.RETRIED));
        // The customer sees the refund waiting, without the gateway's error.
        JsonNode detail = json(mockMvc.perform(get("/after-sales/cases/{id}", refunding.id()).session(bob.session()))
                .andExpect(status().isOk()));
        Assertions.assertEquals("PENDING", detail.get("refund").get("status").asString());
        Assertions.assertEquals(1, detail.get("refund").get("attempts").asInt());
        Assertions.assertFalse(detail.get("refund").has("last_error"), detail.get("refund").toString());
        JsonNode listed = json(mockMvc.perform(get("/after-sales/cases").session(bob.session()))
                .andExpect(status().isOk())).get("cases").get(0);
        Assertions.assertEquals(refunding.id().longValue(), listed.get("id").asLong());
        Assertions.assertFalse(listed.get("refund").has("last_error"), listed.get("refund").toString());

        Assertions.assertEquals(0, refundExecutor.runDueRefunds(), "not due before its backoff");

        makeDue(waiting.id());
        before = LocalDateTime.now();
        Assertions.assertEquals(1, refundExecutor.runDueRefunds());
        after = LocalDateTime.now();
        RefundEntity waitingLonger = refundOf(refunding.id());
        Assertions.assertEquals("PENDING|2|gateway still unavailable|null", state(waitingLonger));
        assertAbout(waitingLonger.nextAttemptAt(), before.plus(RETRY_BACKOFF.multipliedBy(2)),
                after.plus(RETRY_BACKOFF.multipliedBy(2)));

        makeDue(waiting.id());
        Assertions.assertEquals(1, refundExecutor.runDueRefunds());
        assertSucceeded(refunding, 3);
        ArgumentCaptor<RefundGatewayRequest> calls = ArgumentCaptor.forClass(RefundGatewayRequest.class);
        Mockito.verify(gateway, Mockito.times(3)).refund(calls.capture());
        Assertions.assertEquals(List.of(1, 2, 3), calls.getAllValues().stream().map(RefundGatewayRequest::attempt)
                .toList());
        Assertions.assertEquals(1, calls.getAllValues().stream().map(RefundGatewayRequest::gatewayKey).distinct()
                .count(), "one gateway key for every attempt");
    }


    /**
     * A permanent failure gives the refund up at once: FAILED with the gateway's reason, not claimed again, and its
     * case left in REFUNDING for an admin.
     */
    @Test
    void permanentFailure_shouldFailTheRefundAndLeaveItsCaseRefunding() {
        Customer carol = customer("refund-permanent-");
        AfterSalesCaseEntity refunding = approvedRequest(carol);
        Mockito.doReturn(new RefundGatewayResult.PermanentFailure("capture already refunded"))
                .when(gateway).refund(Mockito.any());
        double failedBefore = refundCount(RefundExecutor.FAILED);

        Assertions.assertEquals(1, refundExecutor.runDueRefunds());

        RefundEntity failed = refundOf(refunding.id());
        Assertions.assertEquals("FAILED|1|capture already refunded|null", state(failed));
        Assertions.assertNull(failed.nextAttemptAt());
        Assertions.assertNull(failed.claimedUntil());
        Assertions.assertEquals(refunding, reload(refunding.id()));
        Assertions.assertEquals(List.of("aftersales.case_opened", "aftersales.refund_approved"),
                eventTypes(refunding.id()));
        Assertions.assertEquals(failedBefore + 1, refundCount(RefundExecutor.FAILED));
        Assertions.assertEquals(0, refundExecutor.runDueRefunds(), "a FAILED refund waits for an admin");
    }


    /**
     * An executor that died after its claim leaves the refund PROCESSING; once the lease ran out the next executor
     * reclaims it as another attempt and calls the gateway with the same key. A lease still running is left alone.
     */
    @Test
    void expiredLease_shouldBeReclaimedWithTheSameGatewayKey() {
        Customer dave = customer("refund-reclaim-");
        AfterSalesCaseEntity abandoned = cancelledCase(dave);
        AfterSalesCaseEntity leased = cancelledCase(dave);
        RefundEntity stalled = refundOf(abandoned.id());
        leaseAsIfClaimed(stalled.id(), 1, LocalDateTime.now().minusSeconds(1));
        LocalDateTime leaseEnd = LocalDateTime.now().plusDays(1).withNano(0);
        leaseAsIfClaimed(refundOf(leased.id()).id(), 1, leaseEnd);

        Assertions.assertEquals(1, refundExecutor.runDueRefunds());

        assertSucceeded(abandoned, 2);
        Mockito.verify(gateway).refund(new RefundGatewayRequest(stalled.gatewayKey(), abandoned.orderId(),
                Money.of(abandoned.approvedAmount(), abandoned.currency()), 2, stalled.paymentReference()));
        RefundEntity stillLeased = refundOf(leased.id());
        Assertions.assertEquals("PROCESSING|1|null|null", state(stillLeased));
        Assertions.assertEquals(leaseEnd, stillLeased.claimedUntil());
        Assertions.assertEquals("REFUNDING", reload(leased.id()).status());
    }


    /**
     * Section 19.9: an expired lease with the attempts used up is not reclaimed but given up, without another call;
     * the case stays in REFUNDING.
     */
    @Test
    void expiredLease_withTheAttemptsUsedUp_shouldBeGivenUpWithoutACall() {
        Customer erin = customer("refund-exhausted-");
        AfterSalesCaseEntity refunding = cancelledCase(erin);
        RefundEntity stalled = refundOf(refunding.id());
        leaseAsIfClaimed(stalled.id(), refundExecutor.settings().maxAttempts(), LocalDateTime.now().minusSeconds(1));
        double failedBefore = refundCount(RefundExecutor.FAILED);

        Assertions.assertEquals(1, refundExecutor.runDueRefunds());

        Assertions.assertEquals("FAILED|8|The lease of attempt 8 expired without a result and no attempt is left|null",
                state(refundOf(refunding.id())));
        Mockito.verify(gateway, Mockito.never()).refund(Mockito.any());
        Assertions.assertEquals("REFUNDING", reload(refunding.id()).status());
        Assertions.assertEquals(failedBefore + 1, refundCount(RefundExecutor.FAILED));
        Assertions.assertEquals(0, refundExecutor.runDueRefunds());
    }


    /**
     * Section 19.4: a refund the order's payment no longer covers (a capture of less, or none at all) is given up after
     * its claim and before any money moves.
     */
    @Test
    void refundTheCaptureNoLongerCovers_shouldBeGivenUpBeforeTheGateway() {
        Customer frank = customer("refund-mismatch-");
        AfterSalesCaseEntity overCaptured = cancelledCase(frank);
        AfterSalesCaseEntity uncaptured = cancelledCase(frank);
        jdbcTemplate.update("UPDATE order_payments SET amount = 1.00 WHERE order_id = ?", overCaptured.orderId());
        String paymentReference = refundOf(overCaptured.id()).paymentReference();
        jdbcTemplate.update("DELETE FROM order_payments WHERE order_id = ?", uncaptured.orderId());
        double failedBefore = refundCount(RefundExecutor.FAILED);

        Assertions.assertEquals(2, refundExecutor.runDueRefunds());

        Money amount = Money.of(overCaptured.approvedAmount(), overCaptured.currency());
        Assertions.assertEquals("FAILED|1|The refund of " + amount + " exceeds the 1.00 USD captured by payment "
                + paymentReference + "|null", state(refundOf(overCaptured.id())));
        Assertions.assertEquals("FAILED|1|Order " + uncaptured.orderId() + " has no captured payment|null",
                state(refundOf(uncaptured.id())));
        Mockito.verify(gateway, Mockito.never()).refund(Mockito.any());
        Assertions.assertEquals("REFUNDING", reload(overCaptured.id()).status());
        Assertions.assertEquals("REFUNDING", reload(uncaptured.id()).status());
        Assertions.assertEquals(failedBefore + 2, refundCount(RefundExecutor.FAILED));
    }


    /**
     * Section 24: the claim takes expired leases before due PENDING refunds, whatever their ids (here the PENDING
     * refund is the older one), so a steady stream of PENDING refunds cannot starve a stalled refund.
     */
    @Test
    void expiredLeases_shouldBeClaimedBeforeDuePendingRefunds() {
        Customer grace = customer("refund-claim-order-");
        AfterSalesCaseEntity pending = cancelledCase(grace);
        AfterSalesCaseEntity stalled = cancelledCase(grace);
        RefundEntity stalledRefund = refundOf(stalled.id());
        leaseAsIfClaimed(stalledRefund.id(), 1, LocalDateTime.now().minusSeconds(1));

        Assertions.assertEquals(2, refundExecutor.runDueRefunds());

        ArgumentCaptor<RefundGatewayRequest> calls = ArgumentCaptor.forClass(RefundGatewayRequest.class);
        Mockito.verify(gateway, Mockito.times(2)).refund(calls.capture());
        Assertions.assertEquals(List.of(stalledRefund.gatewayKey(), refundOf(pending.id()).gatewayKey()),
                calls.getAllValues().stream().map(RefundGatewayRequest::gatewayKey).toList());
        assertSucceeded(stalled, 2);
        assertSucceeded(pending, 1);
    }


    /**
     * Section 24: a refund whose claim transaction fails after the claim (here the lookup of its order's payment) rolls
     * back and backs off alone, at the version it was claimed at and without using up an attempt: an expired lease's
     * end and a PENDING refund's next attempt move one retry backoff ahead, with the failure as last_error. The
     * refund behind them is still executed in the same run.
     */
    @Test
    void refundsWhoseClaimFails_shouldBackOffAloneWhileTheNextOneSucceeds() {
        Customer heidi = customer("refund-claim-fails-");
        AfterSalesCaseEntity failingPending = cancelledCase(heidi);
        AfterSalesCaseEntity failingLease = cancelledCase(heidi);
        AfterSalesCaseEntity healthy = cancelledCase(heidi);
        RefundEntity pendingBefore = refundOf(failingPending.id());
        leaseAsIfClaimed(refundOf(failingLease.id()).id(), 1, LocalDateTime.now().minusSeconds(1));
        RefundEntity leaseBefore = refundOf(failingLease.id());
        OrderPaymentRepository payments = Mockito.mock(
                OrderPaymentRepository.class, AdditionalAnswers.delegatesTo(orderPaymentRepository));
        for (AfterSalesCaseEntity failing : List.of(failingPending, failingLease)) {
            Mockito.doThrow(new IllegalStateException("payments unavailable"))
                    .when(payments).findByOrderId(failing.orderId());
        }
        RefundExecutor failingLookups = new RefundExecutor(refundRepository, caseRepository, payments, transitions,
                gateway, metricsService, transactionManager, refundExecutor.settings(), LocalDateTime::now);
        String error = "The claim of attempt %d failed: java.lang.IllegalStateException: payments unavailable";
        double backedOffBefore = refundCount(RefundExecutor.BACKED_OFF);
        try {
            LocalDateTime before = LocalDateTime.now();
            Assertions.assertEquals(3, failingLookups.runDueRefunds());
            LocalDateTime after = LocalDateTime.now();

            RefundEntity backedOffLease = refundOf(failingLease.id());
            Assertions.assertEquals("PROCESSING|1|" + error.formatted(2) + "|null", state(backedOffLease));
            Assertions.assertEquals(leaseBefore.version(), backedOffLease.version());
            Assertions.assertEquals(1, backedOffLease.claimFailures());
            assertAbout(backedOffLease.claimedUntil(), before.plus(RETRY_BACKOFF), after.plus(RETRY_BACKOFF));
            RefundEntity backedOffPending = refundOf(failingPending.id());
            Assertions.assertEquals("PENDING|0|" + error.formatted(1) + "|null", state(backedOffPending));
            Assertions.assertEquals(pendingBefore.version(), backedOffPending.version());
            Assertions.assertEquals(1, backedOffPending.claimFailures());
            assertAbout(backedOffPending.nextAttemptAt(), before.plus(RETRY_BACKOFF), after.plus(RETRY_BACKOFF));
            assertSucceeded(healthy, 1);
            Mockito.verify(gateway, Mockito.times(1)).refund(Mockito.any());
            Assertions.assertEquals(backedOffBefore + 2, refundCount(RefundExecutor.BACKED_OFF));
            Assertions.assertEquals(0, failingLookups.runDueRefunds(), "neither is due before its backoff");
        } finally {
            failingLookups.destroy();
        }

        // Due again with the payments readable, both go through: the expired lease as its second attempt.
        makeDue(pendingBefore.id());
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE refunds SET claimed_until = ? WHERE id = ?",
                LocalDateTime.now().minusSeconds(1), leaseBefore.id()));
        Assertions.assertEquals(2, refundExecutor.runDueRefunds());
        assertSucceeded(failingLease, 2);
        assertSucceeded(failingPending, 1);
        Assertions.assertEquals(0, refundOf(failingLease.id()).claimFailures(), "a step starts the count over");
        Assertions.assertEquals(0, refundOf(failingPending.id()).claimFailures());
    }


    /**
     * Section 27, follow-up 9: the claims of a refund that keep failing are counted in a row, and the
     * max-claim-failures-th one gives the refund up (FAILED, its case still REFUNDING, no attempt used and no call
     * made) instead of backing it off forever; an expired lease the same way, with its attempts kept.
     */
    @Test
    void refundsWhoseClaimsKeepFailing_shouldBeGivenUpByTheLastFailureInARow() {
        Customer ivan = customer("refund-claims-keep-failing-");
        AfterSalesCaseEntity failingPending = cancelledCase(ivan);
        AfterSalesCaseEntity failingLease = cancelledCase(ivan);
        Long pendingId = refundOf(failingPending.id()).id();
        Long leaseId = refundOf(failingLease.id()).id();
        leaseAsIfClaimed(leaseId, 2, LocalDateTime.now().minusSeconds(1));
        int maxClaimFailures = refundExecutor.settings().maxClaimFailures();
        // As if both had backed off all but two times.
        Assertions.assertEquals(2, jdbcTemplate.update("UPDATE refunds SET claim_failures = ? WHERE id IN (?, ?)",
                maxClaimFailures - 2, pendingId, leaseId));
        OrderPaymentRepository payments = Mockito.mock(
                OrderPaymentRepository.class, AdditionalAnswers.delegatesTo(orderPaymentRepository));
        for (AfterSalesCaseEntity failing : List.of(failingPending, failingLease)) {
            Mockito.doThrow(new IllegalStateException("payments unavailable"))
                    .when(payments).findByOrderId(failing.orderId());
        }
        RefundExecutor failingLookups = new RefundExecutor(refundRepository, caseRepository, payments, transitions,
                gateway, metricsService, transactionManager, refundExecutor.settings(), LocalDateTime::now);
        String error = "The claim of attempt %d failed: java.lang.IllegalStateException: payments unavailable";
        double backedOffBefore = refundCount(RefundExecutor.BACKED_OFF);
        double failedBefore = refundCount(RefundExecutor.FAILED);
        try {
            Assertions.assertEquals(2, failingLookups.runDueRefunds());
            Assertions.assertEquals(maxClaimFailures - 1, refundRepository.findById(pendingId).orElseThrow()
                    .claimFailures());
            Assertions.assertEquals(maxClaimFailures - 1, refundRepository.findById(leaseId).orElseThrow()
                    .claimFailures());
            Assertions.assertEquals(backedOffBefore + 2, refundCount(RefundExecutor.BACKED_OFF));

            makeDue(pendingId);
            Assertions.assertEquals(1, jdbcTemplate.update("UPDATE refunds SET claimed_until = ? WHERE id = ?",
                    LocalDateTime.now().minusSeconds(1), leaseId));
            Assertions.assertEquals(2, failingLookups.runDueRefunds());

            String inARow = maxClaimFailures + " claims failed in a row; the last one: ";
            RefundEntity givenUpPending = refundRepository.findById(pendingId).orElseThrow();
            Assertions.assertEquals("FAILED|0|" + inARow + error.formatted(1) + "|null", state(givenUpPending));
            Assertions.assertNull(givenUpPending.nextAttemptAt());
            Assertions.assertEquals(0, givenUpPending.claimFailures());
            RefundEntity givenUpLease = refundRepository.findById(leaseId).orElseThrow();
            Assertions.assertEquals("FAILED|2|" + inARow + error.formatted(3) + "|null", state(givenUpLease));
            Assertions.assertNull(givenUpLease.claimedUntil());
            Assertions.assertEquals(backedOffBefore + 2, refundCount(RefundExecutor.BACKED_OFF));
            Assertions.assertEquals(failedBefore + 2, refundCount(RefundExecutor.FAILED));
            Assertions.assertEquals("REFUNDING", reload(failingPending.id()).status());
            Assertions.assertEquals("REFUNDING", reload(failingLease.id()).status());
            Mockito.verify(gateway, Mockito.never()).refund(Mockito.any());
            Assertions.assertEquals(0, failingLookups.runDueRefunds(), "a FAILED refund waits for an admin");
        } finally {
            failingLookups.destroy();
        }
    }


    /**
     * Section 27, follow-up 10: the application's registry shows the gateway threads and has the saturation counter
     * from the start.
     */
    @Test
    void gatewayThreadMeters_shouldBeBoundToTheApplicationRegistry() {
        Assertions.assertEquals(refundExecutor.settings().gatewayThreads(),
                meterRegistry.get(RefundExecutor.GATEWAY_THREADS_MAX).gauge().value());
        Assertions.assertEquals(0.0, meterRegistry.get(RefundExecutor.GATEWAY_THREADS_BUSY).gauge().value());
        Assertions.assertNotNull(meterRegistry.get(ApplicationMetricsService.AFTER_SALES_REFUND_GATEWAY_SATURATED)
                .counter());
    }


    // ---- helpers ----


    record Customer(Long id, MockHttpSession session) {
    }


    Customer customer(String prefix) {
        String email = prefix + System.nanoTime() + "@example.com";
        customerService.signUp(email, "demo123", "Refund", "Tester");
        Long id = jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
        try {
            MockHttpSession session = (MockHttpSession) mockMvc.perform(post("/login")
                            .param("username", email)
                            .param("password", "demo123"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getRequest()
                    .getSession(false);
            return new Customer(id, session);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
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
     * The automatic refund of a paid order the customer cancelled: REFUNDING with a PENDING refund due at once.
     */
    AfterSalesCaseEntity cancelledCase(Customer customer) {
        OrderDto paid = paidOrder(customer);
        orderService.idempotentCancelOrder(customer.id(), paid.id(), "cancel-" + paid.id());
        List<AfterSalesCaseEntity> cases = caseRepository.findAllByOrderId(paid.id());
        Assertions.assertEquals(1, cases.size());
        Assertions.assertEquals("REFUNDING", cases.get(0).status());
        return cases.get(0);
    }


    /**
     * A customer's whole-order request on a new completed order that the merchant approved, under the case's row lock
     * through the single writer as the merchant module will: REFUNDING with a PENDING refund due at once.
     */
    AfterSalesCaseEntity approvedRequest(Customer customer) {
        OrderDto paid = paidOrder(customer);
        for (String next : List.of("ACCEPTED", "PREPARING", "COMPLETED")) {
            orderService.transitionOrderStatus(paid.id(), next);
        }
        long caseId = afterSalesCustomerService.open(customer.id(), paid.id(), new OpenAfterSalesBody(
                "WHOLE_ORDER", List.of(), "MISSING_ITEM", "The fries were missing."), "open-" + paid.id()).id();
        Long merchantId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE email = ?", Long.class, BURGER_KING_MERCHANT);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            AfterSalesCaseEntity locked = caseRepository.lockById(caseId);
            Assertions.assertTrue(transitions.fire(locked, new AfterSalesTransitions.Command(
                    AfterSalesTrigger.MERCHANT_APPROVE, AfterSalesActor.MERCHANT, merchantId, null, null, null))
                    .applied());
        });
        AfterSalesCaseEntity refunding = reload(caseId);
        Assertions.assertEquals("REFUNDING", refunding.status());
        return refunding;
    }


    /**
     * The state of a refund an executor claimed and then stopped calling for: PROCESSING after {@code attempts}, with
     * a lease ending at {@code claimedUntil}.
     */
    void leaseAsIfClaimed(Long refundId, int attempts, LocalDateTime claimedUntil) {
        Assertions.assertEquals(1, jdbcTemplate.update("""
                UPDATE refunds
                SET status = 'PROCESSING', attempts = ?, next_attempt_at = NULL, claimed_until = ?, version = version + 1
                WHERE id = ?
                """, attempts, claimedUntil, refundId));
    }


    void makeDue(Long refundId) {
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE refunds SET next_attempt_at = ? WHERE id = ?", LocalDateTime.now().minusSeconds(1), refundId));
    }


    /**
     * The refund SUCCEEDED after {@code attempts} with the simulated gateway's reference of its key, and its case
     * REFUNDED by the executor: refunded_amount equal to the approved amount, one version later, the SYSTEM history
     * row and the aftersales.case_refunded event.
     */
    RefundEntity assertSucceeded(AfterSalesCaseEntity refunding, int attempts) {
        RefundEntity refund = refundOf(refunding.id());
        Assertions.assertEquals("SUCCEEDED|" + attempts + "|null|"
                + SimulatedRefundGateway.reference("after-sales-case:" + refunding.id()), state(refund));
        Assertions.assertNotNull(refund.succeededAt());
        Assertions.assertNull(refund.nextAttemptAt());
        Assertions.assertNull(refund.claimedUntil());

        AfterSalesCaseEntity refunded = reload(refunding.id());
        Money approved = Money.of(refunding.approvedAmount(), refunding.currency());
        Assertions.assertEquals("REFUNDED", refunded.status());
        Assertions.assertEquals(approved, Money.of(refunded.refundedAmount(), refunded.currency()));
        Assertions.assertEquals(refunding.version() + 1, refunded.version());
        Assertions.assertNull(refunded.deadlineAt());
        Assertions.assertEquals("REFUND_SUCCEEDED|REFUNDING->REFUNDED|SYSTEM|null|" + approved.amount(),
                lastStep(refunding.id()));

        List<Map<String, Object>> events = jdbcTemplate.queryForList("""
                SELECT topic, event_key, event_type, payload
                FROM outbox_events
                WHERE aggregate_type = 'AFTER_SALES_CASE'
                  AND aggregate_id = ?
                ORDER BY aggregate_sequence
                """, refunding.id());
        Assertions.assertEquals(1, events.stream().filter(row ->
                "aftersales.case_refunded".equals(row.get("event_type"))).count(), "one case_refunded event");
        Map<String, Object> last = events.get(events.size() - 1);
        Assertions.assertEquals("aftersales.case_refunded", last.get("event_type"));
        Assertions.assertEquals(AFTER_SALES_TOPIC, last.get("topic"));
        Assertions.assertEquals(String.valueOf(refunding.orderId()), last.get("event_key"));
        AfterSalesEventEnvelope event = eventContracts.read(
                EventTopic.AFTER_SALES_EVENTS, String.valueOf(last.get("payload")), AfterSalesEventEnvelope.class);
        Assertions.assertEquals(refunding.id(), event.aggregateId());
        Assertions.assertEquals("REFUNDED", event.data().status());
        Assertions.assertEquals("REFUND_SUCCEEDED", event.data().action());
        Assertions.assertEquals("SYSTEM", event.data().actorType());
        Assertions.assertNull(event.data().actorId());
        Assertions.assertEquals(refunded.version(), event.data().version());
        Assertions.assertEquals(approved, Money.of(event.data().refundedAmount(), event.data().currency()));
        return refund;
    }


    RefundEntity refundOf(Long caseId) {
        List<RefundEntity> refunds = refundRepository.findAllByCaseIdIn(List.of(caseId));
        Assertions.assertEquals(1, refunds.size(), "one refund per case");
        return refunds.get(0);
    }


    AfterSalesCaseEntity reload(Long caseId) {
        return caseRepository.findById(caseId).orElseThrow();
    }


    /**
     * status|attempts|last_error|provider_reference.
     */
    static String state(RefundEntity refund) {
        return refund.status() + "|" + refund.attempts() + "|" + refund.lastError() + "|" + refund.providerReference();
    }


    static void assertAbout(LocalDateTime actual, LocalDateTime earliest, LocalDateTime latest) {
        Assertions.assertNotNull(actual);
        Assertions.assertFalse(actual.isBefore(earliest.minusSeconds(1)), actual + " before " + earliest);
        Assertions.assertFalse(actual.isAfter(latest.plusSeconds(1)), actual + " after " + latest);
    }


    /**
     * The case's last step as "action|from->to|actor|actorId|amount".
     */
    String lastStep(Long caseId) {
        List<String> steps = jdbcTemplate.query("""
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
        return steps.get(steps.size() - 1);
    }


    List<String> eventTypes(Long caseId) {
        return jdbcTemplate.queryForList("""
                SELECT event_type
                FROM outbox_events
                WHERE aggregate_type = 'AFTER_SALES_CASE'
                  AND aggregate_id = ?
                ORDER BY aggregate_sequence
                """, String.class, caseId);
    }


    double refundCount(String outcome) {
        Counter counter = meterRegistry.find("onlineorder.aftersales.refunds").tags("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }


    JsonNode json(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
