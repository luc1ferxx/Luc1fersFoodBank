package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.entity.OrderPaymentEntity;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.OrderPaymentRepository;
import com.laioffer.onlineorder.statemachine.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;


/**
 * Executes the refunds of the cases in REFUNDING (ADR 0002 D8, sections 5.4 and 9, with 19.4, 19.9 and 19.10), one
 * refund at a time and each in three parts:
 * <ol>
 *     <li>The claim transaction takes the oldest due refund with FOR UPDATE SKIP LOCKED
 *     ({@link RefundRepository#claimNextDue}): a PENDING refund whose next attempt is due (CLAIM), or a PROCESSING one
 *     whose lease expired because its executor died or stalled (RECLAIM). Either counts an attempt and leases the
 *     refund for app.after-sales.refund.claim-timeout. Before the gateway is called the refund is checked against its
 *     case and the order's payment (19.4): its case is in REFUNDING, its amount is the case's approved amount, and the
 *     payment it names captured that much at least. A refund that fails the check, or whose lease expired with no
 *     attempt left (19.9), is given up (FAILED) in this transaction instead of being called.</li>
 *     <li>The gateway is called outside any transaction and waited for at most app.after-sales.refund-gateway.timeout,
 *     which must be shorter than the lease (19.10, checked at startup), so another executor does not reclaim a refund
 *     whose call may still succeed. A timeout, an exception and no answer count as retryable failures.</li>
 *     <li>The record transaction locks the refund. A refund whose version moved since the claim was reclaimed by
 *     another executor after this lease expired; this result is discarded (superseded), and the gateway, idempotent per
 *     key, gives the other attempt the same answer. Otherwise a success marks the refund SUCCEEDED and then, under the
 *     case's row lock, takes REFUND_SUCCEEDED through the single writer: the case becomes REFUNDED with its
 *     refunded_amount, its history row and its aftersales.case_refunded event. A retryable failure moves the refund
 *     back to PENDING after an exponential backoff, while attempts are left; a permanent failure, or one with no
 *     attempt left, gives it up (FAILED) with its error, for an admin to retry. A FAILED refund leaves its case in
 *     REFUNDING.</li>
 * </ol>
 * Lock order of the record transaction: the refund, the case, the case's outbox sequence row. Every other writer that
 * holds a case row only inserts a new refund and never waits for an existing one, so no cycle forms.
 *
 * <p>A result that cannot be recorded (its transaction failed) leaves the refund PROCESSING until its lease expires,
 * when it is reclaimed and the gateway called again with the same key. Every refund handled counts once in
 * onlineorder.aftersales.refunds, after its transaction committed.
 */
@Service
public final class RefundExecutor implements DisposableBean {

    static final String SUCCEEDED = "succeeded";

    static final String RETRIED = "retried";

    static final String FAILED = "failed";

    static final String SUPERSEDED = "superseded";

    static final String UNRECORDED = "unrecorded";

    static final String SKIPPED = "skipped";

    /**
     * The longest last_error kept (section 9).
     */
    static final int MAX_ERROR_LENGTH = 1000;

    static final String GATEWAY_THREAD_PREFIX = "refund-gateway-";

    private static final Logger LOGGER = LoggerFactory.getLogger(RefundExecutor.class);

    private final RefundRepository refundRepository;
    private final AfterSalesCaseRepository caseRepository;
    private final OrderPaymentRepository orderPaymentRepository;
    private final AfterSalesTransitions transitions;
    private final RefundGateway gateway;
    private final ApplicationMetricsService metricsService;
    private final TransactionTemplate transactionTemplate;
    private final Settings settings;
    private final Supplier<LocalDateTime> clock;
    private final ExecutorService gatewayCalls;


    @Autowired
    public RefundExecutor(
            RefundRepository refundRepository,
            AfterSalesCaseRepository caseRepository,
            OrderPaymentRepository orderPaymentRepository,
            AfterSalesTransitions transitions,
            RefundGateway gateway,
            ApplicationMetricsService metricsService,
            PlatformTransactionManager transactionManager,
            @Value("${app.after-sales.poller.batch-size:100}") int batchSize,
            @Value("${app.after-sales.refund.max-attempts:8}") int maxAttempts,
            @Value("${app.after-sales.refund.retry-backoff:10s}") Duration retryBackoff,
            @Value("${app.after-sales.refund.retry-backoff-max:10m}") Duration retryBackoffMax,
            @Value("${app.after-sales.refund.claim-timeout:60s}") Duration claimTimeout,
            @Value("${app.after-sales.refund-gateway.timeout:10s}") Duration gatewayTimeout
    ) {
        this(refundRepository, caseRepository, orderPaymentRepository, transitions, gateway, metricsService,
                transactionManager,
                new Settings(batchSize, maxAttempts, retryBackoff, retryBackoffMax, claimTimeout, gatewayTimeout),
                LocalDateTime::now);
    }


