package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.entity.OrderPaymentEntity;
import com.laioffer.onlineorder.entity.OutboxEventEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.messaging.AfterSalesEventEnvelope;
import com.laioffer.onlineorder.messaging.contract.EventTestSupport;
import com.laioffer.onlineorder.messaging.contract.EventTopic;
import com.laioffer.onlineorder.money.CurrencyMismatchException;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.OrderPaymentRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.repository.OutboxEventRepository;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import com.laioffer.onlineorder.statemachine.Decision;
import com.laioffer.onlineorder.statemachine.StaleTransitionException;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;


import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * The single writer (ADR 0002 sections 5.2, 6, 9 and 19): every step of table 5.2 writes its fields, the deadline of
 * the status it enters and one more version with a compare-and-set on the status and version it was decided on, then
 * its history row, the refund when it enters REFUNDING, and an event that passes its published contract; a step that
 * already happened or is refused writes nothing; a missed compare-and-set and an invalid amount stop before any other
 * write; the step metric counts committed steps only.
 */
@ExtendWith(MockitoExtension.class)
class AfterSalesTransitionsTests {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 12, 0, 30);

    static final Long CASE_ID = 501L;

    static final Long ORDER_ID = 1001L;

    static final Long ORDER_NO = 7_254_189_912_938_504_192L;

    static final Long CUSTOMER_ID = 3L;

    static final Long MERCHANT_ID = 21L;

    static final Long ADMIN_ID = 1L;

    /**
     * Before {@link #NOW}: the deadline of a status whose timeout is due.
     */
    static final LocalDateTime PAST = NOW.minusMinutes(1);

    /**
     * After {@link #NOW}: the deadline of a status that still waits for its answer.
     */
    static final LocalDateTime FUTURE = NOW.plusHours(1);

    static final List<AfterSalesCaseLineEntity> LINES = List.of(
            new AfterSalesCaseLineEntity(9L, CASE_ID, 41L, "Whopper Meal", new BigDecimal("10.59"), 1,
                    new BigDecimal("10.59"), "USD"),
            new AfterSalesCaseLineEntity(10L, CASE_ID, 42L, "Large Fries", new BigDecimal("2.75"), 2,
                    new BigDecimal("5.50"), "USD")
    );

    /**
     * The order lines of {@link #cancelledOrder}: one Whopper Meal and two Large Fries.
     */
    static final List<OrderHistoryItemEntity> ORDER_ITEMS = List.of(
            new OrderHistoryItemEntity(41L, ORDER_ID, 301L, 7L, new BigDecimal("10.59"), 1, "Whopper Meal",
                    "Burger, fries and a drink", "whopper.png", "USD"),
            new OrderHistoryItemEntity(42L, ORDER_ID, 302L, 7L, new BigDecimal("2.75"), 2, "Large Fries",
                    "Salted", "fries.png", "USD")
    );

    @Mock
    private AfterSalesCaseRepository caseRepository;

    @Mock
    private AfterSalesCaseLineRepository lineRepository;

    @Mock
    private AfterSalesCaseHistoryRepository historyRepository;

    @Mock
    private RefundRepository refundRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderPaymentRepository orderPaymentRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ApplicationMetricsService metricsService;

    @Mock
    private ObjectProvider<Tracer> tracer;

    private final AtomicInteger clockReads = new AtomicInteger();

    private AfterSalesTransitions transitions;


    @BeforeEach
    void setup() {
        Map<String, Long> sequences = new HashMap<>();
        // The application's outbox with its event contracts: every event the writer stores is checked against its
        // published schema.
        OrderEventOutboxService outbox = new OrderEventOutboxService(
                20,
                Duration.ofSeconds(30),
                2,
                "order-events",
                EventTestSupport.mapper(),
                outboxEventRepository,
                (aggregateType, aggregateId) -> sequences.merge(aggregateType + ":" + aggregateId, 1L, Long::sum),
                EventTestSupport.contracts()
        );
        transitions = new AfterSalesTransitions(
                caseRepository,
                lineRepository,
                historyRepository,
                refundRepository,
                orderRepository,
                orderPaymentRepository,
                outbox,
                metricsService,
                new AfterSalesDeadlines(Duration.ofHours(24), Duration.ofHours(24), Duration.ofHours(48),
                        Duration.ofHours(72)),
                tracer,
                "after-sales-events",
                () -> {
                    clockReads.incrementAndGet();
                    return NOW;
                }
        );
    }


    @ParameterizedTest
    @EnumSource(AfterSalesTrigger.class)
    void fire_shouldWriteTheStepOfTable52WithItsDeadlineHistoryRefundAndEvent(AfterSalesTrigger trigger) {
        Scenario scenario = Scenario.of(trigger);
        AfterSalesStatus from = AfterSalesStatus.valueOf(scenario.locked().status());
        AfterSalesStatus to = AfterSalesStatus.valueOf(scenario.expected().status());
        stubCompareAndSet(1);
        Mockito.when(lineRepository.findAllByCaseIdIn(List.of(CASE_ID))).thenReturn(LINES);
        Mockito.when(orderRepository.findOrderNoById(ORDER_ID)).thenReturn(Optional.of(ORDER_NO));
        if (to == AfterSalesStatus.REFUNDING) {
            Mockito.when(orderPaymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment()));
        }

        AfterSalesTransitions.Step step = transitions.fire(scenario.locked(), scenario.command());

        Assertions.assertEquals(new Decision.Apply<>(trigger, from, to), step.decision());
        Assertions.assertTrue(step.applied());
        Assertions.assertEquals(scenario.expected(), step.current());
        AfterSalesCaseEntity moved = scenario.expected();
        Mockito.verify(caseRepository).compareAndSetStep(
                CASE_ID,
                from.name(),
                3L,
                to.name(),
                moved.offeredAmount(),
                moved.approvedAmount(),
                moved.refundedAmount(),
                moved.merchantDecision(),
                moved.merchantNote(),
                moved.resolution(),
                moved.closeReason(),
                moved.adminNote(),
                moved.deadlineAt(),
                NOW
        );
        Mockito.verify(historyRepository).save(new AfterSalesCaseHistoryEntity(
                null,
                CASE_ID,
                from.name(),
                to.name(),
                trigger.name(),
                scenario.command().actor().name(),
                scenario.command().actorId(),
                scenario.historyAmount() == null ? null : new BigDecimal(scenario.historyAmount()),
                "USD",
                scenario.command().note(),
                null,
                NOW
        ));
        if (to == AfterSalesStatus.REFUNDING) {
            Mockito.verify(refundRepository).save(new RefundEntity(
                    null,
                    CASE_ID,
                    ORDER_ID,
                    CUSTOMER_ID,
                    "after-sales-case:" + CASE_ID,
                    "SIMPAY-0f3a",
                    moved.approvedAmount(),
                    "USD",
                    "PENDING",
                    0,
                    NOW,
                    null,
                    null,
                    null,
                    null,
                    NOW,
                    NOW,
                    null
            ));
        } else {
            Mockito.verifyNoInteractions(refundRepository, orderPaymentRepository);
        }
        AfterSalesEventEnvelope event = storedEvent();
        Assertions.assertEquals(to.eventType(), event.eventType());
        Assertions.assertEquals(NOW, event.occurredAt());
        Assertions.assertEquals(to.name(), event.data().status());
        Assertions.assertEquals(trigger.name(), event.data().action());
        Assertions.assertEquals(scenario.command().actor().name(), event.data().actorType());
        Assertions.assertEquals(scenario.command().actorId(), event.data().actorId());
        Assertions.assertEquals(4L, event.data().version(), "the version the step wrote");
        Assertions.assertEquals(ORDER_NO, event.data().orderNo());
        Assertions.assertEquals(2, event.data().lines().size());
        Assertions.assertEquals(moved.deadlineAt(), event.data().deadlineAt());
        Mockito.verify(metricsService).recordAfterSalesStep(trigger.name(), from.name(), to.name());
        Assertions.assertEquals(1, clockReads.get(), "one time per step, read after the caller's lock");
    }


    @Test
    void fire_shouldCarryTheRequestsKeyAsTheEventsCorrelation() {
        stubCompareAndSet(1);
        Mockito.when(lineRepository.findAllByCaseIdIn(List.of(CASE_ID))).thenReturn(LINES);
        Mockito.when(orderRepository.findOrderNoById(ORDER_ID)).thenReturn(Optional.empty());

        transitions.fire(new CaseRow().build(), new AfterSalesTransitions.Command(
                AfterSalesTrigger.CUSTOMER_WITHDRAW, AfterSalesActor.CUSTOMER, CUSTOMER_ID, null, null,
                " withdraw-1 "));

        AfterSalesEventEnvelope event = storedEvent();
        Assertions.assertEquals("withdraw-1", event.idempotencyKey());
        Assertions.assertEquals("withdraw-1", event.correlationId());
        Assertions.assertNull(event.data().orderNo(), "an order without a number");
    }


    @Test
    void fire_whenTheStepAlreadyHappened_shouldWriteNothing() {
        AfterSalesCaseEntity withdrawn = new CaseRow().closed(AfterSalesCloseReason.WITHDRAWN).build();

        AfterSalesTransitions.Step step = transitions.fire(withdrawn, customer(AfterSalesTrigger.CUSTOMER_WITHDRAW));

        Assertions.assertEquals(
                new Decision.AlreadyDone<>(AfterSalesTrigger.CUSTOMER_WITHDRAW, AfterSalesStatus.CLOSED),
                step.decision()
        );
        Assertions.assertFalse(step.applied());
        Assertions.assertSame(withdrawn, step.current());
        assertNothingWritten();
    }


    @Test
    void fire_whenTheStatusDoesNotAllowTheStep_shouldWriteNothing() {
        AfterSalesCaseEntity pending = new CaseRow().build();

        AfterSalesTransitions.Step step = transitions.fire(pending, customer(AfterSalesTrigger.CUSTOMER_ACCEPT_OFFER));

        Assertions.assertEquals(new Decision.Rejected<>(AfterSalesTrigger.CUSTOMER_ACCEPT_OFFER,
                AfterSalesStatus.PENDING_MERCHANT, Decision.Reason.NOT_FROM_CURRENT_STATE, null), step.decision());
        Assertions.assertSame(pending, step.current());
        assertNothingWritten();
    }


    /**
     * The deadline is judged at the time read after the lock: from the deadline on, an answer is refused, while a
     * withdrawal still goes through (section 19.1).
     */
    @Test
    void fire_fromTheDeadlineOn_shouldRefuseTheAnswerButNotTheWithdrawal() {
        AfterSalesCaseEntity offered = new CaseRow().offered("5.50").deadline(NOW).build();

        AfterSalesTransitions.Step answer = transitions.fire(
                offered, customer(AfterSalesTrigger.CUSTOMER_ACCEPT_OFFER));

        Assertions.assertEquals(new Decision.Rejected<>(AfterSalesTrigger.CUSTOMER_ACCEPT_OFFER,
                AfterSalesStatus.COUNTER_OFFERED, Decision.Reason.GUARD_FAILED, AfterSalesStateMachine.BEFORE_DEADLINE),
                answer.decision());
        assertNothingWritten();

        stubCompareAndSet(1);
        Mockito.when(lineRepository.findAllByCaseIdIn(List.of(CASE_ID))).thenReturn(LINES);
        Mockito.when(orderRepository.findOrderNoById(ORDER_ID)).thenReturn(Optional.of(ORDER_NO));
        AfterSalesTransitions.Step withdrawal = transitions.fire(
                offered, customer(AfterSalesTrigger.CUSTOMER_WITHDRAW));
        Assertions.assertTrue(withdrawal.applied());
        Assertions.assertEquals("CLOSED", withdrawal.current().status());
    }


    @Test
    void fire_whenTheCompareAndSetMissesTheRow_shouldThrowBeforeAnyOtherWrite() {
        stubCompareAndSet(0);

        StaleTransitionException stale = Assertions.assertThrows(StaleTransitionException.class,
                () -> transitions.fire(new CaseRow().build(), customer(AfterSalesTrigger.CUSTOMER_WITHDRAW)));

        Assertions.assertEquals(
                "Compare-and-set of after-sales case 501 from PENDING_MERCHANT at version 3 changed 0 rows "
                        + "instead of 1",
                stale.getMessage()
        );
        Mockito.verifyNoInteractions(historyRepository, refundRepository, outboxEventRepository, metricsService);
    }


    @ParameterizedTest
    @ValueSource(strings = {"0.00", "16.09", "20.00", "-1.00"})
    void fire_aCounterOfferOrPartialRefundNotBelowTheRequest_shouldBeABadRequest(String amount) {
        for (AfterSalesTransitions.Command command : List.of(
                merchant(AfterSalesTrigger.MERCHANT_OFFER, Money.of(amount, "USD"), "Half"),
                admin(AfterSalesTrigger.ADMIN_APPROVE_PARTIAL, Money.of(amount, "USD"), "Part")
        )) {
            AfterSalesCaseEntity locked = command.trigger() == AfterSalesTrigger.MERCHANT_OFFER
                    ? new CaseRow().build()
                    : new CaseRow().rejected().status(AfterSalesStatus.IN_ARBITRATION).build();

            BadRequestException refused = Assertions.assertThrows(BadRequestException.class,
                    () -> transitions.fire(locked, command));

            Assertions.assertEquals("The amount must be more than 0 and less than the requested 16.09 USD",
                    refused.getMessage());
        }
        assertNothingWritten();
    }


    @Test
    void fire_withoutTheAmountOrReasonItNeeds_shouldBeAProgrammingError() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> transitions.fire(new CaseRow().build(),
                merchant(AfterSalesTrigger.MERCHANT_OFFER, null, "Half")));
        Assertions.assertThrows(IllegalArgumentException.class, () -> transitions.fire(new CaseRow().build(),
                merchant(AfterSalesTrigger.MERCHANT_REJECT, null, null)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> transitions.fire(new CaseRow().build(),
                merchant(AfterSalesTrigger.MERCHANT_REJECT, null, "  ")));
        assertNothingWritten();
    }


    @Test
    void fire_enteringRefundingWithoutACapturedPayment_shouldFailTheStep() {
        stubCompareAndSet(1);
        Mockito.when(orderPaymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        IllegalStateException missing = Assertions.assertThrows(IllegalStateException.class, () -> transitions.fire(
                new CaseRow().offered("5.50").build(), customer(AfterSalesTrigger.CUSTOMER_ACCEPT_OFFER)));

        Assertions.assertEquals("Order 1001 has no captured payment to refund", missing.getMessage());
        Mockito.verifyNoInteractions(refundRepository, outboxEventRepository, metricsService);
    }


    @Test
    void fire_shouldCountTheStepOnlyOnceItsTransactionCommits() {
        stubCompareAndSet(1);
        Mockito.when(lineRepository.findAllByCaseIdIn(List.of(CASE_ID))).thenReturn(LINES);
        Mockito.when(orderRepository.findOrderNoById(ORDER_ID)).thenReturn(Optional.of(ORDER_NO));
        TransactionSynchronizationManager.initSynchronization();
        try {
            transitions.fire(new CaseRow().build(), customer(AfterSalesTrigger.CUSTOMER_WITHDRAW));
            Mockito.verifyNoInteractions(metricsService);

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            Mockito.verify(metricsService).recordAfterSalesStep("CUSTOMER_WITHDRAW", "PENDING_MERCHANT", "CLOSED");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }


    @Test
    void open_shouldInsertTheRequestInPendingMerchantWithItsLinesHistoryAndEvent() {
        OrderEntity order = completedOrder("USD");
        Mockito.when(caseRepository.save(Mockito.any(AfterSalesCaseEntity.class))).thenAnswer(invocation -> {
            AfterSalesCaseEntity row = invocation.getArgument(0);
            return withIdAndVersion(row);
        });
        Mockito.when(lineRepository.saveAll(Mockito.anyList())).thenAnswer(invocation -> {
            List<AfterSalesCaseLineEntity> rows = invocation.getArgument(0);
            return rows.stream().map(line -> new AfterSalesCaseLineEntity(line.orderItemId() + 100, line.caseId(),
                    line.orderItemId(), line.menuItemName(), line.unitPrice(), line.quantity(), line.amount(),
                    line.currency())).toList();
        });

        AfterSalesCaseEntity opened = transitions.open(new AfterSalesTransitions.CustomerRequest(
                order,
                2,
                AfterSalesReason.MISSING_ITEM,
                "The fries were missing.",
                LINES.stream().map(line -> new AfterSalesCaseLineEntity(null, null, line.orderItemId(),
                        line.menuItemName(), line.unitPrice(), line.quantity(), line.amount(), line.currency()))
                        .toList(),
                Money.of("16.09", "USD"),
                NOW,
                "open-2"
        ));

        AfterSalesCaseEntity expected = new AfterSalesCaseEntity(CASE_ID, ORDER_ID, CUSTOMER_ID, 7L,
                "CUSTOMER_REQUEST", 2, "PENDING_MERCHANT", "MISSING_ITEM", "The fries were missing.",
                new BigDecimal("20.00"), new BigDecimal("16.09"), null, null, null, "USD", null, null, null, null, null,
                NOW.plusHours(24), null, 0, 0L, NOW, NOW);
        Assertions.assertEquals(expected, opened);
        Mockito.verify(caseRepository).save(withoutIdAndVersion(expected));
        ArgumentCaptor<List<AfterSalesCaseLineEntity>> lines = ArgumentCaptor.captor();
        Mockito.verify(lineRepository).saveAll(lines.capture());
        Assertions.assertEquals(List.of(CASE_ID, CASE_ID),
                lines.getValue().stream().map(AfterSalesCaseLineEntity::caseId).toList());
        Assertions.assertEquals(List.of(41L, 42L),
                lines.getValue().stream().map(AfterSalesCaseLineEntity::orderItemId).toList());
        Mockito.verify(historyRepository).save(new AfterSalesCaseHistoryEntity(null, CASE_ID, null, "PENDING_MERCHANT",
                "OPEN", "CUSTOMER", CUSTOMER_ID, new BigDecimal("16.09"), "USD", null, null, NOW));
        AfterSalesEventEnvelope event = storedEvent();
        Assertions.assertEquals("aftersales.case_opened", event.eventType());
        Assertions.assertEquals("OPEN", event.data().action());
        Assertions.assertEquals(0L, event.data().version());
        Assertions.assertEquals(2, event.data().attemptNo());
        Assertions.assertEquals(ORDER_NO, event.data().orderNo());
        Assertions.assertEquals(List.of(41L, 42L),
                event.data().lines().stream().map(line -> line.orderItemId()).toList());
        Assertions.assertEquals("open-2", event.correlationId());
        Mockito.verify(metricsService).recordAfterSalesStep("OPEN", "none", "PENDING_MERCHANT");
        Mockito.verifyNoInteractions(refundRepository, orderRepository);
        Assertions.assertEquals(0, clockReads.get(), "the opening runs at the time its caller read after the lock");
    }


    @Test
    void open_inAnotherCurrencyThanTheOrder_shouldWriteNothing() {
        Assertions.assertThrows(CurrencyMismatchException.class, () -> transitions.open(
                new AfterSalesTransitions.CustomerRequest(completedOrder("USD"), 1, AfterSalesReason.OTHER, "x",
                        List.of(), Money.of("1.00", "EUR"), NOW, null)));
        Mockito.verifyNoInteractions(caseRepository, lineRepository, historyRepository, outboxEventRepository);
    }


    /**
     * ADR 0002 D12, sections 9 and 19.7: the cancel of a paid order opens a case of the whole order straight in
     * REFUNDING, with its lines, its SYSTEM history row, its refund due at once against the captured payment and its
     * case_opened event, in that order and at the time of the cancel.
     */
    @Test
    void openOrderCancelled_shouldInsertTheWholeOrderInRefundingWithItsRefundHistoryAndEvent() {
        OrderEntity order = cancelledOrder("USD");
        stubCaseAndLineInserts();

        AfterSalesCaseEntity opened = transitions.openOrderCancelled(new AfterSalesTransitions.OrderCancellation(
                order, payment("16.09", "USD"), ORDER_ITEMS, NOW));

        AfterSalesCaseEntity expected = new AfterSalesCaseEntity(CASE_ID, ORDER_ID, CUSTOMER_ID, 7L, "ORDER_CANCELLED",
                1, "REFUNDING", null, null, new BigDecimal("16.09"), new BigDecimal("16.09"), null,
                new BigDecimal("16.09"), null, "USD", null, null, "ORDER_CANCELLED", null, null, null, null, 0, 0L, NOW,
                NOW);
        Assertions.assertEquals(expected, opened);
        InOrder writes = Mockito.inOrder(
                caseRepository, lineRepository, historyRepository, refundRepository, outboxEventRepository);
        writes.verify(caseRepository).save(withoutIdAndVersion(expected));
        writes.verify(lineRepository).saveAll(List.of(
                new AfterSalesCaseLineEntity(null, CASE_ID, 41L, "Whopper Meal", new BigDecimal("10.59"), 1,
                        new BigDecimal("10.59"), "USD"),
                new AfterSalesCaseLineEntity(null, CASE_ID, 42L, "Large Fries", new BigDecimal("2.75"), 2,
                        new BigDecimal("5.50"), "USD")
        ));
        writes.verify(historyRepository).save(new AfterSalesCaseHistoryEntity(null, CASE_ID, null, "REFUNDING",
                "ORDER_CANCELLED", "SYSTEM", null, new BigDecimal("16.09"), "USD", null, null, NOW));
        writes.verify(refundRepository).save(new RefundEntity(null, CASE_ID, ORDER_ID, CUSTOMER_ID,
                "after-sales-case:" + CASE_ID, "SIMPAY-0f3a", new BigDecimal("16.09"), "USD", "PENDING", 0, NOW, null,
                null, null, null, NOW, NOW, null));
        writes.verify(outboxEventRepository).save(Mockito.any());
        AfterSalesEventEnvelope event = storedEvent();
        Assertions.assertEquals("aftersales.case_opened", event.eventType());
        Assertions.assertEquals(NOW, event.occurredAt());
        Assertions.assertNull(event.idempotencyKey());
        Assertions.assertEquals(event.eventId(), event.correlationId());
        Assertions.assertEquals("ORDER_CANCELLED", event.data().kind());
        Assertions.assertEquals(1, event.data().attemptNo());
        Assertions.assertEquals("REFUNDING", event.data().status());
        Assertions.assertEquals("ORDER_CANCELLED", event.data().action());
        Assertions.assertEquals("SYSTEM", event.data().actorType());
        Assertions.assertNull(event.data().actorId());
        Assertions.assertEquals(0L, event.data().version());
        Assertions.assertEquals(ORDER_NO, event.data().orderNo());
        Assertions.assertEquals(0, new BigDecimal("16.09").compareTo(event.data().requestedAmount()));
        Assertions.assertEquals(0, new BigDecimal("16.09").compareTo(event.data().approvedAmount()));
        Assertions.assertEquals("USD", event.data().currency());
        Assertions.assertEquals("ORDER_CANCELLED", event.data().resolution());
        Assertions.assertNull(event.data().deadlineAt());
        Assertions.assertEquals(List.of(1, 2), event.data().lines().stream().map(line -> line.quantity()).toList());
        Mockito.verify(metricsService).recordAfterSalesStep("ORDER_CANCELLED", "none", "REFUNDING");
        // The order and its payment come from the caller, which read them under the order's lock.
        Mockito.verifyNoInteractions(orderRepository, orderPaymentRepository);
        Assertions.assertEquals(0, clockReads.get(), "the opening runs at the time of the cancel");
    }


    @Test
    void openOrderCancelled_shouldCountTheOpeningOnlyOnceItsTransactionCommits() {
        stubCaseAndLineInserts();
        TransactionSynchronizationManager.initSynchronization();
        try {
            transitions.openOrderCancelled(new AfterSalesTransitions.OrderCancellation(
                    cancelledOrder("USD"), payment("16.09", "USD"), ORDER_ITEMS, NOW));
            Mockito.verifyNoInteractions(metricsService);

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            Mockito.verify(metricsService).recordAfterSalesStep("ORDER_CANCELLED", "none", "REFUNDING");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }


    @Test
    void openOrderCancelled_withAPaymentInAnotherCurrency_shouldWriteNothing() {
        Assertions.assertThrows(CurrencyMismatchException.class, () -> transitions.openOrderCancelled(
                new AfterSalesTransitions.OrderCancellation(
                        cancelledOrder("USD"), payment("16.09", "EUR"), ORDER_ITEMS, NOW)));
        assertNothingWritten();
    }


    @Test
    void orderCancellation_shouldRequireTheOrderItsPaymentAndTheTime() {
        OrderEntity order = cancelledOrder("USD");
        OrderPaymentEntity payment = payment("16.09", "USD");
        Assertions.assertThrows(NullPointerException.class,
                () -> new AfterSalesTransitions.OrderCancellation(null, payment, ORDER_ITEMS, NOW));
        Assertions.assertThrows(NullPointerException.class,
                () -> new AfterSalesTransitions.OrderCancellation(order, null, ORDER_ITEMS, NOW));
        Assertions.assertThrows(NullPointerException.class,
                () -> new AfterSalesTransitions.OrderCancellation(order, payment, null, NOW));
        Assertions.assertThrows(NullPointerException.class,
                () -> new AfterSalesTransitions.OrderCancellation(order, payment, ORDER_ITEMS, null));
        OrderPaymentEntity anotherOrders = new OrderPaymentEntity(78L, ORDER_ID + 1, CUSTOMER_ID,
                new BigDecimal("16.09"), "USD", "CAPTURED", "SIMPAY-1b2c", NOW.minusHours(3));
        IllegalArgumentException mismatch = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AfterSalesTransitions.OrderCancellation(order, anotherOrders, ORDER_ITEMS, NOW));
        Assertions.assertEquals("Payment 78 belongs to order 1002, not 1001", mismatch.getMessage());
    }


    @Test
    void values_shouldRequireWhatEveryStepNeeds() {
        Assertions.assertThrows(NullPointerException.class,
                () -> new AfterSalesTransitions.Command(null, AfterSalesActor.CUSTOMER, 1L, null, null, null));
        Assertions.assertThrows(NullPointerException.class, () -> new AfterSalesTransitions.Command(
                AfterSalesTrigger.CUSTOMER_WITHDRAW, null, 1L, null, null, null));
        OrderEntity order = completedOrder("USD");
        Money amount = Money.of("1.00", "USD");
        Assertions.assertThrows(NullPointerException.class, () -> new AfterSalesTransitions.CustomerRequest(
                null, 1, AfterSalesReason.OTHER, "x", List.of(), amount, NOW, null));
        Assertions.assertThrows(NullPointerException.class, () -> new AfterSalesTransitions.CustomerRequest(
                order, 1, null, "x", List.of(), amount, NOW, null));
        Assertions.assertThrows(NullPointerException.class, () -> new AfterSalesTransitions.CustomerRequest(
                order, 1, AfterSalesReason.OTHER, null, List.of(), amount, NOW, null));
        Assertions.assertThrows(NullPointerException.class, () -> new AfterSalesTransitions.CustomerRequest(
                order, 1, AfterSalesReason.OTHER, "x", List.of(), null, NOW, null));
        Assertions.assertThrows(NullPointerException.class, () -> new AfterSalesTransitions.CustomerRequest(
                order, 1, AfterSalesReason.OTHER, "x", List.of(), amount, null, null));
    }


    // ---- fixtures ----


    /**
     * One row of table 5.2: the case as locked, the command, the case after the step and the amount of its history
     * row.
     */
    record Scenario(
            AfterSalesCaseEntity locked,
            AfterSalesTransitions.Command command,
            AfterSalesCaseEntity expected,
            String historyAmount
    ) {

        static Scenario of(AfterSalesTrigger trigger) {
            CaseRow pending = new CaseRow();
            CaseRow offered = new CaseRow().offered("5.50");
            CaseRow rejected = new CaseRow().rejected();
            CaseRow arbitration = new CaseRow().rejected().status(AfterSalesStatus.IN_ARBITRATION);
            return switch (trigger) {
                case MERCHANT_APPROVE -> new Scenario(pending.build(), merchant(trigger, null, null),
                        pending.moved().merchant("APPROVED", null).refunding("16.09", "MERCHANT_APPROVED").build(),
                        "16.09");
                case MERCHANT_REJECT -> new Scenario(pending.build(), merchant(trigger, null, "Nothing was missing."),
                        pending.moved().merchant("REJECTED", "Nothing was missing.")
                                .status(AfterSalesStatus.REJECTED).deadline(NOW.plusHours(48)).build(),
                        null);
                case MERCHANT_OFFER -> new Scenario(pending.build(),
                        merchant(trigger, Money.of("5.5", "USD"), "We can refund the fries."),
                        new CaseRow().offered("5.50").moved().deadline(NOW.plusHours(48)).build(),
                        "5.50");
                case MERCHANT_TIMEOUT -> new Scenario(pending.deadline(PAST).build(), system(trigger),
                        pending.moved().refunding("16.09", "MERCHANT_TIMEOUT").build(), "16.09");
                case CUSTOMER_ACCEPT_OFFER -> new Scenario(offered.build(), customer(trigger),
                        offered.moved().refunding("5.50", "OFFER_ACCEPTED").build(), "5.50");
                case CUSTOMER_DECLINE_OFFER -> new Scenario(offered.build(), customer(trigger),
                        offered.moved().status(AfterSalesStatus.REJECTED).deadline(NOW.plusHours(48)).build(), null);
                case OFFER_TIMEOUT -> new Scenario(offered.deadline(PAST).build(), system(trigger),
                        offered.moved().refunding("5.50", "OFFER_TIMEOUT").build(), "5.50");
                case CUSTOMER_ESCALATE -> new Scenario(rejected.build(),
                        new AfterSalesTransitions.Command(trigger, AfterSalesActor.CUSTOMER, CUSTOMER_ID, null,
                                "Please look at the photo I could not upload.", "escalate-1"),
                        rejected.moved().status(AfterSalesStatus.IN_ARBITRATION).deadline(NOW.plusHours(72)).build(),
                        null);
                case CUSTOMER_TIMEOUT -> new Scenario(rejected.deadline(PAST).build(), system(trigger),
                        rejected.moved().closed(AfterSalesCloseReason.CUSTOMER_TIMEOUT).build(), null);
                case CUSTOMER_WITHDRAW -> new Scenario(rejected.build(), customer(trigger),
                        rejected.moved().closed(AfterSalesCloseReason.WITHDRAWN).build(), null);
                case ADMIN_APPROVE_FULL -> new Scenario(arbitration.build(), admin(trigger, null, "Full refund."),
                        arbitration.moved().adminNote("Full refund.").refunding("16.09", "ARBITRATION_FULL").build(),
                        "16.09");
                case ADMIN_APPROVE_PARTIAL -> new Scenario(arbitration.build(),
                        admin(trigger, Money.of("6.00", "USD"), "The fries only."),
                        arbitration.moved().adminNote("The fries only.").refunding("6.00", "ARBITRATION_PARTIAL")
                                .build(),
                        "6.00");
                case ADMIN_REJECT -> new Scenario(arbitration.build(), admin(trigger, null, "No evidence."),
                        arbitration.moved().adminNote("No evidence.").closed(AfterSalesCloseReason.ARBITRATION_REJECTED)
                                .build(),
                        null);
                case ARBITRATION_TIMEOUT -> new Scenario(arbitration.deadline(PAST).build(), system(trigger),
                        arbitration.moved().refunding("16.09", "ARBITRATION_TIMEOUT").build(), "16.09");
                case REFUND_SUCCEEDED -> {
                    CaseRow refunding = new CaseRow().merchant("APPROVED", null)
                            .refunding("16.09", "MERCHANT_APPROVED");
                    yield new Scenario(refunding.build(), system(trigger),
                            refunding.moved().status(AfterSalesStatus.REFUNDED).refunded("16.09").build(), "16.09");
                }
            };
        }
    }


    static AfterSalesTransitions.Command customer(AfterSalesTrigger trigger) {
        return new AfterSalesTransitions.Command(trigger, AfterSalesActor.CUSTOMER, CUSTOMER_ID, null, null, "key-1");
    }


    static AfterSalesTransitions.Command merchant(AfterSalesTrigger trigger, Money amount, String note) {
        return new AfterSalesTransitions.Command(trigger, AfterSalesActor.MERCHANT, MERCHANT_ID, amount, note, null);
    }


    static AfterSalesTransitions.Command admin(AfterSalesTrigger trigger, Money amount, String note) {
        return new AfterSalesTransitions.Command(trigger, AfterSalesActor.ADMIN, ADMIN_ID, amount, note, "arbitrate-1");
    }


    static AfterSalesTransitions.Command system(AfterSalesTrigger trigger) {
        return new AfterSalesTransitions.Command(trigger, AfterSalesActor.SYSTEM, null, null, null, null);
    }


    static OrderEntity completedOrder(String currency) {
        return new OrderEntity(ORDER_ID, ORDER_NO, CUSTOMER_ID, new BigDecimal("20.00"), "COMPLETED",
                NOW.minusHours(3), 7L, null, NOW.minusHours(1), currency);
    }


    /**
     * A paid order of 16.09 (the two {@link #ORDER_ITEMS}) that was just cancelled.
     */
    static OrderEntity cancelledOrder(String currency) {
        return new OrderEntity(ORDER_ID, ORDER_NO, CUSTOMER_ID, new BigDecimal("16.09"), "CANCELLED",
                NOW.minusHours(1), 7L, "CUSTOMER_CANCELLED", NOW, currency);
    }


    static OrderPaymentEntity payment(String amount, String currency) {
        return new OrderPaymentEntity(77L, ORDER_ID, CUSTOMER_ID, new BigDecimal(amount), currency, "CAPTURED",
                "SIMPAY-0f3a", NOW.minusHours(1));
    }


    /**
     * The case's insert assigns the id and version 0, the lines' insert their ids.
     */
    private void stubCaseAndLineInserts() {
        Mockito.when(caseRepository.save(Mockito.any(AfterSalesCaseEntity.class)))
                .thenAnswer(invocation -> withIdAndVersion(invocation.getArgument(0)));
        Mockito.when(lineRepository.saveAll(Mockito.anyList())).thenAnswer(invocation -> {
            List<AfterSalesCaseLineEntity> rows = invocation.getArgument(0);
            return rows.stream().map(line -> new AfterSalesCaseLineEntity(line.orderItemId() + 100, line.caseId(),
                    line.orderItemId(), line.menuItemName(), line.unitPrice(), line.quantity(), line.amount(),
                    line.currency())).toList();
        });
    }


    static OrderPaymentEntity payment() {
        return new OrderPaymentEntity(77L, ORDER_ID, CUSTOMER_ID, new BigDecimal("20.00"), "USD", "CAPTURED",
                "SIMPAY-0f3a", NOW.minusHours(3));
    }


    private void stubCompareAndSet(int rows) {
        Mockito.when(caseRepository.compareAndSetStep(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(rows);
    }


    private void assertNothingWritten() {
        Mockito.verifyNoInteractions(caseRepository, lineRepository, historyRepository, refundRepository,
                outboxEventRepository, metricsService);
    }


    /**
     * The event the writer stored, read back with the after-sales contract as a consumer reads it.
     */
    private AfterSalesEventEnvelope storedEvent() {
        ArgumentCaptor<OutboxEventEntity> row = ArgumentCaptor.forClass(OutboxEventEntity.class);
        Mockito.verify(outboxEventRepository).save(row.capture());
        Assertions.assertEquals("after-sales-events", row.getValue().topic());
        Assertions.assertEquals(String.valueOf(ORDER_ID), row.getValue().eventKey());
        Assertions.assertEquals("AFTER_SALES_CASE", row.getValue().aggregateType());
        Assertions.assertEquals(CASE_ID, row.getValue().aggregateId());
        return EventTestSupport.contracts()
                .read(EventTopic.AFTER_SALES_EVENTS, row.getValue().payload(), AfterSalesEventEnvelope.class);
    }


    private static AfterSalesCaseEntity withIdAndVersion(AfterSalesCaseEntity row) {
        return new AfterSalesCaseEntity(CASE_ID, row.orderId(), row.customerId(), row.restaurantId(), row.kind(),
                row.attemptNo(), row.status(), row.reasonCode(), row.description(), row.orderTotal(),
                row.requestedAmount(), row.offeredAmount(), row.approvedAmount(), row.refundedAmount(), row.currency(),
                row.merchantDecision(), row.merchantNote(), row.resolution(), row.closeReason(), row.adminNote(),
                row.deadlineAt(), row.deadlineRetryAt(), row.deadlineAttempts(), 0L, row.createdAt(), row.updatedAt());
    }


    private static AfterSalesCaseEntity withoutIdAndVersion(AfterSalesCaseEntity row) {
        return new AfterSalesCaseEntity(null, row.orderId(), row.customerId(), row.restaurantId(), row.kind(),
                row.attemptNo(), row.status(), row.reasonCode(), row.description(), row.orderTotal(),
                row.requestedAmount(), row.offeredAmount(), row.approvedAmount(), row.refundedAmount(), row.currency(),
                row.merchantDecision(), row.merchantNote(), row.resolution(), row.closeReason(), row.adminNote(),
                row.deadlineAt(), row.deadlineRetryAt(), row.deadlineAttempts(), null, row.createdAt(),
                row.updatedAt());
    }


    /**
     * A customer's request of 16.09 USD on an order of 20.00, by default just opened (PENDING_MERCHANT, version 3,
     * deadline ahead, a deadline retry pending). {@link #moved()} is the same case after a step at {@link #NOW}: one
     * version later, without its deadline or its retry.
     */
    static final class CaseRow {

        private AfterSalesStatus status = AfterSalesStatus.PENDING_MERCHANT;
        private BigDecimal offered;
        private BigDecimal approved;
        private BigDecimal refunded;
        private String merchantDecision;
        private String merchantNote;
        private String resolution;
        private String closeReason;
        private String adminNote;
        private LocalDateTime deadline = FUTURE;
        private LocalDateTime retryAt = NOW.minusMinutes(5);
        private Integer attempts = 2;
        private Long version = 3L;
        private LocalDateTime updatedAt = NOW.minusHours(1);


        CaseRow status(AfterSalesStatus status) {
            this.status = status;
            return this;
        }


        CaseRow merchant(String decision, String note) {
            this.merchantDecision = decision;
            this.merchantNote = note;
            return this;
        }


        /**
         * COUNTER_OFFERED with the merchant's offer.
         */
        CaseRow offered(String amount) {
            this.offered = new BigDecimal(amount);
            return merchant("OFFERED", "We can refund the fries.").status(AfterSalesStatus.COUNTER_OFFERED);
        }


        /**
         * REJECTED by the merchant.
         */
        CaseRow rejected() {
            return merchant("REJECTED", "Nothing was missing.").status(AfterSalesStatus.REJECTED);
        }


        CaseRow refunding(String approved, String resolution) {
            this.approved = new BigDecimal(approved);
            this.resolution = resolution;
            return status(AfterSalesStatus.REFUNDING).deadline(null);
        }


        CaseRow refunded(String amount) {
            this.refunded = new BigDecimal(amount);
            return this;
        }


        CaseRow closed(AfterSalesCloseReason reason) {
            this.closeReason = reason.name();
            return status(AfterSalesStatus.CLOSED).deadline(null);
        }


        CaseRow adminNote(String note) {
            this.adminNote = note;
            return this;
        }


        CaseRow deadline(LocalDateTime deadline) {
            this.deadline = deadline;
            return this;
        }


        /**
         * A copy one step later: version 4, changed at {@link #NOW}, no deadline retry.
         */
        CaseRow moved() {
            CaseRow copy = new CaseRow();
            copy.status = status;
            copy.offered = offered;
            copy.approved = approved;
            copy.refunded = refunded;
            copy.merchantDecision = merchantDecision;
            copy.merchantNote = merchantNote;
            copy.resolution = resolution;
            copy.closeReason = closeReason;
            copy.adminNote = adminNote;
            copy.deadline = deadline;
            copy.retryAt = null;
            copy.attempts = 0;
            copy.version = version + 1;
            copy.updatedAt = NOW;
            return copy;
        }


        AfterSalesCaseEntity build() {
            return new AfterSalesCaseEntity(
                    CASE_ID,
                    ORDER_ID,
                    CUSTOMER_ID,
                    7L,
                    "CUSTOMER_REQUEST",
                    1,
                    status.name(),
                    "MISSING_ITEM",
                    "The fries were missing.",
                    new BigDecimal("20.00"),
                    new BigDecimal("16.09"),
                    offered,
                    approved,
                    refunded,
                    "USD",
                    merchantDecision,
                    merchantNote,
                    resolution,
                    closeReason,
                    adminNote,
                    deadline,
                    status.isOpen() ? retryAt : null,
                    status.isOpen() ? attempts : 0,
                    version,
                    NOW.minusHours(2),
                    updatedAt
            );
        }
    }
}
