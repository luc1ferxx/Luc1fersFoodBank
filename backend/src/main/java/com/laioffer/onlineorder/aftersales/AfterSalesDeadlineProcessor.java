package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.statemachine.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;


/**
 * Fires the after-sales deadlines that are due (ADR 0002 sections 6 and 19.1 to 19.3), one case per transaction:
 * <ol>
 *     <li>claim the most overdue case whose deadline is due and not backing off, with FOR UPDATE SKIP LOCKED
 *     ({@link AfterSalesCaseRepository#claimNextDue}): pollers of several instances never fire the same case, and a
 *     case a command holds is left to a later run instead of waited for;</li>
 *     <li>fire the timeout of the status the case waits in ({@link AfterSalesStateMachine#timeoutTrigger}) as SYSTEM
 *     through the single writer ({@link AfterSalesTransitions#fire}): MERCHANT_TIMEOUT and ARBITRATION_TIMEOUT refund
 *     the requested amount and OFFER_TIMEOUT the offered one, each with its refund; CUSTOMER_TIMEOUT closes the
 *     case.</li>
 * </ol>
 * The step, its history row, its refund and its event commit together, and onlineorder.aftersales.deadlines counts
 * the step once it committed. A step the after-sales machine refuses (only when the clock read after the claim went
 * back before the deadline) writes nothing, counts as skipped and ends the run, as in the refund executor: the case
 * is still due, and the claim would otherwise take it again for the rest of the batch.
 *
 * <p>Deadlines are hard for the customer's and the merchant's answers: from deadline_at on the machine refuses them
 * whether this poller ran or not (section 19.1). An answer that locks the case first is refused and the timeout fires
 * after it; a timeout that locks first leaves the answer a status conflict. Withdrawing and arbitrating have no
 * deadline: until the timeout fires, whoever locks the case first wins.
 *
 * <p>When firing fails (for example a lock timeout on the case's outbox sequence row), its transaction rolls back and
 * the case backs off: deadline_retry_at moves app.after-sales.poller.retry-backoff into the future and
 * deadline_attempts counts the failure, in a new transaction and only while the case is still at the version that was
 * claimed (section 19.3), so a case a command moved on meanwhile keeps the deadline of its new status. deadline_at
 * itself never moves. The other due cases are still fired in the same run.
 */
@Service
public final class AfterSalesDeadlineProcessor {

    static final String FIRED = "fired";

    static final String SKIPPED = "skipped";

    static final String FAILED = "failed";

    /**
     * The trigger tag of a failure on a case whose status has no timeout: the failure was exactly that.
     */
    static final String NO_TRIGGER = "none";

    private static final Logger LOGGER = LoggerFactory.getLogger(AfterSalesDeadlineProcessor.class);

    private final AfterSalesCaseRepository caseRepository;
    private final AfterSalesTransitions transitions;
    private final ApplicationMetricsService metricsService;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final Duration retryBackoff;
    private final Supplier<LocalDateTime> clock;


    @Autowired
    public AfterSalesDeadlineProcessor(
            AfterSalesCaseRepository caseRepository,
            AfterSalesTransitions transitions,
            ApplicationMetricsService metricsService,
            PlatformTransactionManager transactionManager,
            @Value("${app.after-sales.poller.batch-size:100}") int batchSize,
            @Value("${app.after-sales.poller.retry-backoff:30s}") Duration retryBackoff
    ) {
        this(caseRepository, transitions, metricsService, transactionManager, batchSize, retryBackoff,
                LocalDateTime::now);
    }


    AfterSalesDeadlineProcessor(
            AfterSalesCaseRepository caseRepository,
            AfterSalesTransitions transitions,
            ApplicationMetricsService metricsService,
            PlatformTransactionManager transactionManager,
            int batchSize,
            Duration retryBackoff,
            Supplier<LocalDateTime> clock
    ) {
        if (retryBackoff == null || retryBackoff.isNegative() || retryBackoff.isZero()) {
            // A failing case would be claimed again at once, as often as the batch allows.
            throw new IllegalArgumentException(
                    "app.after-sales.poller.retry-backoff must be positive, got " + retryBackoff);
        }
        this.caseRepository = caseRepository;
        this.transitions = transitions;
        this.metricsService = metricsService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.batchSize = Math.max(1, batchSize);
        this.retryBackoff = retryBackoff;
        this.clock = clock;
    }


    /**
     * Fires the due deadlines, most overdue first, until none is due, the batch size is reached, a step is skipped or
     * the thread is interrupted.
     *
     * @return the number of cases handled (fired, skipped, or backed off after a failure), at most the batch size
     */
    public int fireDueDeadlines() {
        return fireDueDeadlinesWithin(TickBudget.UNLIMITED);
    }


