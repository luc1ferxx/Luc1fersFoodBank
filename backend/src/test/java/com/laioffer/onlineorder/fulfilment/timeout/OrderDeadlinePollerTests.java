package com.laioffer.onlineorder.fulfilment.timeout;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;


import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


class OrderDeadlinePollerTests {

    @Test
    void blockedPoll_shouldNotStarveJobsOnTheSharedScheduler() throws Exception {
        OrderDeadlineProcessor processor = Mockito.mock(OrderDeadlineProcessor.class);
        CountDownLatch pollStarted = new CountDownLatch(1);
        CountDownLatch releasePoll = new CountDownLatch(1);
        AtomicReference<String> pollThread = new AtomicReference<>();
        Mockito.when(processor.fireDueDeadlines()).thenAnswer(invocation -> {
            pollThread.set(Thread.currentThread().getName());
            pollStarted.countDown();
            releasePoll.await();
            return 0;
        });
        // The tightest possible shared scheduler: one thread, standing in for the pool the outbox publisher uses.
        ThreadPoolTaskScheduler sharedScheduler = new ThreadPoolTaskScheduler();
        sharedScheduler.setPoolSize(1);
        sharedScheduler.setThreadNamePrefix("scheduling-");
        sharedScheduler.initialize();
        Semaphore outboxTicks = new Semaphore(0);
        OrderDeadlinePoller poller = new OrderDeadlinePoller(processor, Duration.ofMillis(10));
        try {
            sharedScheduler.scheduleWithFixedDelay(outboxTicks::release, Duration.ofMillis(10));
            poller.start();

            Assertions.assertTrue(pollStarted.await(5, TimeUnit.SECONDS));
            Assertions.assertEquals(OrderDeadlinePoller.THREAD_NAME, pollThread.get());
            outboxTicks.drainPermits();
            Assertions.assertTrue(
                    outboxTicks.tryAcquire(3, 5, TimeUnit.SECONDS),
                    "The shared scheduler stopped while a deadline poll was blocked"
            );
        } finally {
            releasePoll.countDown();
            poller.stop();
            sharedScheduler.shutdown();
        }
        Assertions.assertFalse(poller.isRunning());
    }


    @Test
    void failingPoll_shouldNotStopLaterPolls() throws Exception {
        OrderDeadlineProcessor processor = Mockito.mock(OrderDeadlineProcessor.class);
        AtomicInteger polls = new AtomicInteger();
        CountDownLatch thirdPoll = new CountDownLatch(3);
        Mockito.when(processor.fireDueDeadlines()).thenAnswer(invocation -> {
            polls.incrementAndGet();
            thirdPoll.countDown();
            throw new IllegalStateException("database unavailable");
        });
        OrderDeadlinePoller poller = new OrderDeadlinePoller(processor, Duration.ofMillis(10));
        try {
            poller.start();
            Assertions.assertTrue(thirdPoll.await(5, TimeUnit.SECONDS), "Polling stopped after a failed poll");
        } finally {
            poller.stop();
        }
        Assertions.assertTrue(polls.get() >= 3);
    }
}
