package com.laioffer.onlineorder.aftersales;


import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * The threads the refund executor calls the gateway on: at most app.after-sales.refund-gateway.max-threads (ADR 0002
 * section 24). A thread is reserved before a refund is claimed and runs one call. A call that ignores its interrupt
 * after its timeout keeps its thread until it returns; once every thread is held that way, none can be reserved and
 * the executor claims no refund, and so uses up no attempt, until one of those calls returns. A hung gateway therefore
 * holds at most max-threads threads, instead of one more for every refund tried.
 */
final class RefundGatewayThreads {

    private final Semaphore free;
    private final ExecutorService threads;


    /**
     * @param maxThreads   the most calls running, or reserved, at once; at least 1
     * @param threadPrefix the name of each thread, before its number
     */
    static RefundGatewayThreads create(int maxThreads, String threadPrefix) {
        AtomicInteger created = new AtomicInteger();
        // A thread for every call that may run at once and no more: the reservations keep at most maxThreads calls
        // running, so a call only waits in the queue while a thread that finished its call has not yet taken the next.
        ThreadPoolExecutor threads = new ThreadPoolExecutor(maxThreads, maxThreads, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, threadPrefix + created.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        // Idle threads end after a minute; a quiet executor holds none.
        threads.allowCoreThreadTimeOut(true);
        return new RefundGatewayThreads(maxThreads, threads);
    }


    /**
     * @param threads runs the calls; it must not run more than {@code maxThreads} of them at once
     */
    RefundGatewayThreads(int maxThreads, ExecutorService threads) {
        this.free = new Semaphore(maxThreads);
        this.threads = threads;
    }


    /**
     * Reserves a thread for one call. The caller makes the call on it or releases it.
     *
     * @return the reservation, or null when every thread runs (or is reserved for) a call
     */
    Reservation reserve() {
        return free.tryAcquire() ? new Reservation() : null;
    }


    /**
     * @return the threads neither running nor reserved for a call
     */
    int freeThreads() {
        return free.availablePermits();
    }


    /**
     * Interrupts the running calls and refuses any later one.
     */
    void shutdownNow() {
        threads.shutdownNow();
    }


    /**
     * A thread reserved for one call: the call is made on it, or the reservation is released, once.
     */
    final class Reservation {

        private final AtomicBoolean done = new AtomicBoolean();


        /**
         * Starts {@code work} on the reserved thread, which is free again once the work returned, or once the call
         * was cancelled before it started.
         *
         * @throws RejectedExecutionException after {@link #shutdownNow}; the thread is free again
         */
        Call submit(Callable<RefundGatewayResult> work) {
            if (!done.compareAndSet(false, true)) {
                throw new IllegalStateException("A reserved gateway thread makes one call");
            }
            AtomicBoolean started = new AtomicBoolean();
            try {
                return new Call(threads.submit(() -> {
                    if (!started.compareAndSet(false, true)) {
                        // Cancelled before it started; the canceller freed the thread.
                        return null;
                    }
                    try {
                        return work.call();
                    } finally {
                        free.release();
                    }
                }), started);
            } catch (RejectedExecutionException ex) {
                free.release();
                throw ex;
            }
        }


        /**
         * Frees the thread when no call was made on it; a call frees it itself.
         */
        void release() {
            if (done.compareAndSet(false, true)) {
                free.release();
            }
        }
    }


    /**
     * A call on a reserved thread.
     */
    final class Call {

        private final Future<RefundGatewayResult> result;
        private final AtomicBoolean started;


        private Call(Future<RefundGatewayResult> result, AtomicBoolean started) {
            this.result = result;
            this.started = started;
        }


        /**
         * Waits for the call's answer for at most {@code timeout}.
         */
        RefundGatewayResult get(Duration timeout) throws InterruptedException, ExecutionException, TimeoutException {
            return result.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }


        /**
         * Stops waiting for the call: a running call is interrupted and keeps its thread until it returns; a call that
         * has not started never will, and its thread is free at once.
         */
        void cancel() {
            if (started.compareAndSet(false, true)) {
                free.release();
            }
            result.cancel(true);
        }
    }
}