    RefundExecutor(
            RefundRepository refundRepository,
            AfterSalesCaseRepository caseRepository,
            OrderPaymentRepository orderPaymentRepository,
            AfterSalesTransitions transitions,
            RefundGateway gateway,
            ApplicationMetricsService metricsService,
            PlatformTransactionManager transactionManager,
            Settings settings,
            Supplier<LocalDateTime> clock
    ) {
        this.refundRepository = refundRepository;
        this.caseRepository = caseRepository;
        this.orderPaymentRepository = orderPaymentRepository;
        this.transitions = transitions;
        this.gateway = gateway;
        this.metricsService = metricsService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = clock;
        AtomicInteger threads = new AtomicInteger();
        // A thread per call in flight: a call that ignores the interrupt after its timeout keeps its own thread, not
        // the next refund's. The poller makes one call at a time, so this holds one thread unless a gateway hangs.
        this.gatewayCalls = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, GATEWAY_THREAD_PREFIX + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }


    /**
     * Executes the due refunds, oldest first, until none is due, the batch size is reached or the thread is
     * interrupted. A claim that fails ends the run with its exception; a result that cannot be recorded does not.
     *
     * @return the number of refunds claimed, whatever their outcome, at most the batch size
     */
    public int runDueRefunds() {
        int handled = 0;
        while (handled < settings.batchSize() && !Thread.currentThread().isInterrupted()) {
            Claim claim = transactionTemplate.execute(status -> claimNextDue());
            if (claim == null) {
                return handled;
            }
            handled++;
            if (claim.outcome() != null) {
                metricsService.recordAfterSalesRefund(claim.outcome());
                if (SKIPPED.equals(claim.outcome())) {
                    // Claimed again at once otherwise; the next run has a later clock.
                    return handled;
                }
                continue;
            }
            RefundEntity claimed = claim.refund();
            RefundGatewayResult result;
            try {
                result = call(claimed);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                LOGGER.info("Refund {} stopped waiting for the gateway on attempt {}; it is reclaimed after its lease "
                        + "ends at {}", claimed.id(), claimed.attempts(), claimed.claimedUntil());
                metricsService.recordAfterSalesRefund(UNRECORDED);
                return handled;
            }
            String outcome;
            try {
                outcome = transactionTemplate.execute(status -> record(claimed, result));
            } catch (RuntimeException ex) {
                LOGGER.warn("The result {} of refund {} (case {}, attempt {}) could not be recorded; it is reclaimed "
                                + "after its lease ends at {}",
                        result, claimed.id(), claimed.caseId(), claimed.attempts(), claimed.claimedUntil(), ex);
                outcome = UNRECORDED;
            }
            metricsService.recordAfterSalesRefund(outcome);
        }
        return handled;
    }


    @Override
    public void destroy() {
        gatewayCalls.shutdownNow();
    }


    Settings settings() {
        return settings;
    }


