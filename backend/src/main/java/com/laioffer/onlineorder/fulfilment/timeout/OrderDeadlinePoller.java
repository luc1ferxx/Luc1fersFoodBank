package com.laioffer.onlineorder.fulfilment.timeout;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;


import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


/**
 * Runs {@link OrderDeadlineProcessor} every app.fulfilment.deadline-poller.interval on a thread of its own
 * ("fulfilment-deadline-poller"), not on the shared Spring scheduler.
 *
 * <p>The shared scheduler (spring.task.scheduling.pool.size, 4 threads) runs the outbox publisher every 500 ms and
 * the hourly cleanup. A burst of due deadlines, or deadlines stuck behind order row locks, would otherwise hold
 * scheduler threads while the outbox publisher waits, and every order event (including the order.cancelled events
 * this poller writes) would be delayed. With its own single thread the poller can only delay itself. It is not a
 * TaskScheduler bean on purpose: a second TaskScheduler bean would make Spring Boot back off its auto-configured
 * scheduler for every @Scheduled job.
 *
 * <p>Enabled by default wherever Kafka is (deadlines are only written by the timeout consumer); disable with
 * app.fulfilment.deadline-poller.enabled=false. Every instance runs one: claims use SKIP LOCKED.
 */
@Component
@ConditionalOnProperty(name = "app.fulfilment.deadline-poller.enabled", havingValue = "true", matchIfMissing = true)
public class OrderDeadlinePoller implements SmartLifecycle {

    static final String THREAD_NAME = "fulfilment-deadline-poller";

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderDeadlinePoller.class);

    private final OrderDeadlineProcessor orderDeadlineProcessor;
    private final Duration interval;
    private ScheduledExecutorService executor;
    // Only touched by the single poller thread.
    private int consecutiveFailures;


    public OrderDeadlinePoller(
            OrderDeadlineProcessor orderDeadlineProcessor,
            @Value("${app.fulfilment.deadline-poller.interval:1s}") Duration interval
    ) {
        this.orderDeadlineProcessor = orderDeadlineProcessor;
        this.interval = interval;
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
     * Never throws: an exception would cancel all later runs of a ScheduledExecutorService task. During an outage
     * only the first failure of a streak is logged with its stack trace.
     */
    void poll() {
        try {
            orderDeadlineProcessor.fireDueDeadlines();
            if (consecutiveFailures > 0) {
                LOGGER.info("Order deadline poller resumed after {} failed poll(s)", consecutiveFailures);
                consecutiveFailures = 0;
            }
        } catch (Throwable ex) {
            consecutiveFailures++;
            if (consecutiveFailures == 1) {
                LOGGER.warn("Order deadline poll failed; retrying every {}", interval, ex);
            } else {
                LOGGER.warn("Order deadline poll failed {} times in a row: {}", consecutiveFailures, ex.toString());
            }
        }
    }


    @Override
    public synchronized void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                LOGGER.warn("Order deadline poller did not stop within 10s");
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
}
