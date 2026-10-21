package com.laioffer.onlineorder.aftersales;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;


import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


/**
 * The after-sales poller (ADR 0002 D13 and section 9): its own named thread, a lifecycle that starts and stops once,
 * ticks that run the deadlines and then the refunds, and ticks that never end the schedule.
 */
class AfterSalesPollerTests {

    @Test
    void lifecycle_shouldStartOneThreadAndStopIt() throws Exception {
        AfterSalesDeadlineProcessor processor = Mockito.mock(AfterSalesDeadlineProcessor.class);
        Set<Thread> pollThreads = ConcurrentHashMap.newKeySet();
        CountDownLatch polls = new CountDownLatch(3);
        Mockito.when(processor.fireDueDeadlines()).thenAnswer(invocation -> {
            pollThreads.add(Thread.currentThread());
            polls.countDown();
            return 0;
        });
        AfterSalesPoller poller = new AfterSalesPoller(
                processor, Mockito.mock(RefundExecutor.class), Duration.ofMillis(10));
        Assertions.assertFalse(poller.isRunning());
        Assertions.assertTrue(poller.isAutoStartup(), "started with the context");

        poller.start();
        // A second start keeps the running executor.
        poller.start();
        Assertions.assertTrue(poller.isRunning());
        Assertions.assertTrue(polls.await(5, TimeUnit.SECONDS), "the poller never ran the deadline processor");
        Assertions.assertEquals(1, pollThreads.size(), pollThreads.toString());
        Thread pollThread = pollThreads.iterator().next();
        Assertions.assertEquals(AfterSalesPoller.THREAD_NAME, pollThread.getName());
        Assertions.assertTrue(pollThread.isDaemon());

        poller.stop();
        Assertions.assertFalse(poller.isRunning());
        pollThread.join(TimeUnit.SECONDS.toMillis(5));
        Assertions.assertFalse(pollThread.isAlive(), "the poller's thread outlived stop()");
        // Stopping twice is harmless, and the poller can start again.
        poller.stop();
        CountDownLatch restarted = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            restarted.countDown();
            return 0;
        }).when(processor).fireDueDeadlines();
        poller.start();
        try {
            Assertions.assertTrue(restarted.await(5, TimeUnit.SECONDS), "the restarted poller never ran");
        } finally {
            poller.stop();
        }
    }


    @Test
    void blockedPoll_shouldNotStarveJobsOnTheSharedScheduler() throws Exception {
        AfterSalesDeadlineProcessor processor = Mockito.mock(AfterSalesDeadlineProcessor.class);
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
        AfterSalesPoller poller = new AfterSalesPoller(
                processor, Mockito.mock(RefundExecutor.class), Duration.ofMillis(10));
        try {
            sharedScheduler.scheduleWithFixedDelay(outboxTicks::release, Duration.ofMillis(10));
            poller.start();

            Assertions.assertTrue(pollStarted.await(5, TimeUnit.SECONDS));
            Assertions.assertEquals(AfterSalesPoller.THREAD_NAME, pollThread.get());
            outboxTicks.drainPermits();
            Assertions.assertTrue(
                    outboxTicks.tryAcquire(3, 5, TimeUnit.SECONDS),
                    "The shared scheduler stopped while an after-sales poll was blocked"
            );
        } finally {
            releasePoll.countDown();
            poller.stop();
            sharedScheduler.shutdown();
        }
        Assertions.assertFalse(poller.isRunning());
    }


    /**
     * A failing job never ends the schedule, and the poll after a failure streak goes on as usual.
     */
    @Test
    void failingPolls_shouldNotStopLaterPolls() throws Exception {
        AfterSalesDeadlineProcessor processor = Mockito.mock(AfterSalesDeadlineProcessor.class);
        AtomicInteger polls = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(2);
        Mockito.when(processor.fireDueDeadlines()).thenAnswer(invocation -> {
            if (polls.incrementAndGet() <= 3) {
                throw new IllegalStateException("database unavailable");
            }
            recovered.countDown();
            return 0;
        });
        AfterSalesPoller poller = new AfterSalesPoller(
                processor, Mockito.mock(RefundExecutor.class), Duration.ofMillis(10));
        try {
            poller.start();
            Assertions.assertTrue(recovered.await(5, TimeUnit.SECONDS), "Polling stopped after a failed poll");
        } finally {
            poller.stop();
        }
        Assertions.assertTrue(polls.get() >= 5);
    }


    /**
     * A tick runs on the caller's thread too and swallows every failure, an Error included.
     */
    @Test
    void poll_shouldNeverThrow() {
        AfterSalesDeadlineProcessor processor = Mockito.mock(AfterSalesDeadlineProcessor.class);
        RefundExecutor refundExecutor = Mockito.mock(RefundExecutor.class);
        Mockito.when(processor.fireDueDeadlines()).thenThrow(new AssertionError("broken")).thenReturn(1);
        Mockito.when(refundExecutor.runDueRefunds()).thenReturn(0).thenThrow(new IllegalStateException("gone"));
        AfterSalesPoller poller = new AfterSalesPoller(processor, refundExecutor, Duration.ofSeconds(1));

        Assertions.assertDoesNotThrow(poller::poll);
        Assertions.assertDoesNotThrow(poller::poll);

        Mockito.verify(processor, Mockito.times(2)).fireDueDeadlines();
        Mockito.verify(refundExecutor, Mockito.times(2)).runDueRefunds();
        Assertions.assertFalse(poller.isRunning(), "poll() alone starts nothing");
    }


    /**
     * A tick fires the deadlines first and then executes the refunds, so that a refund a timeout created in this tick
     * is executed in it too; a failing deadline job does not keep the refunds from running, nor the other way round.
     */
    @Test
    void poll_shouldRunTheDeadlinesThenTheRefundsEachFailingOnItsOwn() {
        AfterSalesDeadlineProcessor processor = Mockito.mock(AfterSalesDeadlineProcessor.class);
        RefundExecutor refundExecutor = Mockito.mock(RefundExecutor.class);
        Mockito.when(processor.fireDueDeadlines())
                .thenReturn(1)
                .thenThrow(new IllegalStateException("deadlines down"))
                .thenReturn(0);
        Mockito.when(refundExecutor.runDueRefunds())
                .thenReturn(1)
                .thenReturn(0)
                .thenThrow(new IllegalStateException("refunds down"));
        AfterSalesPoller poller = new AfterSalesPoller(processor, refundExecutor, Duration.ofSeconds(1));

        poller.poll();
        poller.poll();
        poller.poll();

        InOrder ticks = Mockito.inOrder(processor, refundExecutor);
        for (int tick = 0; tick < 3; tick++) {
            ticks.verify(processor).fireDueDeadlines();
            ticks.verify(refundExecutor).runDueRefunds();
        }
        ticks.verifyNoMoreInteractions();
    }
}