    /**
     * {@link #fireDueDeadlines()} within a poller tick: no further case is claimed once {@code budget} is spent, after
     * the first one.
     */
    int fireDueDeadlinesWithin(TickBudget budget) {
        int handled = 0;
        while (handled < batchSize && !Thread.currentThread().isInterrupted() && (handled == 0 || !budget.spent())) {
            AtomicReference<AfterSalesCaseEntity> claimed = new AtomicReference<>();
            AtomicReference<AfterSalesTrigger> timeout = new AtomicReference<>();
            Fired fired;
            try {
                fired = transactionTemplate.execute(status -> {
                    AfterSalesCaseEntity due = caseRepository.claimNextDue(clock.get());
                    if (due == null) {
                        return null;
                    }
                    claimed.set(due);
                    timeout.set(AfterSalesStateMachine.timeoutTrigger(AfterSalesStatus.valueOf(due.status())));
                    return fire(due, timeout.get());
                });
            } catch (RuntimeException ex) {
                AfterSalesCaseEntity due = claimed.get();
                if (due == null) {
                    // The claim itself failed (database unavailable): leave the rest to the next run.
                    throw ex;
                }
                backOff(due, timeout.get(), ex);
                handled++;
                continue;
            }
            if (fired == null) {
                return handled;
            }
            handled++;
            if (!record(fired)) {
                // The refused case is still due and would be claimed again at once; the next run has a later clock.
                return handled;
            }
        }
        return handled;
    }


    /**
     * Fires {@code trigger} on the claimed case as SYSTEM; the single writer reads the time of the step after the
     * claim's lock.
     */
    private Fired fire(AfterSalesCaseEntity due, AfterSalesTrigger trigger) {
        AfterSalesTransitions.Step step = transitions.fire(due, new AfterSalesTransitions.Command(
                trigger, AfterSalesActor.SYSTEM, null, null, null, null));
        return new Fired(due, trigger, step);
    }


    /**
     * Counts and logs a step that committed or was refused.
     *
     * @return whether the step was applied; false when it was refused (skipped)
     */
    private boolean record(Fired fired) {
        AfterSalesCaseEntity due = fired.due();
        if (fired.step().decision() instanceof Decision.Apply<AfterSalesStatus, AfterSalesTrigger> applied) {
            metricsService.recordAfterSalesDeadline(fired.trigger().name(), FIRED);
            AfterSalesCaseEntity moved = fired.step().current();
            LOGGER.info(
                    "After-sales deadline fired: case_id={}, order_id={}, trigger={}, deadline_at={}, status={}->{}, "
                            + "approved_amount={}, currency={}",
                    due.id(),
                    due.orderId(),
                    fired.trigger(),
                    due.deadlineAt(),
                    applied.from(),
                    applied.to(),
                    moved.approvedAmount(),
                    moved.currency()
            );
            return true;
        }
        metricsService.recordAfterSalesDeadline(fired.trigger().name(), SKIPPED);
        LOGGER.warn(
                "After-sales deadline skipped: case_id={}, order_id={}, trigger={}, deadline_at={}, decision={}",
                due.id(),
                due.orderId(),
                fired.trigger(),
                due.deadlineAt(),
                fired.step().decision()
        );
        return false;
    }


    /**
     * Backs off a case whose deadline failed to fire, in a transaction of its own: the claim's transaction rolled back.
     * Only while the case is still at the version that was claimed; a command that moved it on meanwhile set the
     * deadline of its new status, which this retry must not touch.
     *
     * @param trigger the timeout that failed; null when the case's status has none
     */
    private void backOff(AfterSalesCaseEntity due, AfterSalesTrigger trigger, RuntimeException failure) {
        String triggerName = trigger == null ? NO_TRIGGER : trigger.name();
        int attempts = due.deadlineAttempts() + 1;
        LocalDateTime retryAt = clock.get().plus(retryBackoff);
        LOGGER.warn(
                "After-sales deadline {} of case {} (order {}) failed (attempt {}); retrying at {}",
                triggerName,
                due.id(),
                due.orderId(),
                attempts,
                retryAt,
                failure
        );
        try {
            Integer rows = transactionTemplate.execute(status ->
                    caseRepository.rescheduleDeadline(due.id(), due.version(), retryAt, attempts));
            if (!Objects.equals(rows, 1)) {
                LOGGER.info(
                        "After-sales case {} moved on from version {} before its failed deadline backed off; its new "
                                + "status keeps its own deadline",
                        due.id(),
                        due.version()
                );
            }
        } finally {
            metricsService.recordAfterSalesDeadline(triggerName, FAILED);
        }
    }


    /**
     * @param due     the case as claimed
     * @param trigger the timeout fired on it
     * @param step    what the single writer did
     */
    private record Fired(AfterSalesCaseEntity due, AfterSalesTrigger trigger, AfterSalesTransitions.Step step) {
    }
}
