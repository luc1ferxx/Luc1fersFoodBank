package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.audit.AuditActions;
import com.laioffer.onlineorder.audit.AuditActor;
import com.laioffer.onlineorder.audit.AuditLogService;
import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.statemachine.Decision;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;


/**
 * The admin's after-sales service (ADR 0002 D10, D11, sections 5.2, 5.4, 9 and 11.4) on mocks: the shape checks of an
 * arbitration before anything is read, the decision fired as the ADMIN under the case's row lock and audited only when
 * it applied, the amount's currency rules, the replay, the queue's order, and the retry of a refund decided by the
 * refund machine under the refund's row lock with the time read after it.
 */
@ExtendWith(MockitoExtension.class)
class AfterSalesAdminServiceTests {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 12, 0, 30);

    static final Long ADMIN_ID = 1L;

    static final AuditActor ADMIN = AuditActor.admin(ADMIN_ID);

    static final Long CUSTOMER_ID = 3L;

    static final Long ORDER_ID = 1001L;

    static final Long ORDER_NO = 7_254_189_912_938_504_192L;

    static final Long CASE_ID = 501L;

    static final Long REFUND_ID = 70L;

    static final String SCOPE = "after-sales-arbitrate:v1:501";

    @Mock
    private AfterSalesCaseRepository caseRepository;

    @Mock
    private RefundRepository refundRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private AfterSalesTransitions transitions;

    @Mock
    private IdempotentRequests idempotentRequests;

    @Mock
    private AfterSalesCaseViews views;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private Supplier<LocalDateTime> clock;

    private AfterSalesAdminService service;


    @BeforeEach
    void setup() {
        service = new AfterSalesAdminService(caseRepository, refundRepository, orderRepository, transitions,
                idempotentRequests, views, auditLogService, clock);
    }


    // ---- arbitrate ----


    /**
     * The decision is fired on the case as locked, as the ADMIN with the trimmed note and the amount in the case's
     * currency, inside the idempotent request of the admin's key; the audit entry follows the applied step and names
     * the amount it approved.
     */
    @Test
    void arbitrate_partial_shouldFireUnderTheCasesLockAndAuditTheDecision() {
        runRequestsInline();
        AfterSalesCaseEntity locked = caseRow(AfterSalesStatus.IN_ARBITRATION, "USD");
        AfterSalesCaseEntity refunding = refunding(locked, "5.00", AfterSalesResolution.ARBITRATION_PARTIAL);
        Mockito.when(caseRepository.lockById(CASE_ID)).thenReturn(locked);
        Mockito.when(transitions.fire(Mockito.same(locked), Mockito.any())).thenReturn(applied(
                AfterSalesTrigger.ADMIN_APPROVE_PARTIAL, AfterSalesStatus.REFUNDING, refunding));
        AfterSalesCaseDto answer = stubDetail(refunding);

        AfterSalesCaseDto result = service.arbitrate(CASE_ID, body("PARTIAL", "5", "  Half of the meal was missing. "),
                " arbitrate-1 ", ADMIN);

        Assertions.assertSame(answer, result);
        InOrder order = Mockito.inOrder(idempotentRequests, caseRepository, transitions, auditLogService);
        order.verify(idempotentRequests).run(Mockito.eq(ADMIN_ID), Mockito.eq(SCOPE), Mockito.eq("arbitrate-1"),
                Mockito.anyString(), Mockito.any());
        order.verify(caseRepository).lockById(CASE_ID);
        order.verify(transitions).fire(Mockito.same(locked), Mockito.any());
        order.verify(auditLogService).record(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any());
        Assertions.assertEquals(new AfterSalesTransitions.Command(AfterSalesTrigger.ADMIN_APPROVE_PARTIAL,
                AfterSalesActor.ADMIN, ADMIN_ID, Money.of("5.00", "USD"), "Half of the meal was missing.",
                "arbitrate-1"), firedCommand());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("order_id", ORDER_ID);
        details.put("decision", "PARTIAL");
        details.put("amount", new BigDecimal("5.00"));
        details.put("currency", "USD");
        details.put("from_status", "IN_ARBITRATION");
        details.put("to_status", "REFUNDING");
        details.put("idempotency_key", "arbitrate-1");
        Assertions.assertEquals(details, auditedArbitration());
    }


    @Test
    void arbitrate_full_shouldGrantTheRequestedAmountAndAuditIt() {
        runRequestsInline();
        AfterSalesCaseEntity locked = caseRow(AfterSalesStatus.IN_ARBITRATION, "USD");
        AfterSalesCaseEntity refunding = refunding(locked, "16.09", AfterSalesResolution.ARBITRATION_FULL);
        Mockito.when(caseRepository.lockById(CASE_ID)).thenReturn(locked);
        Mockito.when(transitions.fire(Mockito.same(locked), Mockito.any())).thenReturn(applied(
                AfterSalesTrigger.ADMIN_APPROVE_FULL, AfterSalesStatus.REFUNDING, refunding));
        stubDetail(refunding);

        service.arbitrate(CASE_ID, body("FULL", null, "The meal never arrived."), "arbitrate-1", ADMIN);

        Assertions.assertEquals(new AfterSalesTransitions.Command(AfterSalesTrigger.ADMIN_APPROVE_FULL,
                AfterSalesActor.ADMIN, ADMIN_ID, null, "The meal never arrived.", "arbitrate-1"), firedCommand());
        Map<String, Object> details = auditedArbitration();
        Assertions.assertEquals("FULL", details.get("decision"));
        Assertions.assertEquals(new BigDecimal("16.09"), details.get("amount"));
        Assertions.assertEquals("USD", details.get("currency"));
    }


    @Test
    void arbitrate_reject_shouldCloseTheCaseAndAuditItWithoutAnAmount() {
        runRequestsInline();
        AfterSalesCaseEntity locked = caseRow(AfterSalesStatus.IN_ARBITRATION, "USD");
        AfterSalesCaseEntity closed = moved(locked, AfterSalesStatus.CLOSED, null, null,
                AfterSalesCloseReason.ARBITRATION_REJECTED.name());
        Mockito.when(caseRepository.lockById(CASE_ID)).thenReturn(locked);
        Mockito.when(transitions.fire(Mockito.same(locked), Mockito.any())).thenReturn(applied(
                AfterSalesTrigger.ADMIN_REJECT, AfterSalesStatus.CLOSED, closed));
        stubDetail(closed);

        service.arbitrate(CASE_ID, body("REJECT", null, "The photos show a complete order."), "arbitrate-1", ADMIN);

        Assertions.assertEquals(AfterSalesTrigger.ADMIN_REJECT, firedCommand().trigger());
        Assertions.assertNull(firedCommand().amount());
        Map<String, Object> details = auditedArbitration();
        Assertions.assertEquals("REJECT", details.get("decision"));
        Assertions.assertEquals("CLOSED", details.get("to_status"));
        Assertions.assertFalse(details.containsKey("amount"));
        Assertions.assertFalse(details.containsKey("currency"));
    }


    static Stream<Arguments> invalidArbitrations() {
        return Stream.of(
                Arguments.of(body("MAYBE", null, "x"), "Decision must be FULL, PARTIAL or REJECT"),
                Arguments.of(body("full", null, "x"), "Decision must be FULL, PARTIAL or REJECT"),
                Arguments.of(body(null, null, "x"), "Decision must be FULL, PARTIAL or REJECT"),
                Arguments.of(body("PARTIAL", null, "x"), "A PARTIAL decision needs an amount"),
                Arguments.of(body("FULL", "5.00", "x"), "A FULL decision takes no amount"),
                Arguments.of(body("REJECT", "5.00", "x"), "A REJECT decision takes no amount"),
                Arguments.of(body("FULL", null, "   "), "An arbitration needs a note"),
                Arguments.of(body("FULL", null, null), "An arbitration needs a note")
        );
    }


    @ParameterizedTest
    @MethodSource("invalidArbitrations")
    void arbitrate_withAnInvalidBody_shouldBeABadRequestBeforeAnythingIsRead(
            ArbitrateAfterSalesBody body,
            String message
    ) {
        BadRequestException refused = Assertions.assertThrows(BadRequestException.class,
                () -> service.arbitrate(CASE_ID, body, "arbitrate-1", ADMIN));

        Assertions.assertEquals(message, refused.getMessage());
        Mockito.verifyNoInteractions(idempotentRequests, caseRepository, transitions, auditLogService);
    }


    @Test
    void arbitrate_withoutAKey_shouldBeABadRequestBeforeAnythingIsRead() {
        Assertions.assertThrows(BadRequestException.class,
                () -> service.arbitrate(CASE_ID, body("FULL", null, "x"), " ", ADMIN));

        Mockito.verifyNoInteractions(idempotentRequests, caseRepository, transitions, auditLogService);
    }


    /**
     * The amount's decimal places are checked against the currency of the locked case (section 19.5), with the
     * service's own message; an amount beyond any money column is no amount less than the requested one.
     */
    @ParameterizedTest
    @CsvSource({
            "USD, 5.001, Amount must have at most 2 decimal places",
            "JPY, 500.5, Amount must have at most 0 decimal places",
            "USD, 100000000000, The amount must be more than 0 and less than the requested 16.09 USD"
    })
    void arbitrate_withAnAmountTheCurrencyCannotHold_shouldBeABadRequestWithoutAStep(
            String currency,
            String amount,
            String message
    ) {
        runRequestsInline();
        Mockito.when(caseRepository.lockById(CASE_ID)).thenReturn(caseRow(AfterSalesStatus.IN_ARBITRATION, currency));

        BadRequestException refused = Assertions.assertThrows(BadRequestException.class,
                () -> service.arbitrate(CASE_ID, body("PARTIAL", amount, "x"), "arbitrate-1", ADMIN));

        Assertions.assertEquals(message, refused.getMessage());
        Mockito.verifyNoInteractions(transitions, auditLogService);
    }


    @Test
    void arbitrate_inAZeroDecimalCurrency_shouldTakeAWholeAmount() {
        runRequestsInline();
        AfterSalesCaseEntity locked = caseRow(AfterSalesStatus.IN_ARBITRATION, "JPY");
        Mockito.when(caseRepository.lockById(CASE_ID)).thenReturn(locked);
        Mockito.when(transitions.fire(Mockito.same(locked), Mockito.any())).thenReturn(applied(
                AfterSalesTrigger.ADMIN_APPROVE_PARTIAL, AfterSalesStatus.REFUNDING,
                refunding(locked, "500.00", AfterSalesResolution.ARBITRATION_PARTIAL)));
        stubDetail(refunding(locked, "500.00", AfterSalesResolution.ARBITRATION_PARTIAL));

        service.arbitrate(CASE_ID, body("PARTIAL", "500.00", "x"), "arbitrate-1", ADMIN);

        Assertions.assertEquals(Money.of("500", "JPY"), firedCommand().amount());
        Assertions.assertEquals("JPY", auditedArbitration().get("currency"));
    }


    @Test
    void arbitrate_ofAMissingCase_shouldBeNotFoundWithoutAStep() {
        runRequestsInline();

        ResourceNotFoundException missing = Assertions.assertThrows(ResourceNotFoundException.class,
                () -> service.arbitrate(CASE_ID, body("FULL", null, "x"), "arbitrate-1", ADMIN));

        Assertions.assertEquals("After-sales case not found", missing.getMessage());
        Mockito.verifyNoInteractions(transitions, auditLogService);
    }


    /**
     * A case the machine refuses to arbitrate (not IN_ARBITRATION: withdrawn, already decided, timed out) is a 409
     * naming its status; nothing is audited and the idempotency row rolls back with the exception.
     */
    @ParameterizedTest
    @EnumSource(value = AfterSalesStatus.class, names = {"IN_ARBITRATION"}, mode = EnumSource.Mode.EXCLUDE)
    void arbitrate_ofACaseNotInArbitration_shouldBeAStatusConflictWithoutAnAuditEntry(AfterSalesStatus status) {
        runRequestsInline();
        AfterSalesCaseEntity locked = caseRow(status, "USD");
        Mockito.when(caseRepository.lockById(CASE_ID)).thenReturn(locked);
        Decision<AfterSalesStatus, AfterSalesTrigger> refused = AfterSalesStateMachine.DEFINITION.decide(status,
                AfterSalesTrigger.ADMIN_REJECT, AfterSalesActor.ADMIN, new AfterSalesTransitionContext(locked, NOW));
        Mockito.when(transitions.fire(Mockito.same(locked), Mockito.any()))
                .thenReturn(new AfterSalesTransitions.Step(refused, locked));

        ConflictException conflict = Assertions.assertThrows(ConflictException.class,
                () -> service.arbitrate(CASE_ID, body("REJECT", null, "x"), "arbitrate-1", ADMIN));

        Assertions.assertEquals("AFTER_SALES_STATUS_CONFLICT", conflict.getCode());
        Assertions.assertEquals("After-sales case is " + status + "; only IN_ARBITRATION cases can be arbitrated",
                conflict.getMessage());
        Mockito.verifyNoInteractions(auditLogService);
    }


    /**
     * The admin decisions declare no alreadyDoneWhen, so this does not happen today; should one, it answers with the
     * case and writes no audit entry, like every step that changed nothing.
     */
    @Test
    void arbitrate_aStepThatAlreadyHappened_shouldAnswerWithoutAnAuditEntry() {
        runRequestsInline();
        AfterSalesCaseEntity locked = caseRow(AfterSalesStatus.IN_ARBITRATION, "USD");
        Mockito.when(caseRepository.lockById(CASE_ID)).thenReturn(locked);
        Mockito.when(transitions.fire(Mockito.same(locked), Mockito.any())).thenReturn(new AfterSalesTransitions.Step(
                new Decision.AlreadyDone<>(AfterSalesTrigger.ADMIN_REJECT, AfterSalesStatus.IN_ARBITRATION), locked));
        AfterSalesCaseDto answer = stubDetail(locked);

        Assertions.assertSame(answer, service.arbitrate(CASE_ID, body("REJECT", null, "x"), "arbitrate-1", ADMIN));

        Mockito.verifyNoInteractions(auditLogService);
    }


    /**
     * A replay finds its key SUCCEEDED: the request does not run again (no lock, no step, no audit) and the answer is
     * the case as it is now.
     */
    @Test
    void arbitrate_replay_shouldAnswerWithTheCaseItsKeyDecidedWithoutAStepOrAnAuditEntry() {
        Mockito.when(idempotentRequests.run(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(CASE_ID);
        AfterSalesCaseDto answer = stubDetail(refunding(caseRow(AfterSalesStatus.IN_ARBITRATION, "USD"), "5.00",
                AfterSalesResolution.ARBITRATION_PARTIAL));

        Assertions.assertSame(answer, service.arbitrate(CASE_ID, body("PARTIAL", "5.00", "x"), "arbitrate-1", ADMIN));

        Mockito.verify(caseRepository, Mockito.never()).lockById(Mockito.any());
        Mockito.verifyNoInteractions(transitions, auditLogService);
    }


    /**
     * The request hash names the decision, the amount by value and the trimmed note: 5.5 and 5.50 are one request,
     * another decision, amount or note is another.
     */
    @Test
    void arbitrate_shouldHashTheDecisionTheAmountByValueAndTheNote() {
        Mockito.when(idempotentRequests.run(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(CASE_ID);
        stubDetail(caseRow(AfterSalesStatus.CLOSED, "USD"));

        service.arbitrate(CASE_ID, body("PARTIAL", "5.5", "Half."), "k", ADMIN);
        service.arbitrate(CASE_ID, body("PARTIAL", "5.50", " Half. "), "k", ADMIN);
        service.arbitrate(CASE_ID, body("PARTIAL", "5.51", "Half."), "k", ADMIN);
        service.arbitrate(CASE_ID, body("PARTIAL", "5.50", "Half!"), "k", ADMIN);
        service.arbitrate(CASE_ID, body("FULL", null, "Half."), "k", ADMIN);
        service.arbitrate(CASE_ID, body("REJECT", null, "Half."), "k", ADMIN);

        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        Mockito.verify(idempotentRequests, Mockito.times(6)).run(Mockito.eq(ADMIN_ID), Mockito.eq(SCOPE),
                Mockito.eq("k"), hashes.capture(), Mockito.any());
        List<String> values = hashes.getAllValues();
        Assertions.assertEquals(values.get(0), values.get(1));
        Assertions.assertEquals(5, values.stream().distinct().count());
    }


    // ---- the queue and one case ----


    @Test
    void getCases_ofAnOpenStatus_shouldListTheNearestDeadlineFirst() {
        List<AfterSalesCaseEntity> queue = List.of(caseRow(AfterSalesStatus.IN_ARBITRATION, "USD"));
        List<AfterSalesCaseDto> page = List.of();
        Mockito.when(caseRepository.findQueueByStatus("IN_ARBITRATION", 50)).thenReturn(queue);
        Mockito.when(views.adminSummaries(queue)).thenReturn(page);

        Assertions.assertSame(page, service.getCases("IN_ARBITRATION", 50));

        Mockito.verify(caseRepository, Mockito.never()).findPageByStatus(Mockito.any(), Mockito.anyInt());
    }


    @ParameterizedTest
    @EnumSource(value = AfterSalesStatus.class, names = {"REFUNDING", "REFUNDED", "CLOSED"})
    void getCases_ofAStatusWithoutADeadline_shouldListTheNewestFirst(AfterSalesStatus status) {
        List<AfterSalesCaseEntity> newest = List.of(caseRow(status, "USD"));
        List<AfterSalesCaseDto> page = List.of();
        Mockito.when(caseRepository.findPageByStatus(status.name(), 20)).thenReturn(newest);
        Mockito.when(views.adminSummaries(newest)).thenReturn(page);

        Assertions.assertSame(page, service.getCases(status.name(), 20));

        Mockito.verify(caseRepository, Mockito.never()).findQueueByStatus(Mockito.any(), Mockito.anyInt());
    }


    @ParameterizedTest
    @CsvSource({"0,1", "-5,1", "100,100", "500,100"})
    void getCases_shouldKeepThePageBetweenOneAndAHundred(int limit, int pageSize) {
        service.getCases("PENDING_MERCHANT", limit);

        Mockito.verify(caseRepository).findQueueByStatus("PENDING_MERCHANT", pageSize);
    }


    @Test
    void getCases_ofAnUnknownStatus_shouldBeABadRequest() {
        BadRequestException refused = Assertions.assertThrows(BadRequestException.class,
                () -> service.getCases("escalated", 50));

        Assertions.assertEquals("Unsupported after-sales status: escalated", refused.getMessage());
        Assertions.assertThrows(BadRequestException.class, () -> service.getCases(null, 50));
        Assertions.assertThrows(BadRequestException.class, () -> service.getCases(" ", 50));
        Mockito.verifyNoInteractions(caseRepository, views);
    }


    /**
     * Section 27, follow-up 8: the status is trimmed and upper-cased first, like the merchant's filter.
     */
    @Test
    void getCases_shouldTakeTheStatusInAnyCase() {
        service.getCases(" in_Arbitration ", 50);

        Mockito.verify(caseRepository).findQueueByStatus("IN_ARBITRATION", 50);
    }


    @Test
    void getCase_shouldShowAnyCaseWithItsWholeHistoryAndRefund() {
        AfterSalesCaseEntity current = caseRow(AfterSalesStatus.IN_ARBITRATION, "USD");
        AfterSalesCaseDto answer = stubDetail(current);

        Assertions.assertSame(answer, service.getCase(CASE_ID));
        Mockito.verify(views, Mockito.never()).detail(Mockito.any());
    }


    @Test
    void getCase_ofAMissingCase_shouldBeNotFound() {
        Mockito.when(caseRepository.findById(CASE_ID)).thenReturn(Optional.empty());

        ResourceNotFoundException missing = Assertions.assertThrows(ResourceNotFoundException.class,
                () -> service.getCase(CASE_ID));

        Assertions.assertEquals("After-sales case not found", missing.getMessage());
    }


    // ---- refunds ----


    @Test
    void getRefunds_shouldListTheRefundsOfAStatusWithTheirOrderNumberAndLastError() {
        RefundEntity failed = refund(RefundStatus.FAILED, 8, "simulated gateway timeout");
        RefundEntity otherOrder = new RefundEntity(71L, 502L, 1002L, CUSTOMER_ID, "after-sales-case:502", "SIMPAY-2",
                new BigDecimal("3.00"), "USD", "FAILED", 8, null, null, null, "declined", 9L, NOW, NOW, null);
        Mockito.when(refundRepository.findPageByStatus("FAILED", 50)).thenReturn(List.of(otherOrder, failed));
        Mockito.when(orderRepository.findAllById(List.of(1002L, ORDER_ID))).thenReturn(List.of(order()));

        List<RefundDto> refunds = service.getRefunds("FAILED", 50);

        Assertions.assertEquals(List.of(new RefundDto(otherOrder, null), new RefundDto(failed, ORDER_NO)), refunds);
        Assertions.assertEquals("simulated gateway timeout", refunds.get(1).lastError());
    }


    @Test
    void getRefunds_withNoneOrAnUnknownStatus_shouldLookUpNoOrder() {
        Assertions.assertEquals(List.of(), service.getRefunds("PENDING", 500));
        Mockito.verify(refundRepository).findPageByStatus("PENDING", 100);

        BadRequestException refused = Assertions.assertThrows(BadRequestException.class,
                () -> service.getRefunds("GIVEN_UP", 50));
        Assertions.assertEquals("Unsupported refund status: GIVEN_UP", refused.getMessage());
        Mockito.verifyNoInteractions(orderRepository);
    }


    /**
     * ADMIN_RETRY under the refund's row lock, the time read after it: PENDING and due now, no attempt made, no lease,
     * the last error kept until the next attempt; then the refund.retry entry with what the retry left behind.
     */
    @Test
    void retryRefund_ofAFailedRefund_shouldMakeItDueNowWithItsAttemptsStartingOverAndAuditIt() {
        RefundEntity failed = refund(RefundStatus.FAILED, 8, "simulated gateway timeout");
        Mockito.when(refundRepository.lockById(REFUND_ID)).thenReturn(failed);
        Mockito.when(clock.get()).thenReturn(NOW);
        Mockito.when(refundRepository.save(Mockito.any())).thenAnswer(invocation -> withVersion(
                invocation.getArgument(0), 10L));
        Mockito.when(orderRepository.findOrderNoById(ORDER_ID)).thenReturn(Optional.of(ORDER_NO));

        RefundDto retried = service.retryRefund(REFUND_ID, ADMIN);

        InOrder order = Mockito.inOrder(refundRepository, clock, auditLogService);
        order.verify(refundRepository).lockById(REFUND_ID);
        order.verify(clock).get();
        ArgumentCaptor<RefundEntity> saved = ArgumentCaptor.forClass(RefundEntity.class);
        order.verify(refundRepository).save(saved.capture());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("case_id", CASE_ID);
        details.put("order_id", ORDER_ID);
        details.put("previous_attempts", 8);
        details.put("last_error", "simulated gateway timeout");
        order.verify(auditLogService).record(ADMIN, AuditActions.REFUND_RETRY, AuditActions.TARGET_REFUND, REFUND_ID,
                details);
        Assertions.assertEquals(new RefundEntity(REFUND_ID, CASE_ID, ORDER_ID, CUSTOMER_ID, "after-sales-case:501",
                "SIMPAY-1", new BigDecimal("16.09"), "USD", "PENDING", 0, NOW, null, null,
                "simulated gateway timeout", 9L, NOW.minusHours(2), NOW, null), saved.getValue());
        Assertions.assertEquals(new RefundDto(REFUND_ID, CASE_ID, ORDER_ID, ORDER_NO, new BigDecimal("16.09"), "USD",
                "PENDING", 0, "simulated gateway timeout", null, NOW, NOW.minusHours(2), null), retried);
    }


    @Test
    void retryRefund_ofARefundAlreadyPending_shouldAnswerItUnchangedWithoutAnAuditEntry() {
        RefundEntity pending = refund(RefundStatus.PENDING, 0, "simulated gateway timeout");
        Mockito.when(refundRepository.lockById(REFUND_ID)).thenReturn(pending);
        Mockito.when(orderRepository.findOrderNoById(ORDER_ID)).thenReturn(Optional.empty());

        Assertions.assertEquals(new RefundDto(pending, null), service.retryRefund(REFUND_ID, ADMIN));

        Mockito.verify(refundRepository, Mockito.never()).save(Mockito.any());
        Mockito.verifyNoInteractions(auditLogService, clock);
    }


    @ParameterizedTest
    @EnumSource(value = RefundStatus.class, names = {"PROCESSING", "SUCCEEDED"})
    void retryRefund_ofARefundThatDidNotFail_shouldBeARefundStatusConflict(RefundStatus status) {
        Mockito.when(refundRepository.lockById(REFUND_ID)).thenReturn(refund(status, 1, null));

        ConflictException conflict = Assertions.assertThrows(ConflictException.class,
                () -> service.retryRefund(REFUND_ID, ADMIN));

        Assertions.assertEquals("REFUND_STATUS_CONFLICT", conflict.getCode());
        Assertions.assertEquals("Refund is " + status + "; only FAILED refunds can be retried", conflict.getMessage());
        Mockito.verify(refundRepository, Mockito.never()).save(Mockito.any());
        Mockito.verifyNoInteractions(auditLogService, clock, orderRepository);
    }


    @Test
    void retryRefund_ofAMissingRefund_shouldBeNotFound() {
        ResourceNotFoundException missing = Assertions.assertThrows(ResourceNotFoundException.class,
                () -> service.retryRefund(REFUND_ID, ADMIN));

        Assertions.assertEquals("Refund not found", missing.getMessage());
        Mockito.verifyNoInteractions(auditLogService, clock);
    }


    // ---- fixtures ----


    static ArbitrateAfterSalesBody body(String decision, String amount, String note) {
        return new ArbitrateAfterSalesBody(decision, amount == null ? null : new BigDecimal(amount), note);
    }


    /**
     * A customer's request of 16.09 (or 1609 JPY) in {@code status}; an open status has its deadline an hour after
     * {@link #NOW}, a closed one its reason.
     */
    static AfterSalesCaseEntity caseRow(AfterSalesStatus status, String currency) {
        String requested = "JPY".equals(currency) ? "1609.00" : "16.09";
        boolean refunding = status == AfterSalesStatus.REFUNDING || status == AfterSalesStatus.REFUNDED;
        return new AfterSalesCaseEntity(CASE_ID, ORDER_ID, CUSTOMER_ID, 7L, "CUSTOMER_REQUEST", 1, status.name(),
                "MISSING_ITEM", "The fries were missing.", new BigDecimal(requested), new BigDecimal(requested), null,
                refunding ? new BigDecimal(requested) : null,
                status == AfterSalesStatus.REFUNDED ? new BigDecimal(requested) : null, currency, "REJECTED",
                "The order was complete.", null, status == AfterSalesStatus.CLOSED ? "WITHDRAWN" : null, null,
                status.isOpen() ? NOW.plusHours(1) : null, null, 0, 3L, NOW.minusHours(2), NOW.minusHours(1));
    }


    static AfterSalesCaseEntity refunding(AfterSalesCaseEntity locked, String approved,
                                          AfterSalesResolution resolution) {
        return moved(locked, AfterSalesStatus.REFUNDING, new BigDecimal(approved), resolution.name(), null);
    }


    static AfterSalesCaseEntity moved(
            AfterSalesCaseEntity locked,
            AfterSalesStatus status,
            BigDecimal approved,
            String resolution,
            String closeReason
    ) {
        return new AfterSalesCaseEntity(locked.id(), locked.orderId(), locked.customerId(), locked.restaurantId(),
                locked.kind(), locked.attemptNo(), status.name(), locked.reasonCode(), locked.description(),
                locked.orderTotal(), locked.requestedAmount(), null, approved, null, locked.currency(),
                locked.merchantDecision(), locked.merchantNote(), resolution, closeReason, "note", null, null, 0,
                locked.version() + 1, locked.createdAt(), NOW);
    }


    static AfterSalesTransitions.Step applied(
            AfterSalesTrigger trigger,
            AfterSalesStatus to,
            AfterSalesCaseEntity written
    ) {
        return new AfterSalesTransitions.Step(
                new Decision.Apply<>(trigger, AfterSalesStatus.IN_ARBITRATION, to), written);
    }


    static RefundEntity refund(RefundStatus status, int attempts, String lastError) {
        return new RefundEntity(REFUND_ID, CASE_ID, ORDER_ID, CUSTOMER_ID, "after-sales-case:501", "SIMPAY-1",
                new BigDecimal("16.09"), "USD", status.name(), attempts,
                status == RefundStatus.PENDING ? NOW.minusMinutes(1) : null,
                status == RefundStatus.PROCESSING ? NOW.plusMinutes(1) : null,
                status == RefundStatus.SUCCEEDED ? "SIMREF-1" : null, lastError, 9L, NOW.minusHours(2),
                NOW.minusMinutes(5), status == RefundStatus.SUCCEEDED ? NOW.minusMinutes(5) : null);
    }


    static RefundEntity withVersion(RefundEntity refund, Long version) {
        return new RefundEntity(refund.id(), refund.caseId(), refund.orderId(), refund.customerId(),
                refund.gatewayKey(), refund.paymentReference(), refund.amount(), refund.currency(), refund.status(),
                refund.attempts(), refund.nextAttemptAt(), refund.claimedUntil(), refund.providerReference(),
                refund.lastError(), version, refund.createdAt(), refund.updatedAt(), refund.succeededAt());
    }


    static OrderEntity order() {
        return new OrderEntity(ORDER_ID, ORDER_NO, CUSTOMER_ID, new BigDecimal("16.09"), "COMPLETED",
                NOW.minusHours(5), 7L, null, NOW.minusHours(4), "USD");
    }


    /**
     * The idempotent requests run their request in place, as on a first run.
     */
    private void runRequestsInline() {
        Mockito.when(idempotentRequests.run(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(invocation -> invocation.<Supplier<IdempotentRequests.Binding>>getArgument(4).get()
                        .caseId());
    }


    private AfterSalesCaseDto stubDetail(AfterSalesCaseEntity current) {
        AfterSalesCaseDto answer = new AfterSalesCaseDto(current, ORDER_NO, List.of(), null, List.of());
        Mockito.when(caseRepository.findById(current.id())).thenReturn(Optional.of(current));
        Mockito.when(views.adminDetail(current)).thenReturn(answer);
        return answer;
    }


    private AfterSalesTransitions.Command firedCommand() {
        ArgumentCaptor<AfterSalesTransitions.Command> command = ArgumentCaptor.forClass(
                AfterSalesTransitions.Command.class);
        Mockito.verify(transitions).fire(Mockito.any(), command.capture());
        return command.getValue();
    }


    @SuppressWarnings("unchecked")
    private Map<String, Object> auditedArbitration() {
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(auditLogService).record(Mockito.eq(ADMIN), Mockito.eq(AuditActions.AFTER_SALES_CASE_ARBITRATE),
                Mockito.eq(AuditActions.TARGET_AFTER_SALES_CASE), Mockito.eq(CASE_ID), details.capture());
        return details.getValue();
    }
}
