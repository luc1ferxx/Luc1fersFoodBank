package com.laioffer.onlineorder.push;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;


import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;


/**
 * Keeps the open streams alive on one thread of its own ("push-keepalive"), not on the shared Spring scheduler: a
 * TaskScheduler bean of its own would make Spring Boot back off its scheduler for every {@code @Scheduled} job.
 *
 * <p>Every {@code app.push.keepalive-interval} (25s) a tick writes the {@code :keepalive} comment to every stream,
 * which also finds the streams whose client went away, then re-checks the active flag of every merchant's and rider's
 * stream: a login that was deactivated gets {@code event: forbidden} and its stream is completed. The keepalives go
 * first, so a slow database delays only the checks. A check that fails (the database is unreachable) keeps the
 * stream; every merchant and rider request checks the flag again anyway.
 *
 * <p>A stream that is still registered one interval after its own timeout (its connection's callbacks never came, say
 * because the request never went async) is completed and dropped, so no stream outlives
 * {@code app.push.stream-timeout} by much.
 *
 * <p>On shutdown the instance stops taking streams and completes the open ones, before the web server's graceful
 * shutdown, which would otherwise wait for them; the browsers reconnect to another instance.
 */
public final class PushStreamHousekeeping implements SmartLifecycle {

    static final String THREAD_NAME = "push-keepalive";

    private static final Logger LOGGER = LoggerFactory.getLogger(PushStreamHousekeeping.class);

    private final PushStreamRegistry registry;
    private final Duration interval;
    private final long maxAgeNanos;
    private final LongSupplier nanoTime;
    private ScheduledExecutorService executor;


    /**
     * @param interval      time between ticks
     * @param streamTimeout the streams' own timeout; a stream registered one interval longer than that is dropped
     */
    public PushStreamHousekeeping(PushStreamRegistry registry, Duration interval, Duration streamTimeout) {
        this(registry, interval, streamTimeout, System::nanoTime);
    }


    PushStreamHousekeeping(
            PushStreamRegistry registry,
            Duration interval,
            Duration streamTimeout,
            LongSupplier nanoTime
    ) {
        this.registry = registry;
        this.interval = interval;
        this.maxAgeNanos = streamTimeout.plus(interval).toNanos();
        this.nanoTime = nanoTime;
    }


    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        registry.open();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = Math.max(1L, interval.toMillis());
        executor.scheduleWithFixedDelay(this::tick, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }


    /**
     * One run. Never throws: an exception would cancel every later run of a ScheduledExecutorService task.
     */
    void tick() {
        try {
            List<PushStream> streams = registry.streams();
            long now = nanoTime.getAsLong();
            for (PushStream stream : streams) {
                if (now - stream.openedAtNanos() > maxAgeNanos) {
                    LOGGER.debug("Push stream of user {} outlived its timeout; dropping it", stream.userId());
                    stream.complete(null);
                } else {
                    stream.send(SseFrames.KEEPALIVE);
                }
            }
            for (PushStream stream : streams) {
                boolean staff = stream.accessCheck() != PushStream.AccessCheck.ALWAYS;
                if (staff && !stream.isClosed() && !stillAllowed(stream)) {
                    LOGGER.info("Push stream of user {} closed: the merchant account or rider was deactivated",
                            stream.userId());
                    stream.complete(SseFrames.FORBIDDEN);
                }
            }
        } catch (RuntimeException ex) {
            LOGGER.warn("Push stream keepalive failed", ex);
        }
    }


    private static boolean stillAllowed(PushStream stream) {
        try {
            return stream.accessCheck().stillAllowed();
        } catch (RuntimeException ex) {
            LOGGER.debug("Re-checking the push stream of user {} failed; it stays open: {}", stream.userId(),
                    ex.toString());
            return true;
        }
    }


    @Override
    public synchronized void stop() {
        registry.close();
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        executor = null;
    }


    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