    /**
     * The claim transaction: claims the next due refund and either leases it for a gateway call or, when it must not
     * be called, finishes it here.
     *
     * @return null when no refund is due
     */
    private Claim claimNextDue() {
        // The claim skips locked rows instead of waiting, so the time read before it is the time of its lock; the
        // query and the guards see the same lease.
        LocalDateTime now = clock.get();
        RefundEntity due = refundRepository.claimNextDue(now);
        if (due == null) {
            return null;
        }
        RefundStatus status = RefundStatus.valueOf(due.status());
        RefundTrigger trigger = status == RefundStatus.PROCESSING ? RefundTrigger.RECLAIM : RefundTrigger.CLAIM;
        Decision<RefundStatus, RefundTrigger> decision = decide(status, trigger, due, now);
        if (decision instanceof Decision.Rejected<RefundStatus, RefundTrigger> rejected
                && RefundStateMachine.ATTEMPTS_LEFT.equals(rejected.guard())) {
            // Section 19.9: the last attempt's executor died or stalled; its call may have refunded, which an admin
            // retry finds out (the gateway answers the same key with the same refund).
            giveUp(due, status, "The lease of attempt " + due.attempts() + " expired without a result and no "
                    + "attempt is left", due.attempts(), now);
            return new Claim(null, FAILED);
        }
        if (!(decision instanceof Decision.Apply<RefundStatus, RefundTrigger>)) {
            LOGGER.warn("Refund {} was claimed in {} but {} was not applied: {}", due.id(), status, trigger, decision);
            return new Claim(null, SKIPPED);
        }
        int attempts = due.attempts() + 1;
        String mismatch = mismatch(due);
        if (mismatch != null) {
            // Given up after the claim, without calling the gateway: CLAIM or RECLAIM, then GIVE_UP from PROCESSING.
            giveUp(due, RefundStatus.PROCESSING, mismatch, attempts, now);
            return new Claim(null, FAILED);
        }
        RefundEntity claimed = refundRepository.save(moved(due, RefundStatus.PROCESSING, attempts, null,
                now.plus(settings.claimTimeout()), null, due.lastError(), null, now));
        return new Claim(claimed, null);
    }


    /**
     * Section 19.4, before any money moves: the refund's case is in REFUNDING, the refund is of exactly the case's
     * approved amount, and the payment it names captured at least that much in the same currency. The composite key of
     * refunds and the opening rules already ensure it; this is the last check before the gateway.
     *
     * @return why the refund must not be paid, or null when it matches
     */
    private String mismatch(RefundEntity refund) {
        Money amount = Money.of(refund.amount(), refund.currency());
        AfterSalesCaseEntity refundCase = caseRepository.findById(refund.caseId())
                .orElseThrow(() -> new IllegalStateException("Refund " + refund.id() + " has no case"));
        if (!AfterSalesStatus.REFUNDING.name().equals(refundCase.status())) {
            return "Case " + refundCase.id() + " is " + refundCase.status() + ", not REFUNDING";
        }
        if (refundCase.approvedAmount() == null
                || !amount.equals(Money.of(refundCase.approvedAmount(), refundCase.currency()))) {
            return "The refund of " + amount + " is not the approved amount " + refundCase.approvedAmount() + " "
                    + refundCase.currency() + " of case " + refundCase.id();
        }
        OrderPaymentEntity payment = orderPaymentRepository.findByOrderId(refund.orderId()).orElse(null);
        if (payment == null) {
            return "Order " + refund.orderId() + " has no captured payment";
        }
        if (!payment.providerReference().equals(refund.paymentReference())) {
            return "The refund names the payment " + refund.paymentReference() + " but order " + refund.orderId()
                    + " was paid by " + payment.providerReference();
        }
        Money captured = payment.captured();
        if (!captured.currency().equals(amount.currency()) || amount.isGreaterThan(captured)) {
            return "The refund of " + amount + " exceeds the " + captured + " captured by payment "
                    + payment.providerReference();
        }
        return null;
    }


    /**
     * The gateway call, outside any transaction, waited for at most the gateway timeout.
     *
     * @throws InterruptedException when the thread was interrupted while waiting; the call is cancelled
     */
    private RefundGatewayResult call(RefundEntity claimed) throws InterruptedException {
        RefundGatewayRequest request = new RefundGatewayRequest(
                claimed.gatewayKey(),
                claimed.orderId(),
                Money.of(claimed.amount(), claimed.currency()),
                claimed.attempts(),
                claimed.paymentReference()
        );
        Future<RefundGatewayResult> call = gatewayCalls.submit(() -> gateway.refund(request));
        try {
            RefundGatewayResult result = call.get(settings.gatewayTimeout().toNanos(), TimeUnit.NANOSECONDS);
            return result == null
                    ? new RefundGatewayResult.RetryableFailure("The refund gateway answered nothing")
                    : result;
        } catch (TimeoutException ex) {
            call.cancel(true);
            return new RefundGatewayResult.RetryableFailure(
                    "The refund gateway did not answer within " + settings.gatewayTimeout().toMillis() + " ms");
        } catch (ExecutionException ex) {
            Throwable failure = ex.getCause();
            if (failure instanceof Error error) {
                // Not a gateway answer: the refund stays leased and is reclaimed once the lease ends.
                throw error;
            }
            return new RefundGatewayResult.RetryableFailure(String.valueOf(failure));
        } catch (InterruptedException ex) {
            call.cancel(true);
            throw ex;
        }
    }


