package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.statemachine.Decision;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;


import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * The after-sales deadline processor (ADR 0002 sections 6 and 19.1 to 19.3): one claimed case per transaction, the
 * timeout of its status fired as SYSTEM through the single writer, the metric counted after the commit, a refused
 * step counted as skipped, and a failure backed off in a new transaction at the claimed version without touching the
 * deadline itself.
 */
@ExtendWith(MockitoExtension.class)
class AfterSalesDeadlineProcessorTests {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 12, 0, 30);

    static final Duration RETRY_BACKOFF = Duration.ofSeconds(30);

    @Mock
    private AfterSalesCaseRepository caseRepository;

    @Mock
    private AfterSalesTransitions transitions;

    @Mock
    private ApplicationMetricsService metricsService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private AfterSalesDeadlineProcessor processor;


    @BeforeEach
    void setup() {
        Mockito.lenient().when(transactionManager.getTransaction(Mockito.any()))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        processor = processor(100);
    }


    @ParameterizedTest
    @CsvSource({
            "PENDING_MERCHANT, MERCHANT_TIMEOUT, REFUNDING",
            "COUNTER_OFFERED, OFFER_TIMEOUT, REFUNDING",
            "REJECTED, CUSTOMER_TIMEOUT, CLOSED",
            "IN_ARBITRATION, ARBITRATION_TIMEOUT, REFUNDING"
    })
    void dueCase_shouldFireTheTimeoutOfItsStatusAsSystem(
            AfterSalesStatus waiting,
            AfterSalesTrigger timeout,
            AfterSalesStatus to
    ) {
        AfterSalesCaseEntity due = dueCase(501L, waiting, 0);
        Mockito.when(caseRepository.claimNextDue(NOW)).thenReturn(due).thenReturn(null);
        Mockito.when(transitions.fire(Mockito.eq(due), Mockito.any())).thenReturn(applied(due, timeout, to));

        Assertions.assertEquals(1, processor.fireDueDeadlines());

        ArgumentCaptor<AfterSalesTransitions.Command> command =
                ArgumentCaptor.forClass(AfterSalesTransitions.Command.class);
        Mockito.verify(transitions).fire(Mockito.eq(due), command.capture());
        Assertions.assertEquals(
                new AfterSalesTransitions.Command(timeout, AfterSalesActor.SYSTEM, null, null, null, null),
                command.getValue()
        );
        Mockito.verify(metricsService).recordAfterSalesDeadline(timeout.name(), AfterSalesDeadlineProcessor.FIRED);
        Mockito.verify(caseRepository, Mockito.never())
                .rescheduleDeadline(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }


    /**
     * The claim and the step share one transaction; the metric only counts a step that committed.
     */
    @Test
    void dueCase_shouldBeClaimedAndFiredInOneTransactionAndCountedAfterItsCommit() {
        AfterSalesCaseEntity due = dueCase(501L, AfterSalesStatus.PENDING_MERCHANT, 0);
        Mockito.when(caseRepository.claimNextDue(NOW)).thenReturn(due).thenReturn(null);
        Mockito.when(transitions.fire(Mockito.eq(due), Mockito.any()))
                .thenReturn(applied(due, AfterSalesTrigger.MERCHANT_TIMEOUT, AfterSalesStatus.REFUNDING));

        processor.fireDueDeadlines();

        InOrder steps = Mockito.inOrder(transactionManager, caseRepository, transitions, metricsService);
        steps.verify(transactionManager).getTransaction(Mockito.any());
        steps.verify(caseRepository).claimNextDue(NOW);
        steps.verify(transitions).fire(Mockito.eq(due), Mockito.any());
        steps.verify(transactionManager).commit(Mockito.any(TransactionStatus.class));
        steps.verify(metricsService).recordAfterSalesDeadline("MERCHANT_TIMEOUT", AfterSalesDeadlineProcessor.FIRED);
        // The second claim found nothing due.
        steps.verify(transactionManager).getTransaction(Mockito.any());
        steps.verify(caseRepository).claimNextDue(NOW);
        steps.verify(transactionManager).commit(Mockito.any(TransactionStatus.class));
        Mockito.verify(transactionManager, Mockito.never()).rollback(Mockito.any());
    }


    /**
     * The machine refuses the timeout only when the clock read after the claim is behind the claim's. The case is then
     * still due and the claim keeps returning it, as here: the run ends after the first refusal instead of claiming it
     * again up to the batch size (ADR 0002 section 24).
     */
    @Test
    void refusedTimeout_shouldCountAsSkippedBackNothingOffAndEndTheRun() {
        AfterSalesCaseEntity due = dueCase(501L, AfterSalesStatus.REJECTED, 0);
        Mockito.when(caseRepository.claimNextDue(NOW)).thenReturn(due);
        Mockito.when(transitions.fire(Mockito.eq(due), Mockito.any())).thenReturn(new AfterSalesTransitions.Step(
                new Decision.Rejected<>(
                        AfterSalesTrigger.CUSTOMER_TIMEOUT,
                        AfterSalesStatus.REJECTED,
                        Decision.Reason.GUARD_FAILED,
                        AfterSalesStateMachine.DEADLINE_DUE
                ),
                due
        ));

        Assertions.assertEquals(1, processor.fireDueDeadlines());

        Mockito.verify(caseRepository, Mockito.times(1)).claimNextDue(NOW);
        Mockito.verify(transitions, Mockito.times(1)).fire(Mockito.eq(due), Mockito.any());
        Mockito.verify(metricsService, Mockito.times(1))
                .recordAfterSalesDeadline("CUSTOMER_TIMEOUT", AfterSalesDeadlineProcessor.SKIPPED);
        Mockito.verify(metricsService, Mockito.never())
                .recordAfterSalesDeadline(Mockito.anyString(), Mockito.eq(AfterSalesDeadlineProcessor.FIRED));
        Mockito.verify(caseRepository, Mockito.never())
                .rescheduleDeadline(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }


    /**
     * Within a poller tick the run stops claiming once the tick's budget is spent, but always handles one case: here
     * the budget runs out after the second case although a third is due.
     */
    @Test
    void run_shouldStopClaimingOnceTheTickBudgetIsSpent() {
        Mockito.when(caseRepository.claimNextDue(NOW)).thenReturn(dueCase(501L, AfterSalesStatus.REJECTED, 0),
                dueCase(502L, AfterSalesStatus.REJECTED, 0), dueCase(503L, AfterSalesStatus.REJECTED, 0));
        Mockito.when(transitions.fire(Mockito.any(), Mockito.any())).thenAnswer(invocation -> applied(
                invocation.getArgument(0), AfterSalesTrigger.CUSTOMER_TIMEOUT, AfterSalesStatus.CLOSED));
        AtomicInteger checks = new AtomicInteger();

        Assertions.assertEquals(2, processor.fireDueDeadlinesWithin(() -> checks.incrementAndGet() >= 2));

        Assertions.assertEquals(2, checks.get(), "checked before every claim but the first");
        Mockito.verify(caseRepository, Mockito.times(2)).claimNextDue(NOW);

        // A budget spent from the start still lets the first case through.
        Mockito.clearInvocations(caseRepository);
        Assertions.assertEquals(1, processor.fireDueDeadlinesWithin(() -> true));
        Mockito.verify(caseRepository, Mockito.times(1)).claimNextDue(NOW);
    }


    /**
     * A failure rolls the step back whole; the case then backs off in a transaction of its own, at the version it was
     * claimed at, with one more attempt; deadline_at is not written. The next due case still fires in the same run.
     */
    @Test
    void failingCase_shouldBackOffInANewTransactionAndTheNextCaseStillFire() {
        AfterSalesCaseEntity failing = dueCase(501L, AfterSalesStatus.COUNTER_OFFERED, 2);
        AfterSalesCaseEntity next = dueCase(502L, AfterSalesStatus.IN_ARBITRATION, 0);
        Mockito.when(caseRepository.claimNextDue(NOW)).thenReturn(failing).thenReturn(next).thenReturn(null);
        Mockito.when(transitions.fire(Mockito.eq(failing), Mockito.any()))
                .thenThrow(new CannotAcquireLockException("lock timeout"));
        Mockito.when(transitions.fire(Mockito.eq(next), Mockito.any()))
                .thenReturn(applied(next, AfterSalesTrigger.ARBITRATION_TIMEOUT, AfterSalesStatus.REFUNDING));
        Mockito.when(caseRepository.rescheduleDeadline(501L, failing.version(), NOW.plus(RETRY_BACKOFF), 3))
                .thenReturn(1);

        Assertions.assertEquals(2, processor.fireDueDeadlines());

        InOrder steps = Mockito.inOrder(transactionManager, caseRepository, metricsService);
        steps.verify(caseRepository).claimNextDue(NOW);
        steps.verify(transactionManager).rollback(Mockito.any(TransactionStatus.class));
        steps.verify(transactionManager).getTransaction(Mockito.any());
        steps.verify(caseRepository).rescheduleDeadline(501L, failing.version(), NOW.plus(RETRY_BACKOFF), 3);
        steps.verify(transactionManager).commit(Mockito.any(TransactionStatus.class));
        steps.verify(metricsService).recordAfterSalesDeadline("OFFER_TIMEOUT", AfterSalesDeadlineProcessor.FAILED);
        steps.verify(caseRepository).claimNextDue(NOW);
        steps.verify(metricsService)
                .recordAfterSalesDeadline("ARBITRATION_TIMEOUT", AfterSalesDeadlineProcessor.FIRED);
        Mockito.verify(transactionManager, Mockito.times(1)).rollback(Mockito.any(TransactionStatus.class));
        // The back-off is the only write to the failing case besides the rolled-back step.
        Mockito.verify(caseRepository, Mockito.times(3)).claimNextDue(NOW);
        Mockito.verify(caseRepository).rescheduleDeadline(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verifyNoMoreInteractions(caseRepository);
    }


    /**
     * A command moved the case on between the rolled-back step and the back-off: the version no longer matches, the
     * back-off changes nothing, and the run goes on.
     */
    @Test
    void backOffOfACaseThatMovedOn_shouldChangeNothingAndStillCountTheFailure() {
        AfterSalesCaseEntity failing = dueCase(501L, AfterSalesStatus.PENDING_MERCHANT, 0);
        Mockito.when(caseRepository.claimNextDue(NOW)).thenReturn(failing).thenReturn(null);
        Mockito.when(transitions.fire(Mockito.eq(failing), Mockito.any()))
                .thenThrow(new IllegalStateException("Order 1001 has no captured payment to refund"));
        Mockito.when(caseRepository.rescheduleDeadline(501L, failing.version(), NOW.plus(RETRY_BACKOFF), 1))
                .thenReturn(0);

        Assertions.assertEquals(1, processor.fireDueDeadlines());

        Mockito.verify(metricsService).recordAfterSalesDeadline("MERCHANT_TIMEOUT", AfterSalesDeadlineProcessor.FAILED);
        Mockito.verify(caseRepository, Mockito.times(2)).claimNextDue(NOW);
    }


    @Test
    void failingBackOff_shouldStillCountTheFailureAndEndTheRun() {
        AfterSalesCaseEntity failing = dueCase(501L, AfterSalesStatus.PENDING_MERCHANT, 0);
        Mockito.when(caseRepository.claimNextDue(NOW)).thenReturn(failing);
        Mockito.when(transitions.fire(Mockito.eq(failing), Mockito.any()))
                .thenThrow(new CannotAcquireLockException("lock timeout"));
        Mockito.when(caseRepository.rescheduleDeadline(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        Assertions.assertThrows(DataAccessResourceFailureException.class, processor::fireDueDeadlines);

        Mockito.verify(metricsService).recordAfterSalesDeadline("MERCHANT_TIMEOUT", AfterSalesDeadlineProcessor.FAILED);
        Mockito.verify(caseRepository, Mockito.times(1)).claimNextDue(NOW);
    }


    @Test
    void failingClaim_shouldEndTheRunWithoutBackingAnythingOff() {
        Mockito.when(caseRepository.claimNextDue(NOW))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        Assertions.assertThrows(DataAccessResourceFailureException.class, processor::fireDueDeadlines);

        Mockito.verifyNoInteractions(transitions, metricsService);
        Mockito.verify(caseRepository, Mockito.never())
                .rescheduleDeadline(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }


    /**
     * chk_after_sales_case_deadline keeps a deadline off every status without a timeout; should a row break it, the
     * case backs off like any other failure instead of blocking the cases behind it.
     */
    @Test
    void claimedCaseWithoutATimeout_shouldBackOffWithoutATrigger() {
        AfterSalesCaseEntity broken = dueCase(501L, AfterSalesStatus.REFUNDING, 0);
        Mockito.when(caseRepository.claimNextDue(NOW)).thenReturn(broken).thenReturn(null);
        Mockito.when(caseRepository.rescheduleDeadline(501L, broken.version(), NOW.plus(RETRY_BACKOFF), 1))
                .thenReturn(1);

        Assertions.assertEquals(1, processor.fireDueDeadlines());

        Mockito.verifyNoInteractions(transitions);
        Mockito.verify(metricsService)
                .recordAfterSalesDeadline(AfterSalesDeadlineProcessor.NO_TRIGGER, AfterSalesDeadlineProcessor.FAILED);
    }


    @Test
    void run_shouldStopAtTheBatchSize() {
        AfterSalesDeadlineProcessor smallBatches = processor(2);
        AfterSalesCaseEntity first = dueCase(501L, AfterSalesStatus.REJECTED, 0);
        AfterSalesCaseEntity second = dueCase(502L, AfterSalesStatus.REJECTED, 0);
        Mockito.when(caseRepository.claimNextDue(NOW))
                .thenReturn(first, second, dueCase(503L, AfterSalesStatus.REJECTED, 0));
        Mockito.when(transitions.fire(Mockito.any(), Mockito.any())).thenAnswer(invocation -> applied(
                invocation.getArgument(0), AfterSalesTrigger.CUSTOMER_TIMEOUT, AfterSalesStatus.CLOSED));

        Assertions.assertEquals(2, smallBatches.fireDueDeadlines());

        Mockito.verify(caseRepository, Mockito.times(2)).claimNextDue(NOW);
        Mockito.verify(metricsService, Mockito.times(2))
                .recordAfterSalesDeadline("CUSTOMER_TIMEOUT", AfterSalesDeadlineProcessor.FIRED);
    }


    /**
     * The poller's thread is interrupted when it stops: the run ends before the next claim.
     */
    @Test
    void interruptedThread_shouldClaimNothing() {
        Thread.currentThread().interrupt();
        try {
            Assertions.assertEquals(0, processor.fireDueDeadlines());
        } finally {
            Assertions.assertTrue(Thread.interrupted(), "the interrupt is left for the poller to see");
        }
        Mockito.verifyNoInteractions(caseRepository, transitions, metricsService);
    }


    /**
     * A clock with nanoseconds (Linux) is read in microseconds, what the database keeps: the claim and the back-off's
     * deadline_retry_at get the time without them.
     */
    @Test
    void clockWithNanoseconds_shouldBeReadInMicroseconds() {
        LocalDateTime inMicros = NOW.withNano(123_456_000);
        AfterSalesCaseEntity failing = dueCase(501L, AfterSalesStatus.PENDING_MERCHANT, 0);
        Mockito.when(caseRepository.claimNextDue(inMicros)).thenReturn(failing).thenReturn(null);
        Mockito.when(transitions.fire(Mockito.eq(failing), Mockito.any()))
                .thenThrow(new CannotAcquireLockException("lock timeout"));
        Mockito.when(caseRepository.rescheduleDeadline(501L, failing.version(), inMicros.plus(RETRY_BACKOFF), 1))
                .thenReturn(1);
        AfterSalesDeadlineProcessor nanosecondClock = new AfterSalesDeadlineProcessor(caseRepository, transitions,
                metricsService, transactionManager, 100, RETRY_BACKOFF, () -> NOW.withNano(123_456_789));

        Assertions.assertEquals(1, nanosecondClock.fireDueDeadlines());

        Mockito.verify(caseRepository, Mockito.times(2)).claimNextDue(inMicros);
        Mockito.verify(caseRepository).rescheduleDeadline(501L, failing.version(), inMicros.plus(RETRY_BACKOFF), 1);
    }


    @Test
    void retryBackoff_shouldBePositive() {
        for (Duration backoff : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1), null}) {
            IllegalArgumentException refused = Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new AfterSalesDeadlineProcessor(caseRepository, transitions, metricsService,
                            transactionManager, 100, backoff));
            Assertions.assertEquals(
                    "app.after-sales.poller.retry-backoff must be positive, got " + backoff, refused.getMessage());
        }
    }


    private AfterSalesDeadlineProcessor processor(int batchSize) {
        return new AfterSalesDeadlineProcessor(
                caseRepository, transitions, metricsService, transactionManager, batchSize, RETRY_BACKOFF, () -> NOW);
    }


    /**
     * A customer's request in {@code status} whose deadline passed a minute before {@link #NOW}, at version 4, after
     * {@code attempts} failed deadline runs.
     */
    private static AfterSalesCaseEntity dueCase(Long id, AfterSalesStatus status, int attempts) {
        boolean offered = status == AfterSalesStatus.COUNTER_OFFERED;
        return new AfterSalesCaseEntity(id, id + 500, 3L, 7L, "CUSTOMER_REQUEST", 1, status.name(), "MISSING_ITEM",
                "The fries were missing.", new BigDecimal("16.09"), new BigDecimal("16.09"),
                offered ? new BigDecimal("5.00") : null, null, null, "USD", offered ? "OFFERED" : null, null, null, null,
                null, NOW.minusMinutes(1), attempts == 0 ? null : NOW.minusSeconds(5), attempts, 4L,
                NOW.minusDays(1), NOW.minusHours(1));
    }


    private static AfterSalesTransitions.Step applied(
            AfterSalesCaseEntity due,
            AfterSalesTrigger trigger,
            AfterSalesStatus to
    ) {
        AfterSalesCaseEntity moved = new AfterSalesCaseEntity(due.id(), due.orderId(), due.customerId(),
                due.restaurantId(), due.kind(), due.attemptNo(), to.name(), due.reasonCode(), due.description(),
                due.orderTotal(), due.requestedAmount(), due.offeredAmount(),
                to == AfterSalesStatus.REFUNDING ? due.requestedAmount() : null, null, due.currency(),
                due.merchantDecision(), due.merchantNote(), null, to == AfterSalesStatus.CLOSED ? "CUSTOMER_TIMEOUT" : null,
                null, null, null, 0, due.version() + 1, due.createdAt(), NOW);
        return new AfterSalesTransitions.Step(
                new Decision.Apply<>(trigger, AfterSalesStatus.valueOf(due.status()), to), moved);
    }
}
