package com.laioffer.onlineorder.aftersales;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;


import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;


/**
 * Runs the after-sales background jobs every app.after-sales.poller.interval on a thread of its own
 * ("after-sales-poller"), not on the shared Spring scheduler (ADR 0002 D13 and section 9): the deadlines
 * ({@link AfterSalesDeadlineProcessor}), then the refunds ({@link RefundExecutor}).
 *
 * <p>The shared scheduler (spring.task.scheduling.pool.size) runs the outbox publisher every 500 ms. A burst of due
 * cases, cases stuck behind row locks, or a slow refund gateway would otherwise hold its threads and delay every event,
 * the after-sales events this poller writes included. With its own single thread the poller can only delay itself. It
 * is not a TaskScheduler bean on purpose: a second TaskScheduler bean would make Spring Boot back off its
 * auto-configured scheduler for every @Scheduled job.
 *
 * <p>Each tick runs the jobs one after the other, each failing on its own: a failed job is logged and the next one
 * still runs. The jobs share the tick's time budget (app.after-sales.poller.tick-budget, {@link TickBudget}): each
 * stops claiming once it is spent, after one item at least, so that a slow or hung refund gateway delays the next
 * tick's deadlines by about the budget plus one gateway timeout at most. On by default wherever Kafka is
 * (app.after-sales.poller.enabled follows app.kafka.enabled in application.yml); every instance runs one, since claims
 * use SKIP LOCKED.
 */
@Component
@ConditionalOnProperty(name = "app.after-sales.poller.enabled", havingValue = "true", matchIfMissing = true)
public final class AfterSalesPoller implements SmartLifecycle {

    static final String THREAD_NAME = "after-sales-poller";

    private static final Logger LOGGER = LoggerFactory.getLogger(AfterSalesPoller.class);

    private final List<Job> jobs;
    private final Duration interval;
    private final Duration tickBudget;
    private final LongSupplier nanoTime;
    private ScheduledExecutorService executor;


    @Autowired
    public AfterSalesPoller(
            AfterSalesDeadlineProcessor deadlineProcessor,
            RefundExecutor refundExecutor,
            @Value("${app.after-sales.poller.interval:1s}") Duration interval,
            @Value("${app.after-sales.poller.tick-budget:5s}") Duration tickBudget
    ) {
        this(deadlineProcessor, refundExecutor, interval, tickBudget, System::nanoTime);
    }


    AfterSalesPoller(
            AfterSalesDeadlineProcessor deadlineProcessor,
            RefundExecutor refundExecutor,
            Duration interval,
            Duration tickBudget,
            LongSupplier nanoTime
    ) {
        if (tickBudget == null || tickBudget.isNegative() || tickBudget.isZero()) {
            // A budget of nothing would cut every job to one item per tick.
            throw new IllegalArgumentException(
                    "app.after-sales.poller.tick-budget must be positive, got " + tickBudget);
        }
        // The jobs of a tick, in order: the refunds come after the deadlines, so that a refund a timeout created is
        // executed in the same tick.
        this.jobs = List.of(
                new Job("deadline", deadlineProcessor::fireDueDeadlinesWithin),
                new Job("refund", refundExecutor::runDueRefundsWithin)
        );
        this.interval = interval;
        this.tickBudget = tickBudget;
        this.nanoTime = nanoTime;
    }


    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = Math.max(1L, interval.toMillis());
        executor.scheduleWithFixedDelay(this::poll, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }


    /**
     * One tick: every job in turn, within one time budget from the start of the tick. Never throws: an exception would
     * cancel all later runs of a ScheduledExecutorService task.
     */
    void poll() {
        TickBudget budget = TickBudget.startingNow(tickBudget, nanoTime);
        for (Job job : jobs) {
            job.run(budget, interval);
        }
    }


    Duration tickBudget() {
        return tickBudget;
    }


    @Override
    public synchronized void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                LOGGER.warn("After-sales poller did not stop within 10s");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            executor = null;
        }
    }


    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }


    /**
     * A job of the poller, with its own failure streak. During an outage only the first failure of a streak is logged
     * with its stack trace.
     */
    private static final class Job {

        private final String name;
        private final Consumer<TickBudget> work;
        // Only touched by the single poller thread.
        private int consecutiveFailures;


        Job(String name, Consumer<TickBudget> work) {
            this.name = name;
            this.work = work;
        }


        void run(TickBudget budget, Duration interval) {
            try {
                work.accept(budget);
                if (consecutiveFailures > 0) {
                    LOGGER.info("After-sales {} poll resumed after {} failed poll(s)", name, consecutiveFailures);
                    consecutiveFailures = 0;
                }
            } catch (Throwable ex) {
                consecutiveFailures++;
                if (consecutiveFailures == 1) {
                    LOGGER.warn("After-sales {} poll failed; retrying every {}", name, interval, ex);
                } else {
                    LOGGER.warn("After-sales {} poll failed {} times in a row: {}", name, consecutiveFailures,
                            ex.toString());
                }
            }
        }
    }
}
