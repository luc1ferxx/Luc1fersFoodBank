package com.laioffer.onlineorder.push;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


/**
 * One daemon thread with a bounded queue, for the push work that must not run on a writer's thread: a Redis publish or
 * the writes to the streams. It is not a TaskScheduler or TaskExecutor bean (a second TaskScheduler bean would make
 * Spring Boot back off its own scheduler), and a full queue drops the task instead of blocking the caller: a lost
 * signal only costs a later read.
 */
final class PushWorker {

    static final int QUEUE_CAPACITY = 10_000;

    private static final Logger LOGGER = LoggerFactory.getLogger(PushWorker.class);

    private final String name;
    private final ThreadPoolExecutor executor;


    PushWorker(String threadName) {
        this(threadName, QUEUE_CAPACITY);
    }


    PushWorker(String threadName, int queueCapacity) {
        this.name = threadName;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, threadName);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }


    /**
     * Queues the task; one that throws is logged, and the thread goes on with the next.
     *
     * @return false when the queue is full or the worker was shut down, and the task was dropped
     */
    boolean submit(Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException ex) {
                    LOGGER.warn("Push task on {} failed", name, ex);
                }
            });
            return true;
        } catch (RejectedExecutionException ex) {
            return false;
        }
    }


    void shutdown() {
        executor.shutdownNow();
    }
}