    /**
     * The record transaction: records the gateway's answer on the refund still at its claimed version, and on a
     * success marks its case refunded.
     */
    private String record(RefundEntity claimed, RefundGatewayResult result) {
        RefundEntity locked = refundRepository.lockById(claimed.id());
        if (locked == null || !claimed.version().equals(locked.version())) {
            LOGGER.info("Refund {} (case {}) moved on from version {} after the lease of attempt {} expired; the "
                    + "result {} is discarded", claimed.id(), claimed.caseId(), claimed.version(), claimed.attempts(),
                    result);
            return SUPERSEDED;
        }
        LocalDateTime now = clock.get();
        RefundStatus status = RefundStatus.valueOf(locked.status());
        if (result instanceof RefundGatewayResult.Succeeded succeeded) {
            requireApplied(decide(status, RefundTrigger.SUCCEED, locked, now), locked);
            refundRepository.save(moved(locked, RefundStatus.SUCCEEDED, locked.attempts(), null, null,
                    succeeded.providerReference(), null, now, now));
            AfterSalesTransitions.Step step = transitions.fire(
                    caseRepository.lockById(locked.caseId()),
                    new AfterSalesTransitions.Command(
                            AfterSalesTrigger.REFUND_SUCCEEDED, AfterSalesActor.SYSTEM, null, null, null, null)
            );
            if (!step.applied()) {
                throw new IllegalStateException("Case " + locked.caseId() + " of refund " + locked.id()
                        + " was not refunded: " + step.decision());
            }
            LOGGER.info("Refund {} of case {} (order {}) succeeded on attempt {}: {} {}, provider reference {}",
                    locked.id(), locked.caseId(), locked.orderId(), locked.attempts(), locked.amount(),
                    locked.currency(), succeeded.providerReference());
            return SUCCEEDED;
        }
        if (result instanceof RefundGatewayResult.RetryableFailure retryable
                && decide(status, RefundTrigger.RETRY_LATER, locked, now)
                instanceof Decision.Apply<RefundStatus, RefundTrigger>) {
            LocalDateTime nextAttemptAt = now.plus(backoff(locked.attempts()));
            refundRepository.save(moved(locked, RefundStatus.PENDING, locked.attempts(), nextAttemptAt, null, null,
                    truncate(retryable.error()), null, now));
            LOGGER.warn("Refund {} of case {} failed on attempt {} of {}: {}; retrying at {}", locked.id(),
                    locked.caseId(), locked.attempts(), settings.maxAttempts(), retryable.error(), nextAttemptAt);
            return RETRIED;
        }
        // A permanent failure, or a retryable one with the attempts used up.
        giveUp(locked, status, failure(result), locked.attempts(), now);
        return FAILED;
    }


    /**
     * GIVE_UP: the refund, PROCESSING in {@code from}, becomes FAILED with {@code error}, for an admin to retry; its
     * case stays in REFUNDING.
     *
     * @param from     the refund's status as the step finds it: PROCESSING, after this claim or an earlier one
     * @param attempts the attempts made, this claim's included
     */
    private void giveUp(RefundEntity processing, RefundStatus from, String error, int attempts, LocalDateTime now) {
        requireApplied(decide(from, RefundTrigger.GIVE_UP, processing, now), processing);
        refundRepository.save(moved(processing, RefundStatus.FAILED, attempts, null, null, null, truncate(error), null,
                now));
        LOGGER.error("Refund {} of case {} (order {}, {} {}) gave up after {} attempt(s): {}", processing.id(),
                processing.caseId(), processing.orderId(), processing.amount(), processing.currency(), attempts, error);
    }


    private Decision<RefundStatus, RefundTrigger> decide(
            RefundStatus status,
            RefundTrigger trigger,
            RefundEntity current,
            LocalDateTime now
    ) {
        return RefundStateMachine.DEFINITION.decide(status, trigger, AfterSalesActor.SYSTEM,
                new RefundTransitionContext(current, now, settings.maxAttempts()));
    }


