package com.laioffer.onlineorder.aftersales;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * The gateway threads of the refund executor (ADR 0002 section 24): at most max-threads calls reserved or running, a
 * thread given back when its call returns (not when it is cancelled while still running), at once when a call is
 * cancelled before it started, and when no call is made on a reservation.
 */
class RefundGatewayThreadsTests {

    private static final RefundGatewayResult ANSWER = new RefundGatewayResult.Succeeded("SIMREF-1");

    private final List<RefundGatewayThreads> created = new ArrayList<>();

    private final List<ExecutorService> executors = new ArrayList<>();


    @AfterEach
    void shutDown() {
        created.forEach(RefundGatewayThreads::shutdownNow);
        executors.forEach(ExecutorService::shutdownNow);
    }


    @Test
    void reservations_shouldStopAtMaxThreadsAndComeBackWhenReleased() {
        RefundGatewayThreads threads = threads(2);

        RefundGatewayThreads.Reservation first = threads.reserve();
        RefundGatewayThreads.Reservation second = threads.reserve();

        Assertions.assertNotNull(first);
        Assertions.assertNotNull(second);
        Assertions.assertNull(threads.reserve(), "a third reservation over two threads");
        Assertions.assertEquals(0, threads.freeThreads());
        first.release();
        // Releasing twice gives back one thread, not two.
        first.release();
        Assertions.assertEquals(1, threads.freeThreads());
        Assertions.assertNotNull(threads.reserve());
        Assertions.assertNull(threads.reserve());
    }


    @Test
    void call_shouldHoldItsThreadUntilItReturns() throws Exception {
        RefundGatewayThreads threads = threads(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RefundGatewayThreads.Reservation reservation = threads.reserve();

        RefundGatewayThreads.Call call = reservation.submit(() -> {
            entered.countDown();
            Assertions.assertTrue(release.await(5, TimeUnit.SECONDS));
            return ANSWER;
        });
        // The call gives the thread back itself; releasing the used reservation changes nothing.
        reservation.release();

        Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS));
        Assertions.assertEquals(0, threads.freeThreads());
        Assertions.assertNull(threads.reserve());
        release.countDown();
        Assertions.assertEquals(ANSWER, call.get(Duration.ofSeconds(5)));
        // The thread is given back before the answer is handed over.
        Assertions.assertEquals(1, threads.freeThreads());
        IllegalStateException twice = Assertions.assertThrows(IllegalStateException.class,
                () -> reservation.submit(() -> ANSWER));
        Assertions.assertEquals("A reserved gateway thread makes one call", twice.getMessage());
    }


    /**
     * A timed-out call is interrupted; one that ignores the interrupt keeps its thread until it returns.
     */
    @Test
    void cancelledRunningCall_shouldBeInterruptedAndKeepItsThreadUntilItReturns() throws Exception {
        RefundGatewayThreads threads = threads(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        RefundGatewayThreads.Call call = threads.reserve().submit(() -> {
            entered.countDown();
            try {
                while (true) {
                    try {
                        if (release.await(30, TimeUnit.SECONDS)) {
                            return ANSWER;
                        }
                    } catch (InterruptedException ex) {
                        interrupted.countDown();
                    }
                }
            } finally {
                returned.countDown();
            }
        });
        Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS));

        call.cancel();

        Assertions.assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the call was not interrupted");
        Assertions.assertEquals(0, threads.freeThreads(), "a call still running keeps its thread");
        Assertions.assertNull(threads.reserve());
        release.countDown();
        Assertions.assertTrue(returned.await(5, TimeUnit.SECONDS));
        awaitFreeThreads(threads, 1);
        Assertions.assertNotNull(threads.reserve());
    }


    /**
     * A call cancelled while it waits for a thread (the one thread here is busy) never runs, and its reservation is
     * given back at once.
     */
    @Test
    void callCancelledBeforeItStarted_shouldNeverRunAndFreeItsThreadAtOnce() throws Exception {
        ExecutorService busy = Executors.newSingleThreadExecutor();
        executors.add(busy);
        CountDownLatch release = new CountDownLatch(1);
        busy.submit(() -> release.await(5, TimeUnit.SECONDS));
        RefundGatewayThreads threads = new RefundGatewayThreads(2, busy);
        AtomicBoolean ran = new AtomicBoolean();

        RefundGatewayThreads.Call call = threads.reserve().submit(() -> {
            ran.set(true);
            return ANSWER;
        });
        Assertions.assertEquals(1, threads.freeThreads());
        call.cancel();

        Assertions.assertEquals(2, threads.freeThreads());
        release.countDown();
        // Whatever the executor ran after the cancel has run once this marker has.
        Future<Boolean> marker = busy.submit(() -> true);
        Assertions.assertTrue(marker.get(5, TimeUnit.SECONDS));
        Assertions.assertFalse(ran.get(), "a call cancelled before it started ran");
        Assertions.assertEquals(2, threads.freeThreads());
    }


    @Test
    void submitAfterShutdown_shouldBeRefusedAndFreeTheThread() {
        RefundGatewayThreads threads = threads(1);
        threads.shutdownNow();
        RefundGatewayThreads.Reservation reservation = threads.reserve();

        Assertions.assertThrows(RejectedExecutionException.class, () -> reservation.submit(() -> ANSWER));

        Assertions.assertEquals(1, threads.freeThreads());
    }


    /**
     * Calls run on named daemon threads, never more of them than max-threads.
     */
    @Test
    void calls_shouldRunOnAtMostMaxThreadsNamedDaemonThreads() throws Exception {
        RefundGatewayThreads threads = threads(2);
        Set<Thread> used = ConcurrentHashMap.newKeySet();
        for (int round = 0; round < 5; round++) {
            CountDownLatch bothRunning = new CountDownLatch(2);
            List<RefundGatewayThreads.Call> calls = new ArrayList<>();
            for (int call = 0; call < 2; call++) {
                calls.add(threads.reserve().submit(() -> {
                    used.add(Thread.currentThread());
                    bothRunning.countDown();
                    Assertions.assertTrue(bothRunning.await(5, TimeUnit.SECONDS));
                    return ANSWER;
                }));
            }
            for (RefundGatewayThreads.Call call : calls) {
                Assertions.assertEquals(ANSWER, call.get(Duration.ofSeconds(5)));
            }
        }

        Assertions.assertTrue(used.size() <= 2, used.toString());
        for (Thread thread : used) {
            Assertions.assertTrue(thread.getName().startsWith(RefundExecutor.GATEWAY_THREAD_PREFIX), thread.getName());
            Assertions.assertTrue(thread.isDaemon());
        }
    }


    private RefundGatewayThreads threads(int maxThreads) {
        RefundGatewayThreads threads = RefundGatewayThreads.create(maxThreads, RefundExecutor.GATEWAY_THREAD_PREFIX);
        created.add(threads);
        return threads;
    }


    /**
     * Waits until a call that outlived its cancel has returned and given its thread back.
     */
    private static void awaitFreeThreads(RefundGatewayThreads threads, int free) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (threads.freeThreads() < free) {
            Assertions.assertTrue(System.nanoTime() < deadline, "the thread was never given back");
            Thread.onSpinWait();
        }
    }
}
