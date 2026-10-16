package com.laioffer.onlineorder.fulfilment.timeout;


import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;


/**
 * Fires due order deadlines, one deadline per transaction:
 * <ol>
 *     <li>claim the most overdue deadline with FOR UPDATE SKIP LOCKED, so pollers on several instances never fire
 *     the same deadline and never wait on each other;</li>
 *     <li>cancel the order through the order module's guarded transition, only while the order is still in the
 *     status the deadline waits in (a PAID order for ACCEPT, a PLACED order for PAYMENT): an order that was accepted,
 *     paid or cancelled in the meantime is left alone;</li>
 *     <li>delete the deadline.</li>
 * </ol>
 * An ACCEPT deadline that is due while app.fulfilment.accept-timeout-enabled is off (it was switched off after the
 * deadline was set) is dropped without cancelling the order.
 *
 * <p>The cancel, its order.cancelled outbox event and the deadline delete commit together. When firing fails (for
 * example a lock timeout on the order row), the deadline is moved app.fulfilment.deadline-poller.retry-backoff into
 * the future and retried then; the other due deadlines are still fired in the same run.
 */
@Service
public class OrderDeadlineProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderDeadlineProcessor.class);

    private final OrderDeadlineRepository orderDeadlineRepository;
    private final OrderLifecycleCommands orderLifecycleCommands;
    private final ApplicationMetricsService metricsService;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final Duration retryBackoff;
    private final boolean acceptTimeoutEnabled;


    public OrderDeadlineProcessor(
            OrderDeadlineRepository orderDeadlineRepository,
            OrderLifecycleCommands orderLifecycleCommands,
            ApplicationMetricsService metricsService,
            PlatformTransactionManager transactionManager,
            @Value("${app.fulfilment.deadline-poller.batch-size:100}") int batchSize,
            @Value("${app.fulfilment.deadline-poller.retry-backoff:30s}") Duration retryBackoff,
            @Value("${app.fulfilment.accept-timeout-enabled:false}") boolean acceptTimeoutEnabled
    ) {
        this.orderDeadlineRepository = orderDeadlineRepository;
        this.orderLifecycleCommands = orderLifecycleCommands;
        this.metricsService = metricsService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.batchSize = Math.max(1, batchSize);
        this.retryBackoff = retryBackoff;
        this.acceptTimeoutEnabled = acceptTimeoutEnabled;
    }


    /**
     * @return the number of deadlines handled (fired or rescheduled after a failure), at most the batch size
     */
    public int fireDueDeadlines() {
        int handled = 0;
        while (handled < batchSize && !Thread.currentThread().isInterrupted()) {
            AtomicReference<OrderDeadlineEntity> claimed = new AtomicReference<>();
            FiredDeadline fired;
            try {
                fired = transactionTemplate.execute(status -> {
                    OrderDeadlineEntity deadline = orderDeadlineRepository.claimNextDue(LocalDateTime.now());
                    if (deadline == null) {
                        return null;
                    }
                    claimed.set(deadline);
                    return fire(deadline);
                });
            } catch (RuntimeException ex) {
                OrderDeadlineEntity deadline = claimed.get();
                if (deadline == null) {
                    // The claim itself failed (database unavailable): leave the rest to the next run.
                    throw ex;
                }
                reschedule(deadline, ex);
                handled++;
                continue;
            }
            if (fired == null) {
                return handled;
            }
            record(fired);
            handled++;
        }
        return handled;
    }


    private FiredDeadline fire(OrderDeadlineEntity deadline) {
        DeadlineType type = DeadlineType.valueOf(deadline.deadlineType());
        boolean enabled = type != DeadlineType.ACCEPT || acceptTimeoutEnabled;
        boolean cancelled = enabled && orderLifecycleCommands.cancelIfStillIn(
                deadline.orderId(),
                type.waitingStatus(),
                type.cancelReason()
        ).isPresent();
        orderDeadlineRepository.deleteById(deadline.id());
        return new FiredDeadline(deadline, type, cancelled);
    }


    private void record(FiredDeadline fired) {
        metricsService.recordFulfilmentTimeout(fired.type().name(), fired.cancelled() ? "cancelled" : "skipped");
        if (fired.cancelled() && fired.type() == DeadlineType.ACCEPT) {
            metricsService.recordMerchantDecision("timeout");
        }
        if (fired.cancelled()) {
            LOGGER.info(
                    "Order deadline fired: order_id={}, deadline={}, due_at={}, cancel_reason={}",
                    fired.deadline().orderId(),
                    fired.type(),
                    fired.deadline().dueAt(),
                    fired.type().cancelReason()
            );
        }
    }


    private void reschedule(OrderDeadlineEntity deadline, RuntimeException failure) {
        LocalDateTime now = LocalDateTime.now();
        String error = failure.toString();
        LOGGER.warn(
                "Order deadline {} for order {} failed (attempt {}); retrying in {}",
                deadline.deadlineType(),
                deadline.orderId(),
                (deadline.attempts() == null ? 0 : deadline.attempts()) + 1,
                retryBackoff,
                failure
        );
        transactionTemplate.executeWithoutResult(status -> orderDeadlineRepository.reschedule(
                deadline.id(),
                now.plus(retryBackoff),
                error.length() > 1000 ? error.substring(0, 1000) : error,
                now
        ));
    }


    private record FiredDeadline(OrderDeadlineEntity deadline, DeadlineType type, boolean cancelled) {
    }
}