    /**
     * The backoff after the failure of attempt {@code attempts}: retry-backoff x 2^(attempts - 1), at most
     * retry-backoff-max.
     */
    Duration backoff(int attempts) {
        Duration backoff = settings.retryBackoff();
        for (int made = 1; made < attempts && backoff.compareTo(settings.retryBackoffMax()) < 0; made++) {
            backoff = backoff.multipliedBy(2);
        }
        return backoff.compareTo(settings.retryBackoffMax()) < 0 ? backoff : settings.retryBackoffMax();
    }


    static void requireApplied(Decision<RefundStatus, RefundTrigger> decision, RefundEntity refund) {
        if (!(decision instanceof Decision.Apply<RefundStatus, RefundTrigger>)) {
            throw new IllegalStateException("Refund " + refund.id() + " in " + refund.status() + ": " + decision);
        }
    }


    private static String failure(RefundGatewayResult result) {
        if (result instanceof RefundGatewayResult.RetryableFailure retryable) {
            return retryable.error();
        }
        return ((RefundGatewayResult.PermanentFailure) result).error();
    }


    static String truncate(String error) {
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }


    /**
     * The refund after a step at {@code now}: a PENDING refund has its next attempt, a PROCESSING one its lease, and
     * neither is kept in another status.
     */
    private static RefundEntity moved(
            RefundEntity current,
            RefundStatus status,
            int attempts,
            LocalDateTime nextAttemptAt,
            LocalDateTime claimedUntil,
            String providerReference,
            String lastError,
            LocalDateTime succeededAt,
            LocalDateTime now
    ) {
        return new RefundEntity(
                current.id(),
                current.caseId(),
                current.orderId(),
                current.customerId(),
                current.gatewayKey(),
                current.paymentReference(),
                current.amount(),
                current.currency(),
                status.name(),
                attempts,
                nextAttemptAt,
                claimedUntil,
                providerReference,
                lastError,
                current.version(),
                current.createdAt(),
                now,
                succeededAt
        );
    }


    /**
     * The executor's settings (app.after-sales.refund.*, app.after-sales.refund-gateway.timeout and the poller's
     * batch size), checked at startup.
     *
     * @param batchSize       refunds handled per run at most; at least 1
     * @param maxAttempts     gateway calls a refund may make, reclaims included, before it is given up
     * @param retryBackoff    the wait after the first failed attempt; it doubles with every further one
     * @param retryBackoffMax the longest wait between attempts
     * @param claimTimeout    the lease of a claim: after it another executor may reclaim the refund
     * @param gatewayTimeout  how long a gateway call is waited for; shorter than the lease (section 19.10)
     */
    record Settings(
            int batchSize,
            int maxAttempts,
            Duration retryBackoff,
            Duration retryBackoffMax,
            Duration claimTimeout,
            Duration gatewayTimeout
    ) {

        Settings {
            batchSize = Math.max(1, batchSize);
            if (maxAttempts < 1) {
                throw new IllegalArgumentException(
                        "app.after-sales.refund.max-attempts must be at least 1, got " + maxAttempts);
            }
            requirePositive(retryBackoff, "app.after-sales.refund.retry-backoff");
            requirePositive(retryBackoffMax, "app.after-sales.refund.retry-backoff-max");
            requirePositive(claimTimeout, "app.after-sales.refund.claim-timeout");
            requirePositive(gatewayTimeout, "app.after-sales.refund-gateway.timeout");
            if (retryBackoffMax.compareTo(retryBackoff) < 0) {
                throw new IllegalArgumentException("app.after-sales.refund.retry-backoff-max (" + retryBackoffMax
                        + ") must not be shorter than app.after-sales.refund.retry-backoff (" + retryBackoff + ")");
            }
            if (gatewayTimeout.compareTo(claimTimeout) >= 0) {
                // The lease would end while the call may still succeed, and another executor would call again.
                throw new IllegalArgumentException("app.after-sales.refund-gateway.timeout (" + gatewayTimeout
                        + ") must be shorter than app.after-sales.refund.claim-timeout (" + claimTimeout + ")");
            }
        }


        private static void requirePositive(Duration duration, String property) {
            if (duration == null || duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException(property + " must be positive, got " + duration);
            }
        }
    }


    /**
     * What the claim transaction left to do.
     *
     * @param refund  the claimed refund, PROCESSING with its lease, to call the gateway for; null when the claim
     *                transaction finished the refund
     * @param outcome the metric outcome of a refund the claim transaction finished (given up or skipped); null when
     *                there is a call to make
     */
    private record Claim(RefundEntity refund, String outcome) {
    }
}
