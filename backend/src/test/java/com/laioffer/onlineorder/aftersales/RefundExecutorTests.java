package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.entity.OrderPaymentEntity;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.OrderPaymentRepository;
import com.laioffer.onlineorder.statemachine.Decision;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;


/**
 * The refund executor (ADR 0002 D8, sections 5.4 and 9, with 19.4, 19.9 and 19.10): a claim transaction that leases
 * the refund (or gives it up without a call), the gateway call outside any transaction and within its timeout, and a
 * record transaction that keeps a result only for the claimed version and marks the case refunded on a success. The
 * repositories here keep the refund rows in a map; save raises the version as Spring Data JDBC does.
 */
@ExtendWith(MockitoExtension.class)
class RefundExecutorTests {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 12, 0, 30);

    static final long REFUND_ID = 70L;

    static final long CASE_ID = 501L;

    static final long ORDER_ID = 1001L;

    static final String GATEWAY_KEY = "after-sales-case:" + CASE_ID;

    static final String PAYMENT_REFERENCE = "SIMPAY-0123456789abcdef";

    static final String PROVIDER_REFERENCE = "SIMREF-0123456789abcdef0123";

    static final int MAX_ATTEMPTS = 8;

    static final int GATEWAY_THREADS = 4;

    static final RefundExecutor.Settings SETTINGS = new RefundExecutor.Settings(
            100, MAX_ATTEMPTS, Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofSeconds(60),
            Duration.ofSeconds(10), GATEWAY_THREADS);

    @Mock
    private RefundRepository refundRepository;

    @Mock
    private AfterSalesCaseRepository caseRepository;

    @Mock
    private OrderPaymentRepository orderPaymentRepository;

    @Mock
    private AfterSalesTransitions transitions;

    @Mock
    private RefundGateway gateway;

    @Mock
    private ApplicationMetricsService metricsService;

    @Mock
    private PlatformTransactionManager transactionManager;

    /**
     * The refunds table: what save wrote last, with its raised version.
     */
    private final Map<Long, RefundEntity> rows = new HashMap<>();

    /**
     * Every refund passed to save, as passed (at the version it was read).
     */
    private final List<RefundEntity> writes = new ArrayList<>();

    private final List<RefundExecutor> executors = new ArrayList<>();


    @BeforeEach
    void setup() {
        Mockito.lenient().when(transactionManager.getTransaction(Mockito.any()))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        Mockito.lenient().when(refundRepository.save(Mockito.any())).thenAnswer(invocation -> {
            RefundEntity refund = invocation.getArgument(0);
            writes.add(refund);
            RefundEntity written = withVersion(refund, refund.version() + 1);
            rows.put(refund.id(), written);
            return written;
        });
        Mockito.lenient().when(refundRepository.lockById(Mockito.anyLong()))
                .thenAnswer(invocation -> rows.get(invocation.<Long>getArgument(0)));
        Mockito.lenient().when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(refundingCase("20.00")));
        Mockito.lenient().when(orderPaymentRepository.findByOrderId(ORDER_ID))
                .thenReturn(Optional.of(payment("25.00", "USD", PAYMENT_REFERENCE)));
    }


    @AfterEach
    void shutDown() {
        executors.forEach(RefundExecutor::destroy);
    }


    /**
     * The three parts in order: the claim transaction leases the refund (PROCESSING, one attempt, the lease, no next
     * attempt) and commits; the gateway is called outside any transaction; the record transaction marks the refund
     * SUCCEEDED, locks the case and takes REFUND_SUCCEEDED through the single writer; the metric counts after the
     * commit.
     */
    @Test
    void dueRefund_shouldBeClaimedCalledOutsideATransactionAndRecordedAsSucceeded() {
        due(pending(0));
        AfterSalesCaseEntity refunding = refundingCase("20.00");
        Mockito.when(caseRepository.lockById(CASE_ID)).thenReturn(refunding);
        Mockito.when(transitions.fire(Mockito.eq(refunding), Mockito.any())).thenReturn(refunded(refunding));
        Mockito.when(gateway.refund(Mockito.any())).thenReturn(new RefundGatewayResult.Succeeded(PROVIDER_REFERENCE));

        Assertions.assertEquals(1, executor(SETTINGS).runDueRefunds());

        Assertions.assertEquals(List.of(
                "PROCESSING|1|null|2026-10-21T12:01:30|null|null|v2|2026-10-21T12:00:30|null",
                "SUCCEEDED|1|null|null|" + PROVIDER_REFERENCE + "|null|v3|2026-10-21T12:00:30|2026-10-21T12:00:30"
        ), writes.stream().map(RefundExecutorTests::summary).toList());
        writes.forEach(RefundExecutorTests::assertSameRefund);
        Mockito.verify(gateway).refund(new RefundGatewayRequest(
                GATEWAY_KEY, ORDER_ID, Money.of("20.00", "USD"), 1, PAYMENT_REFERENCE));
        ArgumentCaptor<AfterSalesTransitions.Command> command =
                ArgumentCaptor.forClass(AfterSalesTransitions.Command.class);
        Mockito.verify(transitions).fire(Mockito.eq(refunding), command.capture());
        Assertions.assertEquals(
                new AfterSalesTransitions.Command(
                        AfterSalesTrigger.REFUND_SUCCEEDED, AfterSalesActor.SYSTEM, null, null, null, null),
                command.getValue()
        );

        InOrder steps = Mockito.inOrder(
                transactionManager, refundRepository, gateway, caseRepository, transitions, metricsService);
        steps.verify(transactionManager).getTransaction(Mockito.any());
        steps.verify(refundRepository).claimNextDue(NOW);
        steps.verify(refundRepository).save(Mockito.any());
        steps.verify(transactionManager).commit(Mockito.any(TransactionStatus.class));
        steps.verify(gateway).refund(Mockito.any());
        steps.verify(transactionManager).getTransaction(Mockito.any());
        steps.verify(refundRepository).lockById(REFUND_ID);
        steps.verify(refundRepository).save(Mockito.any());
        steps.verify(caseRepository).lockById(CASE_ID);
        steps.verify(transitions).fire(Mockito.eq(refunding), Mockito.any());
        steps.verify(transactionManager).commit(Mockito.any(TransactionStatus.class));
        steps.verify(metricsService).recordAfterSalesRefund(RefundExecutor.SUCCEEDED);
        Mockito.verify(transactionManager, Mockito.never()).rollback(Mockito.any());
    }


    /**
     * A retryable failure with attempts left: back to PENDING after retry-backoff x 2^(attempts - 1), with its error,
     * without a lease.
     */
    @Test
    void retryableFailure_withAttemptsLeft_shouldWaitForAnExponentialBackoff() {
        due(pending(2));
        Mockito.when(gateway.refund(Mockito.any()))
                .thenReturn(new RefundGatewayResult.RetryableFailure("gateway unavailable"));

        Assertions.assertEquals(1, executor(SETTINGS).runDueRefunds());

        Assertions.assertEquals(List.of(
                // The claim keeps the error of the attempt before until this one has its own.
                "PROCESSING|3|null|2026-10-21T12:01:30|null|earlier failure|v2|2026-10-21T12:00:30|null",
                "PENDING|3|2026-10-21T12:01:10|null|null|gateway unavailable|v3|2026-10-21T12:00:30|null"
        ), writes.stream().map(RefundExecutorTests::summary).toList());
        Mockito.verify(gateway).refund(new RefundGatewayRequest(
                GATEWAY_KEY, ORDER_ID, Money.of("20.00", "USD"), 3, PAYMENT_REFERENCE));
        Mockito.verify(metricsService).recordAfterSalesRefund(RefundExecutor.RETRIED);
        Mockito.verifyNoInteractions(transitions);
        Mockito.verify(caseRepository, Mockito.never()).lockById(Mockito.any());
    }


    @Test
    void backoff_shouldDoubleWithEveryAttemptUpToItsMaximum() {
        RefundExecutor refundExecutor = executor(SETTINGS);

        List<Duration> backoffs = new ArrayList<>();
        for (int attempts = 1; attempts <= 8; attempts++) {
            backoffs.add(refundExecutor.backoff(attempts));
        }

        Assertions.assertEquals(List.of(10L, 20L, 40L, 80L, 160L, 320L, 600L, 600L),
                backoffs.stream().map(Duration::toSeconds).toList());
        Assertions.assertEquals(Duration.ofMinutes(10), refundExecutor.backoff(Integer.MAX_VALUE));
        RefundExecutor flat = executor(new RefundExecutor.Settings(
                100, MAX_ATTEMPTS, Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(60),
                Duration.ofSeconds(10), GATEWAY_THREADS));
        Assertions.assertEquals(Duration.ofSeconds(5), flat.backoff(1));
        Assertions.assertEquals(Duration.ofSeconds(5), flat.backoff(3));
    }


    /**
     * The last attempt's retryable failure and any permanent failure give the refund up: FAILED with the gateway's
     * error, the case left in REFUNDING.
     */
    @ParameterizedTest
    @CsvSource({
            "7, RETRYABLE, gateway unavailable, 8",
            "0, PERMANENT, capture already refunded, 1",
            "4, PERMANENT, card closed, 5"
    })
    void failure_withoutAnAttemptLeftOrPermanent_shouldGiveTheRefundUp(
            int attemptsBefore,
            String kind,
            String error,
            int attempts
    ) {
        due(pending(attemptsBefore));
        Mockito.when(gateway.refund(Mockito.any())).thenReturn("RETRYABLE".equals(kind)
                ? new RefundGatewayResult.RetryableFailure(error)
                : new RefundGatewayResult.PermanentFailure(error));

        Assertions.assertEquals(1, executor(SETTINGS).runDueRefunds());

        Assertions.assertEquals("FAILED|" + attempts + "|null|null|null|" + error + "|v3|2026-10-21T12:00:30|null",
                summary(writes.get(1)));
        Mockito.verify(metricsService).recordAfterSalesRefund(RefundExecutor.FAILED);
        Mockito.verifyNoInteractions(transitions);
        Mockito.verify(caseRepository, Mockito.never()).lockById(Mockito.any());
    }


    /**
     * An expired lease (its executor died or stalled) is reclaimed: one more attempt, a new lease, the same key.
     */
    @Test
    void expiredLease_shouldBeReclaimedAsAnotherAttemptWithTheSameKey() {
        due(processing(3, NOW));
        Mockito.when(gateway.refund(Mockito.any()))
                .thenReturn(new RefundGatewayResult.RetryableFailure("gateway unavailable"));

        executor(SETTINGS).runDueRefunds();

        Assertions.assertEquals("PROCESSING|4|null|2026-10-21T12:01:30|null|null|v2|2026-10-21T12:00:30|null",
                summary(writes.get(0)));
        Mockito.verify(gateway).refund(new RefundGatewayRequest(
                GATEWAY_KEY, ORDER_ID, Money.of("20.00", "USD"), 4, PAYMENT_REFERENCE));
        // The fourth failure waits 10 s x 2^3.
        Assertions.assertEquals(
                "PENDING|4|2026-10-21T12:01:50|null|null|gateway unavailable|v3|2026-10-21T12:00:30|null",
                summary(writes.get(1)));
    }


    /**
     * Section 19.9: an expired lease with the attempts used up is given up in the claim transaction, without calling
     * the gateway again; the next due refund is still executed.
     */
    @Test
    void expiredLease_withNoAttemptLeft_shouldBeGivenUpWithoutACall() {
        RefundEntity exhausted = processing(MAX_ATTEMPTS, NOW.minusSeconds(1));
        Mockito.when(refundRepository.claimNextDue(NOW)).thenReturn(exhausted).thenReturn(null);
        rows.put(exhausted.id(), exhausted);

        Assertions.assertEquals(1, executor(SETTINGS).runDueRefunds());

        Assertions.assertEquals(List.of("FAILED|8|null|null|null|The lease of attempt 8 expired without a result and "
                        + "no attempt is left|v2|2026-10-21T12:00:30|null"),
                writes.stream().map(RefundExecutorTests::summary).toList());
        Mockito.verifyNoInteractions(gateway, transitions);
        Mockito.verify(refundRepository, Mockito.never()).lockById(Mockito.any());
        Mockito.verify(metricsService).recordAfterSalesRefund(RefundExecutor.FAILED);
    }


    /**
     * A claimed refund the machine does not let the executor take (a lease that has not run out, a refund already
     * done) is left as it is and ends the run, which would otherwise claim it again at once.
     */
    @ParameterizedTest
    @CsvSource({"PROCESSING", "SUCCEEDED", "FAILED"})
    void claimedRefundTheMachineRefuses_shouldBeSkippedAndEndTheRun(RefundStatus status) {
        RefundEntity notDue = new RefundEntity(REFUND_ID, CASE_ID, ORDER_ID, 3L, GATEWAY_KEY, PAYMENT_REFERENCE,
                new BigDecimal("20.00"), "USD", status.name(), 1, null,
                status == RefundStatus.PROCESSING ? NOW.plusSeconds(1) : null,
                status == RefundStatus.SUCCEEDED ? PROVIDER_REFERENCE : null, null, 2L, NOW, NOW,
                status == RefundStatus.SUCCEEDED ? NOW : null);
        Mockito.when(refundRepository.claimNextDue(NOW)).thenReturn(notDue);

        Assertions.assertEquals(1, executor(SETTINGS).runDueRefunds());

        Mockito.verify(refundRepository).claimNextDue(NOW);
        Mockito.verify(refundRepository, Mockito.never()).save(Mockito.any());
        Mockito.verifyNoInteractions(gateway, transitions);
        Mockito.verify(metricsService).recordAfterSalesRefund(RefundExecutor.SKIPPED);
    }


    /**
     * Section 19.4: a refund that no longer matches its case or the order's payment is given up after the claim and
     * before the gateway: FAILED, the claim counted as an attempt, the reason kept as last_error.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "CLOSED|20.00|25.00|USD|" + PAYMENT_REFERENCE + "|Case 501 is CLOSED, not REFUNDING",
            "REFUNDING||25.00|USD|" + PAYMENT_REFERENCE + "|The refund of 20.00 USD is not the approved amount "
                    + "null USD of case 501",
            "REFUNDING|19.99|25.00|USD|" + PAYMENT_REFERENCE + "|The refund of 20.00 USD is not the approved amount "
                    + "19.99 USD of case 501",
            "REFUNDING|20.00||USD|" + PAYMENT_REFERENCE + "|Order 1001 has no captured payment",
            "REFUNDING|20.00|25.00|USD|SIMPAY-other|The refund names the payment " + PAYMENT_REFERENCE + " but order "
                    + "1001 was paid by SIMPAY-other",
            "REFUNDING|20.00|25.00|EUR|" + PAYMENT_REFERENCE + "|The refund of 20.00 USD exceeds the 25.00 EUR "
                    + "captured by payment " + PAYMENT_REFERENCE,
            "REFUNDING|20.00|19.99|USD|" + PAYMENT_REFERENCE + "|The refund of 20.00 USD exceeds the 19.99 USD "
                    + "captured by payment " + PAYMENT_REFERENCE
    })
    void refundThatNoLongerMatches_shouldBeGivenUpBeforeTheGateway(
            String caseStatus,
            String approved,
            String captured,
            String capturedCurrency,
            String paymentReference,
            String error
    ) {
        due(pending(0));
        Mockito.when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseIn(caseStatus, approved)));
        Mockito.lenient().when(orderPaymentRepository.findByOrderId(ORDER_ID)).thenReturn(captured == null
                ? Optional.empty()
                : Optional.of(payment(captured, capturedCurrency, paymentReference)));

        Assertions.assertEquals(1, executor(SETTINGS).runDueRefunds());

        Assertions.assertEquals(List.of("FAILED|1|null|null|null|" + error + "|v2|2026-10-21T12:00:30|null"),
                writes.stream().map(RefundExecutorTests::summary).toList());
        Mockito.verifyNoInteractions(gateway, transitions);
        Mockito.verify(metricsService).recordAfterSalesRefund(RefundExecutor.FAILED);
    }


    /**
     * The check compares money, not BigDecimal scales, and a capture of exactly the refund is enough.
     */
    @Test
    void refundOfTheWholeCapture_shouldBeCalled() {
        due(pending(0));
        Mockito.when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseIn("REFUNDING", "20.0")));
        Mockito.when(orderPaymentRepository.findByOrderId(ORDER_ID))
                .thenReturn(Optional.of(payment("20", "USD", PAYMENT_REFERENCE)));
        Mockito.when(gateway.refund(Mockito.any()))
                .thenReturn(new RefundGatewayResult.RetryableFailure("gateway unavailable"));

        executor(SETTINGS).runDueRefunds();

        Mockito.verify(gateway).refund(Mockito.any());
        Mockito.verify(metricsService).recordAfterSalesRefund(RefundExecutor.RETRIED);
    }


    /**
     * Section 24: a claim transaction that fails after the claim (here a refund without its case) rolls back and backs
     * off that refund alone, in a transaction of its own at the version claimed: its next attempt one retry backoff
     * ahead, the failure as last_error, its status and attempts as they were. The refund behind it is still executed
     * in the same run instead of waiting behind it.
     */
    @Test
    void claimThatFailsAfterTheClaim_shouldBackOffThatRefundAloneAndGoOn() {
        RefundEntity failing = pending(0);
        RefundEntity next = otherRefund();
        Mockito.when(refundRepository.claimNextDue(NOW)).thenReturn(failing).thenReturn(next).thenReturn(null);
        rows.put(failing.id(), failing);
        rows.put(next.id(), next);
        Mockito.when(caseRepository.findById(CASE_ID)).thenReturn(Optional.empty());
        Mockito.when(caseRepository.findById(next.caseId())).thenReturn(Optional.of(caseFor(next)));
        Mockito.when(orderPaymentRepository.findByOrderId(next.orderId())).thenReturn(Optional.of(paymentFor(next)));
        String error = "The claim of attempt 1 failed: java.lang.IllegalStateException: Refund 70 has no case";
        Mockito.when(refundRepository.backOffPending(REFUND_ID, 2L, NOW.plusSeconds(10), error, NOW)).thenReturn(1);
        Mockito.when(gateway.refund(Mockito.any()))
                .thenReturn(new RefundGatewayResult.RetryableFailure("gateway unavailable"));

        Assertions.assertEquals(2, executor(SETTINGS).runDueRefunds());

        InOrder steps = Mockito.inOrder(transactionManager, refundRepository, gateway, metricsService);
        steps.verify(refundRepository).claimNextDue(NOW);
        steps.verify(transactionManager).rollback(Mockito.any(TransactionStatus.class));
        steps.verify(transactionManager).getTransaction(Mockito.any());
        steps.verify(refundRepository).backOffPending(REFUND_ID, 2L, NOW.plusSeconds(10), error, NOW);
        steps.verify(transactionManager).commit(Mockito.any(TransactionStatus.class));
        steps.verify(metricsService).recordAfterSalesRefund(RefundExecutor.BACKED_OFF);
        steps.verify(refundRepository).claimNextDue(NOW);
        steps.verify(gateway).refund(Mockito.any());
        steps.verify(metricsService).recordAfterSalesRefund(RefundExecutor.RETRIED);
        Mockito.verify(transactionManager, Mockito.times(1)).rollback(Mockito.any());
        // Only the next refund was saved: its claim and its retry.
        Assertions.assertEquals(List.of(next.id(), next.id()), writes.stream().map(RefundEntity::id).toList());
        Mockito.verify(refundRepository, Mockito.never()).backOffExpiredLease(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }


    /**
     * An expired lease whose reclaim fails after the claim stays PROCESSING with its attempts, and the end of its lease
     * moves one retry backoff ahead so that it is not reclaimed before.
     */
    @Test
    void reclaimThatFailsAfterTheClaim_shouldMoveTheEndOfTheExpiredLease() {
        due(processing(3, NOW.minusSeconds(1)));
        Mockito.when(orderPaymentRepository.findByOrderId(ORDER_ID))
                .thenThrow(new CannotAcquireLockException("lock timeout"));
        String error = "The claim of attempt 4 failed: org.springframework.dao.CannotAcquireLockException: "
                + "lock timeout";
        Mockito.when(refundRepository.backOffExpiredLease(REFUND_ID, 2L, NOW.plusSeconds(10), error, NOW))
                .thenReturn(1);

        Assertions.assertEquals(1, executor(SETTINGS).runDueRefunds());

        Mockito.verify(refundRepository).backOffExpiredLease(REFUND_ID, 2L, NOW.plusSeconds(10), error, NOW);
        Mockito.verify(refundRepository, Mockito.never()).backOffPending(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Assertions.assertTrue(writes.isEmpty());
        Mockito.verifyNoInteractions(gateway, transitions);
        Mockito.verify(metricsService).recordAfterSalesRefund(RefundExecutor.BACKED_OFF);
        Mockito.verify(refundRepository, Mockito.times(2)).claimNextDue(NOW);
    }


    /**
     * The refund moved on between the rolled-back claim and the back-off (another executor claimed it): the back-off
     * changes nothing, still counts, and the run goes on.
     */
    @Test
    void backOffOfARefundThatMovedOn_shouldChangeNothingAndGoOn() {
        due(pending(0));
        Mockito.when(caseRepository.findById(CASE_ID)).thenThrow(new IllegalStateException("broken row"));
        Mockito.when(refundRepository.backOffPending(Mockito.eq(REFUND_ID), Mockito.eq(2L), Mockito.any(),
                Mockito.any(), Mockito.any())).thenReturn(0);

        Assertions.assertEquals(1, executor(SETTINGS).runDueRefunds());

        Mockito.verify(metricsService).recordAfterSalesRefund(RefundExecutor.BACKED_OFF);
        Mockito.verify(refundRepository, Mockito.times(2)).claimNextDue(NOW);
        Mockito.verifyNoInteractions(gateway);
    }


    /**
     * A back-off that fails too (the database is gone) ends the run with its exception, after counting the refund.
     */
    @Test
    void failingBackOff_shouldStillCountTheRefundAndEndTheRun() {
        RefundEntity failing = pending(0);
        Mockito.when(refundRepository.claimNextDue(NOW)).thenReturn(failing);
        rows.put(failing.id(), failing);
        Mockito.when(caseRepository.findById(CASE_ID)).thenThrow(new CannotAcquireLockException("lock timeout"));
        Mockito.when(refundRepository.backOffPending(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any())).thenThrow(new DataAccessResourceFailureException("database unavailable"));

        Assertions.assertThrows(DataAccessResourceFailureException.class, () -> executor(SETTINGS).runDueRefunds());

        Mockito.verify(metricsService).recordAfterSalesRefund(RefundExecutor.BACKED_OFF);
        Mockito.verify(refundRepository, Mockito.times(1)).claimNextDue(NOW);
        Mockito.verifyNoInteractions(gateway);
    }


    /**
     * The lease expired during the call and another executor reclaimed the refund (its version moved): this result is
     * discarded, nothing is written and the case is not touched. A refund that is gone counts the same.
     */
    @Test
    void resultForARefundThatMovedOn_shouldBeDiscardedAsSuperseded() {
        due(pending(0));
        Mockito.when(gateway.refund(Mockito.any())).thenAnswer(invocation -> {
            // Another executor reclaims the refund meanwhile.
            rows.put(REFUND_ID, withVersion(rows.get(REFUND_ID), 4L));
            return new RefundGatewayResult.Succeeded(PROVIDER_REFERENCE);
        });

        Assertions.assertEquals(1, executor(SETTINGS).runDueRefunds());

        Assertions.assertEquals(1, writes.size(), "only the claim");
        Mockito.verify(refundRepository).lockById(REFUND_ID);
        Mockito.verifyNoInteractions(transitions);
        Mockito.verify(caseRepository, Mockito.never()).lockById(Mockito.any());
        Mockito.verify(metricsService).recordAfterSalesRefund(RefundExecutor.SUPERSEDED);

        rows.clear();
        writes.clear();
        due(pending(0));
        Mockito.when(gateway.refund(Mockito.any())).thenAnswer(invocation -> {
            rows.remove(REFUND_ID);
            return new RefundGatewayResult.Succeeded(PROVIDER_REFERENCE);
        });
        executor(SETTINGS).runDueRefunds();
        Assertions.assertEquals(1, writes.size());
        Mockito.verify(metricsService, Mockito.times(2)).recordAfterSalesRefund(RefundExecutor.SUPERSEDED);
    }


    /**
     * Section 19.10: a call that does not answer within the gateway timeout is cancelled (its thread interrupted) and
     * counts as a retryable failure.
     */
    @Test
    void gatewayThatDoesNotAnswerInTime_shouldBeCancelledAndRetried() throws Exception {
        due(pending(0));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicReference<String> callThread = new AtomicReference<>();
        Mockito.when(gateway.refund(Mockito.any())).thenAnswer(invocation -> {
            callThread.set(Thread.currentThread().getName());
            try {
                release.await();
            } catch (InterruptedException ex) {
                interrupted.countDown();
            }
            return new RefundGatewayResult.Succeeded(PROVIDER_REFERENCE);
        });
        RefundExecutor refundExecutor = executor(new RefundExecutor.Settings(
                100, MAX_ATTEMPTS, Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofSeconds(60),
                Duration.ofMillis(100), GATEWAY_THREADS));

        try {
            Assertions.assertEquals(1, refundExecutor.runDueRefunds());
        } finally {
            release.countDown();
        }

        Assertions.assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the timed-out call was not interrupted");
        Assertions.assertTrue(callThread.get().startsWith(RefundExecutor.GATEWAY_THREAD_PREFIX), callThread.get());
        Assertions.assertEquals("PENDING|1|2026-10-21T12:00:40|null|null|The refund gateway did not answer within "
                + "100 ms|v3|2026-10-21T12:00:30|null", summary(writes.get(1)));
        Mockito.verify(metricsService).recordAfterSalesRefund(RefundExecutor.RETRIED);
    }


    /**
     * Any exception of the gateway, and no answer at all, count as retryable failures.
     */
    @Test
    void gatewayThatThrowsOrAnswersNothing_shouldBeRetried() {
        due(pending(0));
        Mockito.when(gateway.refund(Mockito.any())).thenThrow(new IllegalStateException("connection reset"));

        executor(SETTINGS).runDueRefunds();

        Assertions.assertEquals("PENDING|1|2026-10-21T12:00:40|null|null|java.lang.IllegalStateException: "
                + "connection reset|v3|2026-10-21T12:00:30|null", summary(writes.get(1)));

        rows.clear();
        writes.clear();
        due(pending(0));
        Mockito.doReturn(null).when(gateway).refund(Mockito.any());
        executor(SETTINGS).runDueRefunds();
        Assertions.assertEquals("PENDING|1|2026-10-21T12:00:40|null|null|The refund gateway answered nothing|v3|"
                + "2026-10-21T12:00:30|null", summary(writes.get(1)));
        Mockito.verify(metricsService, Mockito.times(2)).recordAfterSalesRefund(RefundExecutor.RETRIED);
    }


    /**
     * An Error from the gateway is not an answer: it ends the run and the refund keeps its lease, to be reclaimed.
     */
    @Test
    void gatewayError_shouldEndTheRunWithoutRecordingAnything() {
        due(pending(0));
        Mockito.when(gateway.refund(Mockito.any())).thenThrow(new AssertionError("broken"));

        AssertionError failure = Assertions.assertThrows(
                AssertionError.class, () -> executor(SETTINGS).runDueRefunds());

        Assertions.assertEquals("broken", failure.getMessage());
        Assertions.assertEquals(List.of("PROCESSING"), writes.stream().map(RefundEntity::status).toList());
        Mockito.verify(refundRepository, Mockito.never()).lockById(Mockito.any());
        Mockito.verifyNoInteractions(metricsService);
    }


    /**
     * An executor stopped while it waits for the gateway (the poller shutting down) cancels the call, records nothing
     * and keeps the interrupt; the refund is reclaimed once its lease ends.
     */
    @Test
    void interruptWhileWaitingForTheGateway_shouldCancelTheCallAndRecordNothing() throws Exception {
        due(pending(0));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Mockito.when(gateway.refund(Mockito.any())).thenAnswer(invocation -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException ex) {
                interrupted.countDown();
            }
            return new RefundGatewayResult.Succeeded(PROVIDER_REFERENCE);
        });
        RefundExecutor refundExecutor = executor(SETTINGS);
        AtomicReference<Thread> runner = new AtomicReference<>();
        ExecutorService threads = Executors.newSingleThreadExecutor();
        try {
            Future<String> run = threads.submit(() -> {
                runner.set(Thread.currentThread());
                int handled = refundExecutor.runDueRefunds();
                return handled + "|" + Thread.currentThread().isInterrupted();
            });
            Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS));
            runner.get().interrupt();

            Assertions.assertEquals("1|true", run.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
        Assertions.assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the call was not cancelled");
        Assertions.assertEquals(List.of("PROCESSING"), writes.stream().map(RefundEntity::status).toList());
        Mockito.verify(refundRepository, Mockito.never()).lockById(Mockito.any());
        Mockito.verify(metricsService).recordAfterSalesRefund(RefundExecutor.UNRECORDED);
    }


    /**
     * A record transaction that fails (here a lock timeout) rolls back and counts as unrecorded; the refund keeps its
     * lease and the run goes on with the next due refund.
     */
    @Test
    void recordThatFails_shouldRollBackAndGoOnWithTheNextRefund() {
        RefundEntity first = pending(0);
        RefundEntity second = otherRefund();
        Mockito.when(refundRepository.claimNextDue(NOW)).thenReturn(first).thenReturn(second).thenReturn(null);
        rows.put(first.id(), first);
        rows.put(second.id(), second);
        Mockito.when(caseRepository.findById(second.caseId())).thenReturn(Optional.of(caseFor(second)));
        Mockito.when(orderPaymentRepository.findByOrderId(second.orderId()))
                .thenReturn(Optional.of(paymentFor(second)));
        Mockito.when(refundRepository.lockById(REFUND_ID)).thenThrow(new CannotAcquireLockException("lock timeout"));
        Mockito.when(gateway.refund(Mockito.any()))
                .thenReturn(new RefundGatewayResult.RetryableFailure("gateway unavailable"));

        Assertions.assertEquals(2, executor(SETTINGS).runDueRefunds());

        Assertions.assertEquals("PROCESSING", rows.get(REFUND_ID).status(), "the first refund keeps its lease");
        Assertions.assertEquals("PENDING", rows.get(second.id()).status());
        Mockito.verify(transactionManager).rollback(Mockito.any());
        InOrder outcomes = Mockito.inOrder(metricsService);
        outcomes.verify(metricsService).recordAfterSalesRefund(RefundExecutor.UNRECORDED);
        outcomes.verify(metricsService).recordAfterSalesRefund(RefundExecutor.RETRIED);
    }


    /**
     * A success whose case cannot be marked refunded (not in REFUNDING after all) rolls back whole: the refund keeps
     * its lease, as for any result that cannot be recorded.
     */
    @Test
    void successWhoseCaseIsNotRefunded_shouldRollBackTheRecord() {
        due(pending(0));
        AfterSalesCaseEntity refunded = caseIn("REFUNDED", "20.00");
        Mockito.when(caseRepository.lockById(CASE_ID)).thenReturn(refunded);
        Mockito.when(transitions.fire(Mockito.eq(refunded), Mockito.any())).thenReturn(new AfterSalesTransitions.Step(
                new Decision.AlreadyDone<>(AfterSalesTrigger.REFUND_SUCCEEDED, AfterSalesStatus.REFUNDED), refunded));
        Mockito.when(gateway.refund(Mockito.any())).thenReturn(new RefundGatewayResult.Succeeded(PROVIDER_REFERENCE));

        Assertions.assertEquals(1, executor(SETTINGS).runDueRefunds());

        Mockito.verify(transactionManager).rollback(Mockito.any());
        Mockito.verify(metricsService).recordAfterSalesRefund(RefundExecutor.UNRECORDED);
    }


    /**
     * A refund at its claimed version is PROCESSING; one that is not (only a hand-written row) is not recorded on.
     */
    @Test
    void resultForARefundThatIsNotProcessing_shouldNotBeRecorded() {
        due(pending(0));
        Mockito.when(refundRepository.lockById(REFUND_ID)).thenAnswer(invocation -> {
            RefundEntity claimed = rows.get(REFUND_ID);
            return new RefundEntity(claimed.id(), claimed.caseId(), claimed.orderId(), claimed.customerId(),
                    claimed.gatewayKey(), claimed.paymentReference(), claimed.amount(), claimed.currency(),
                    "SUCCEEDED", claimed.attempts(), null, null, PROVIDER_REFERENCE, null, claimed.version(),
                    claimed.createdAt(), claimed.updatedAt(), NOW);
        });
        Mockito.when(gateway.refund(Mockito.any()))
                .thenReturn(new RefundGatewayResult.Succeeded(PROVIDER_REFERENCE))
                .thenReturn(new RefundGatewayResult.PermanentFailure("refused"));

        executor(SETTINGS).runDueRefunds();
        rows.clear();
        writes.clear();
        due(pending(0));
        executor(SETTINGS).runDueRefunds();

        Assertions.assertEquals(List.of("PROCESSING"), writes.stream().map(RefundEntity::status).toList());
        Mockito.verify(transactionManager, Mockito.times(2)).rollback(Mockito.any());
        Mockito.verify(metricsService, Mockito.times(2)).recordAfterSalesRefund(RefundExecutor.UNRECORDED);
        Mockito.verifyNoInteractions(transitions);
    }


    @Test
    void claimThatFails_shouldEndTheRunWithoutAMetric() {
        Mockito.when(refundRepository.claimNextDue(NOW))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        Assertions.assertThrows(DataAccessResourceFailureException.class, () -> executor(SETTINGS).runDueRefunds());

        Mockito.verifyNoInteractions(gateway, metricsService);
    }


    @Test
    void run_shouldStopAtTheBatchSize() {
        RefundEntity first = pending(0);
        RefundEntity second = otherRefund();
        Mockito.when(refundRepository.claimNextDue(NOW)).thenReturn(first).thenReturn(second);
        rows.put(first.id(), first);
        Mockito.when(gateway.refund(Mockito.any()))
                .thenReturn(new RefundGatewayResult.RetryableFailure("gateway unavailable"));

        Assertions.assertEquals(1, executor(new RefundExecutor.Settings(
                0, MAX_ATTEMPTS, Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofSeconds(60),
                Duration.ofSeconds(10), GATEWAY_THREADS)).runDueRefunds());

        Mockito.verify(refundRepository, Mockito.times(1)).claimNextDue(NOW);
    }


    /**
     * Within a poller tick the run stops claiming once the tick's budget is spent, but always handles one refund: a
     * slow gateway call that uses the budget up ends the run although another refund is due.
     */
    @Test
    void run_shouldStopClaimingOnceTheTickBudgetIsSpent() {
        RefundEntity first = pending(0);
        RefundEntity second = otherRefund();
        Mockito.when(refundRepository.claimNextDue(NOW)).thenReturn(first).thenReturn(second);
        rows.put(first.id(), first);
        rows.put(second.id(), second);
        AtomicBoolean spent = new AtomicBoolean();
        Mockito.when(gateway.refund(Mockito.any())).thenAnswer(invocation -> {
            spent.set(true);
            return new RefundGatewayResult.RetryableFailure("gateway slow");
        });

        Assertions.assertEquals(1, executor(SETTINGS).runDueRefundsWithin(spent::get));

        Mockito.verify(refundRepository, Mockito.times(1)).claimNextDue(NOW);
        Mockito.verify(metricsService).recordAfterSalesRefund(RefundExecutor.RETRIED);

        // A budget spent from the start still lets the first refund through.
        Mockito.clearInvocations(refundRepository);
        rows.clear();
        writes.clear();
        due(pending(0));
        Assertions.assertEquals(1, executor(SETTINGS).runDueRefundsWithin(() -> true));
        Mockito.verify(refundRepository, Mockito.times(1)).claimNextDue(NOW);
    }


    /**
     * Section 24: a call that ignores its interrupt after the timeout keeps its gateway thread. With every thread held
     * that way a run claims nothing (so the next due refund uses up no attempt) until the call returns; then the next
     * refund is claimed again.
     */
    @Test
    void callsHoldingEveryGatewayThread_shouldLeaveTheNextRefundUnclaimedUntilOneReturns() throws Exception {
        RefundEntity hanging = pending(0);
        RefundEntity next = otherRefund();
        Mockito.when(refundRepository.claimNextDue(NOW)).thenReturn(hanging).thenReturn(next).thenReturn(null);
        rows.put(hanging.id(), hanging);
        rows.put(next.id(), next);
        Mockito.when(caseRepository.findById(next.caseId())).thenReturn(Optional.of(caseFor(next)));
        Mockito.when(orderPaymentRepository.findByOrderId(next.orderId())).thenReturn(Optional.of(paymentFor(next)));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        Mockito.when(gateway.refund(Mockito.any())).thenAnswer(invocation -> {
            if (!GATEWAY_KEY.equals(invocation.<RefundGatewayRequest>getArgument(0).gatewayKey())) {
                return new RefundGatewayResult.RetryableFailure("gateway unavailable");
            }
            // Hangs past its timeout and ignores the interrupt, as a stuck socket read would.
            while (true) {
                try {
                    if (release.await(30, TimeUnit.SECONDS)) {
                        return new RefundGatewayResult.Succeeded(PROVIDER_REFERENCE);
                    }
                } catch (InterruptedException ex) {
                    interrupted.countDown();
                }
            }
        });
        RefundExecutor oneThread = executor(new RefundExecutor.Settings(
                100, MAX_ATTEMPTS, Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofSeconds(60),
                Duration.ofMillis(100), 1));

        try {
            Assertions.assertEquals(1, oneThread.runDueRefunds());
            Assertions.assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the timed-out call was not interrupted");
            Assertions.assertEquals(0, oneThread.runDueRefunds(), "the only thread is still held");
            Assertions.assertEquals(0, oneThread.freeGatewayThreads());
        } finally {
            release.countDown();
        }

        Mockito.verify(refundRepository, Mockito.times(1)).claimNextDue(NOW);
        Assertions.assertEquals("PENDING|1|2026-10-21T12:00:40|null|null|The refund gateway did not answer within "
                + "100 ms|v3|2026-10-21T12:00:30|null", summary(writes.get(1)));
        Assertions.assertEquals(next, rows.get(next.id()), "the next refund was not claimed");
        awaitFreeGatewayThreads(oneThread, 1);
        Assertions.assertEquals(1, oneThread.runDueRefunds());
        Assertions.assertEquals("PENDING", rows.get(next.id()).status());
        Assertions.assertEquals(1, rows.get(next.id()).attempts());
        Mockito.verify(metricsService, Mockito.times(2)).recordAfterSalesRefund(RefundExecutor.RETRIED);
    }


    @Test
    void run_onAnInterruptedThread_shouldClaimNothing() {
        RefundExecutor refundExecutor = executor(SETTINGS);
        Thread.currentThread().interrupt();
        try {
            Assertions.assertEquals(0, refundExecutor.runDueRefunds());
        } finally {
            Assertions.assertTrue(Thread.interrupted());
        }
        Mockito.verifyNoInteractions(refundRepository, gateway, metricsService);
    }


    /**
     * After the bean was destroyed no call is made: the claimed refund keeps its lease.
     */
    @Test
    void destroyedExecutor_shouldMakeNoMoreCalls() {
        due(pending(0));
        RefundExecutor refundExecutor = executor(SETTINGS);
        refundExecutor.destroy();

        Assertions.assertThrows(RejectedExecutionException.class, refundExecutor::runDueRefunds);

        Assertions.assertEquals(List.of("PROCESSING"), writes.stream().map(RefundEntity::status).toList());
        Mockito.verifyNoInteractions(gateway);
    }


    @Test
    void lastError_shouldBeCutAtItsMaximumLength() {
        String longError = "x".repeat(RefundExecutor.MAX_ERROR_LENGTH + 1);

        Assertions.assertEquals(RefundExecutor.MAX_ERROR_LENGTH, RefundExecutor.truncate(longError).length());
        Assertions.assertEquals("short", RefundExecutor.truncate("short"));
        Assertions.assertEquals(
                "x".repeat(RefundExecutor.MAX_ERROR_LENGTH),
                RefundExecutor.truncate("x".repeat(RefundExecutor.MAX_ERROR_LENGTH))
        );
    }


    @Test
    void requireApplied_shouldRefuseAnyOtherDecision() {
        IllegalStateException refused = Assertions.assertThrows(IllegalStateException.class, () ->
                RefundExecutor.requireApplied(new Decision.Rejected<>(RefundTrigger.GIVE_UP, RefundStatus.PENDING,
                        Decision.Reason.NOT_FROM_CURRENT_STATE, null), pending(0)));
        Assertions.assertTrue(refused.getMessage().startsWith("Refund 70 in PENDING: Rejected["), refused.getMessage());
        Assertions.assertDoesNotThrow(() -> RefundExecutor.requireApplied(
                new Decision.Apply<>(RefundTrigger.GIVE_UP, RefundStatus.PROCESSING, RefundStatus.FAILED), pending(0)));
    }


    /**
     * Section 19.10 and the other settings, checked when the bean is created (so a bad configuration fails startup).
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0|10s|10m|60s|10s|app.after-sales.refund.max-attempts must be at least 1, got 0",
            "8|0s|10m|60s|10s|app.after-sales.refund.retry-backoff must be positive, got PT0S",
            "8|10s|-1s|60s|10s|app.after-sales.refund.retry-backoff-max must be positive, got PT-1S",
            "8|10s|10m|0s|10s|app.after-sales.refund.claim-timeout must be positive, got PT0S",
            "8|10s|10m|60s|0s|app.after-sales.refund-gateway.timeout must be positive, got PT0S",
            "8|10s|5s|60s|10s|app.after-sales.refund.retry-backoff-max (PT5S) must not be shorter than "
                    + "app.after-sales.refund.retry-backoff (PT10S)",
            "8|10s|10m|60s|60s|app.after-sales.refund-gateway.timeout (PT1M) must be shorter than "
                    + "app.after-sales.refund.claim-timeout (PT1M)",
            "8|10s|10m|60s|90s|app.after-sales.refund-gateway.timeout (PT1M30S) must be shorter than "
                    + "app.after-sales.refund.claim-timeout (PT1M)"
    })
    void settings_shouldRefuseAnInvalidConfiguration(
            int maxAttempts,
            String retryBackoff,
            String retryBackoffMax,
            String claimTimeout,
            String gatewayTimeout,
            String message
    ) {
        IllegalArgumentException refused = Assertions.assertThrows(IllegalArgumentException.class, () ->
                new RefundExecutor.Settings(100, maxAttempts, duration(retryBackoff), duration(retryBackoffMax),
                        duration(claimTimeout), duration(gatewayTimeout), GATEWAY_THREADS));

        Assertions.assertEquals(message, refused.getMessage());
    }


    @Test
    void settings_shouldRefuseFewerThanOneGatewayThread() {
        for (int gatewayThreads : new int[] {0, -1}) {
            IllegalArgumentException refused = Assertions.assertThrows(IllegalArgumentException.class, () ->
                    new RefundExecutor.Settings(100, MAX_ATTEMPTS, Duration.ofSeconds(10), Duration.ofMinutes(10),
                            Duration.ofSeconds(60), Duration.ofSeconds(10), gatewayThreads));
            Assertions.assertEquals("app.after-sales.refund-gateway.max-threads must be at least 1, got "
                    + gatewayThreads, refused.getMessage());
        }
    }


    @Test
    void settings_shouldRefuseMissingDurationsAndRaiseTheBatchSizeToOne() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new RefundExecutor.Settings(
                100, 8, null, Duration.ofMinutes(10), Duration.ofSeconds(60), Duration.ofSeconds(10), GATEWAY_THREADS));
        Assertions.assertEquals(1, new RefundExecutor.Settings(
                -5, 1, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1), 1)
                .batchSize());
    }


    // ---- helpers ----


    private RefundExecutor executor(RefundExecutor.Settings settings) {
        RefundExecutor refundExecutor = new RefundExecutor(refundRepository, caseRepository, orderPaymentRepository,
                transitions, gateway, metricsService, transactionManager, settings, () -> NOW);
        executors.add(refundExecutor);
        return refundExecutor;
    }


    /**
     * Waits until a call that ignored its timeout has returned and given its thread back.
     */
    private static void awaitFreeGatewayThreads(RefundExecutor refundExecutor, int free) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (refundExecutor.freeGatewayThreads() < free) {
            Assertions.assertTrue(System.nanoTime() < deadline, "the gateway thread was never given back");
            Thread.onSpinWait();
        }
    }


    /**
     * {@code refund} is the one due refund, and the table's row.
     */
    private void due(RefundEntity refund) {
        Mockito.when(refundRepository.claimNextDue(NOW)).thenReturn(refund).thenReturn(null);
        rows.put(refund.id(), refund);
    }


    static RefundEntity pending(int attempts) {
        return new RefundEntity(REFUND_ID, CASE_ID, ORDER_ID, 3L, GATEWAY_KEY, PAYMENT_REFERENCE,
                new BigDecimal("20.00"), "USD", "PENDING", attempts, NOW.minusSeconds(5), null, null,
                attempts == 0 ? null : "earlier failure", 2L, NOW.minusHours(1), NOW.minusMinutes(1), null);
    }


    /**
     * A PROCESSING refund whose lease ends at {@code claimedUntil}.
     */
    static RefundEntity processing(int attempts, LocalDateTime claimedUntil) {
        return new RefundEntity(REFUND_ID, CASE_ID, ORDER_ID, 3L, GATEWAY_KEY, PAYMENT_REFERENCE,
                new BigDecimal("20.00"), "USD", "PROCESSING", attempts, null, claimedUntil, null, null, 2L,
                NOW.minusHours(1), NOW.minusMinutes(2), null);
    }


    private static RefundEntity otherRefund() {
        return new RefundEntity(71L, 502L, 1002L, 4L, "after-sales-case:502", "SIMPAY-other",
                new BigDecimal("8.50"), "EUR", "PENDING", 0, NOW.minusSeconds(1), null, null, null, 0L, NOW, NOW, null);
    }


    private static AfterSalesCaseEntity caseFor(RefundEntity refund) {
        return new AfterSalesCaseEntity(refund.caseId(), refund.orderId(), refund.customerId(), 7L, "ORDER_CANCELLED",
                1, "REFUNDING", null, null, refund.amount(), refund.amount(), null, refund.amount(), null,
                refund.currency(), null, null, "ORDER_CANCELLED", null, null, null, null, 0, 0L, NOW, NOW);
    }


    private static OrderPaymentEntity paymentFor(RefundEntity refund) {
        return new OrderPaymentEntity(10L, refund.orderId(), refund.customerId(), refund.amount(), refund.currency(),
                OrderPaymentEntity.CAPTURED, refund.paymentReference(), NOW.minusHours(2));
    }


    static AfterSalesCaseEntity refundingCase(String approved) {
        return caseIn("REFUNDING", approved);
    }


    static AfterSalesCaseEntity caseIn(String status, String approved) {
        return new AfterSalesCaseEntity(CASE_ID, ORDER_ID, 3L, 7L, "CUSTOMER_REQUEST", 1, status, "MISSING_ITEM",
                "The fries were missing.", new BigDecimal("25.00"), new BigDecimal("20.00"), null,
                approved == null ? null : new BigDecimal(approved), "REFUNDED".equals(status) ? new BigDecimal(approved)
                : null, "USD", "APPROVED", null, "MERCHANT_APPROVED", "CLOSED".equals(status) ? "WITHDRAWN" : null,
                null, null, null, 0, 4L, NOW.minusHours(2), NOW.minusHours(1));
    }


    static OrderPaymentEntity payment(String amount, String currency, String reference) {
        return new OrderPaymentEntity(9L, ORDER_ID, 3L, new BigDecimal(amount), currency, OrderPaymentEntity.CAPTURED,
                reference, NOW.minusHours(2));
    }


    private static AfterSalesTransitions.Step refunded(AfterSalesCaseEntity refunding) {
        return new AfterSalesTransitions.Step(new Decision.Apply<>(
                AfterSalesTrigger.REFUND_SUCCEEDED, AfterSalesStatus.REFUNDING, AfterSalesStatus.REFUNDED), refunding);
    }


    private static RefundEntity withVersion(RefundEntity refund, Long version) {
        return new RefundEntity(refund.id(), refund.caseId(), refund.orderId(), refund.customerId(),
                refund.gatewayKey(), refund.paymentReference(), refund.amount(), refund.currency(), refund.status(),
                refund.attempts(), refund.nextAttemptAt(), refund.claimedUntil(), refund.providerReference(),
                refund.lastError(), version, refund.createdAt(), refund.updatedAt(), refund.succeededAt());
    }


    /**
     * What a step writes: status|attempts|next_attempt_at|claimed_until|provider_reference|last_error|v(version the
     * write checks)|updated_at|succeeded_at.
     */
    private static String summary(RefundEntity refund) {
        return refund.status() + "|" + refund.attempts() + "|" + refund.nextAttemptAt() + "|" + refund.claimedUntil()
                + "|" + refund.providerReference() + "|" + refund.lastError() + "|v" + refund.version() + "|"
                + refund.updatedAt() + "|" + refund.succeededAt();
    }


    /**
     * A step never changes what the refund is: its case, order, customer, key, payment, amount and creation.
     */
    private static void assertSameRefund(RefundEntity written) {
        RefundEntity original = pending(0);
        Assertions.assertEquals(
                List.of(original.id(), original.caseId(), original.orderId(), original.customerId(),
                        original.gatewayKey(), original.paymentReference(), original.amount(), original.currency(),
                        original.createdAt()),
                List.of(written.id(), written.caseId(), written.orderId(), written.customerId(), written.gatewayKey(),
                        written.paymentReference(), written.amount(), written.currency(), written.createdAt())
        );
    }


    private static Duration duration(String value) {
        return Duration.parse("PT" + value.toUpperCase(java.util.Locale.ROOT));
    }
}
