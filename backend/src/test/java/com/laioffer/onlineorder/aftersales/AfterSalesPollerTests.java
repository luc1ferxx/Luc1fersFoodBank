package com.laioffer.onlineorder.aftersales;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;


import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;


/**
 * The after-sales poller (ADR 0002 D13, sections 9 and 24): its own named thread, a lifecycle that starts and stops
 * once, ticks that run the deadlines and then the refunds within one time budget, and ticks that never end the
 * schedule.
 */
class AfterSalesPollerTests {

    static final Duration TICK_BUDGET = Duration.ofSeconds(5);

    @Test
    void lifecycle_shouldStartOneThreadAndStopIt() throws Exception {
        AfterSalesDeadlineProcessor processor = Mockito.mock(AfterSalesDeadlineProcessor.class);
        Set<Thread> pollThreads = ConcurrentHashMap.newKeySet();
        CountDownLatch polls = new CountDownLatch(3);
        Mockito.when(processor.fireDueDeadlinesWithin(Mockito.any())).thenAnswer(invocation -> {
            pollThreads.add(Thread.currentThread());
            polls.countDown();
            return 0;
        });
        AfterSalesPoller poller = new AfterSalesPoller(
                processor, Mockito.mock(RefundExecutor.class), Duration.ofMillis(10), TICK_BUDGET);
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
        }).when(processor).fireDueDeadlinesWithin(Mockito.any());
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
        Mockito.when(processor.fireDueDeadlinesWithin(Mockito.any())).thenAnswer(invocation -> {
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
                processor, Mockito.mock(RefundExecutor.class), Duration.ofMillis(10), TICK_BUDGET);
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
        Mockito.when(processor.fireDueDeadlinesWithin(Mockito.any())).thenAnswer(invocation -> {
            if (polls.incrementAndGet() <= 3) {
                throw new IllegalStateException("database unavailable");
            }
            recovered.countDown();
            return 0;
        });
        AfterSalesPoller poller = new AfterSalesPoller(
                processor, Mockito.mock(RefundExecutor.class), Duration.ofMillis(10), TICK_BUDGET);
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
        Mockito.when(processor.fireDueDeadlinesWithin(Mockito.any()))
                .thenThrow(new AssertionError("broken"))
                .thenReturn(1);
        Mockito.when(refundExecutor.runDueRefundsWithin(Mockito.any()))
                .thenReturn(0)
                .thenThrow(new IllegalStateException("gone"));
        AfterSalesPoller poller = new AfterSalesPoller(processor, refundExecutor, Duration.ofSeconds(1), TICK_BUDGET);

        Assertions.assertDoesNotThrow(poller::poll);
        Assertions.assertDoesNotThrow(poller::poll);

        Mockito.verify(processor, Mockito.times(2)).fireDueDeadlinesWithin(Mockito.any());
        Mockito.verify(refundExecutor, Mockito.times(2)).runDueRefundsWithin(Mockito.any());
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
        Mockito.when(processor.fireDueDeadlinesWithin(Mockito.any()))
                .thenReturn(1)
                .thenThrow(new IllegalStateException("deadlines down"))
                .thenReturn(0);
        Mockito.when(refundExecutor.runDueRefundsWithin(Mockito.any()))
                .thenReturn(1)
                .thenReturn(0)
                .thenThrow(new IllegalStateException("refunds down"));
        AfterSalesPoller poller = new AfterSalesPoller(processor, refundExecutor, Duration.ofSeconds(1), TICK_BUDGET);

        poller.poll();
        poller.poll();
        poller.poll();

        InOrder ticks = Mockito.inOrder(processor, refundExecutor);
        for (int tick = 0; tick < 3; tick++) {
            ticks.verify(processor).fireDueDeadlinesWithin(Mockito.any());
            ticks.verify(refundExecutor).runDueRefundsWithin(Mockito.any());
        }
        ticks.verifyNoMoreInteractions();
    }


    /**
     * Section 24: both jobs of a tick get the same budget, measured from the start of the tick. Time the deadlines
     * took is gone for the refunds, and each tick starts a budget of its own.
     */
    @Test
    void poll_shouldShareOneBudgetFromTheStartOfTheTick() {
        AfterSalesDeadlineProcessor processor = Mockito.mock(AfterSalesDeadlineProcessor.class);
        RefundExecutor refundExecutor = Mockito.mock(RefundExecutor.class);
        AtomicLong nanoTime = new AtomicLong(-TimeUnit.SECONDS.toNanos(1));
        List<String> seen = new ArrayList<>();
        Mockito.when(processor.fireDueDeadlinesWithin(Mockito.any())).thenAnswer(invocation -> {
            TickBudget budget = invocation.getArgument(0);
            seen.add("deadlines " + budget.spent());
            // The deadlines take 3 of the 5 seconds.
            nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(3));
            seen.add("deadlines " + budget.spent());
            return 1;
        });
        Mockito.when(refundExecutor.runDueRefundsWithin(Mockito.any())).thenAnswer(invocation -> {
            TickBudget budget = invocation.getArgument(0);
            seen.add("refunds " + budget.spent());
            nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(2) - 1);
            seen.add("refunds " + budget.spent());
            nanoTime.incrementAndGet();
            seen.add("refunds " + budget.spent());
            return 1;
        });
        AfterSalesPoller poller = new AfterSalesPoller(
                processor, refundExecutor, Duration.ofSeconds(1), TICK_BUDGET, nanoTime::get);

        poller.poll();
        poller.poll();

        Assertions.assertEquals(List.of(
                "deadlines false", "deadlines false", "refunds false", "refunds false", "refunds true",
                "deadlines false", "deadlines false", "refunds false", "refunds false", "refunds true"
        ), seen);
        ArgumentCaptor<TickBudget> deadlineBudgets = ArgumentCaptor.forClass(TickBudget.class);
        ArgumentCaptor<TickBudget> refundBudgets = ArgumentCaptor.forClass(TickBudget.class);
        Mockito.verify(processor, Mockito.times(2)).fireDueDeadlinesWithin(deadlineBudgets.capture());
        Mockito.verify(refundExecutor, Mockito.times(2)).runDueRefundsWithin(refundBudgets.capture());
        Assertions.assertSame(deadlineBudgets.getAllValues().get(0), refundBudgets.getAllValues().get(0));
        Assertions.assertNotSame(deadlineBudgets.getAllValues().get(0), deadlineBudgets.getAllValues().get(1));
    }


    @Test
    void tickBudget_shouldBePositive() {
        for (Duration budget : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1), null}) {
            IllegalArgumentException refused = Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new AfterSalesPoller(Mockito.mock(AfterSalesDeadlineProcessor.class),
                            Mockito.mock(RefundExecutor.class), Duration.ofSeconds(1), budget));
            Assertions.assertEquals(
                    "app.after-sales.poller.tick-budget must be positive, got " + budget, refused.getMessage());
        }
    }


    /**
     * application.yml binds the tick budget (5 s, or AFTER_SALES_POLL_TICK_BUDGET), and a budget that is not positive
     * fails startup.
     */
    @Test
    void tickBudget_shouldBeBoundFromApplicationYmlAndCheckedAtStartup() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                // SpringApplication registers this so @Value can bind durations such as 5s.
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withBean(AfterSalesDeadlineProcessor.class, () -> Mockito.mock(AfterSalesDeadlineProcessor.class))
                .withBean(RefundExecutor.class, () -> Mockito.mock(RefundExecutor.class))
                .withUserConfiguration(AfterSalesPoller.class);

        contextRunner.run(context -> Assertions.assertEquals(
                Duration.ofSeconds(5), context.getBean(AfterSalesPoller.class).tickBudget()));
        contextRunner.withPropertyValues("AFTER_SALES_POLL_TICK_BUDGET=2s").run(context -> Assertions.assertEquals(
                Duration.ofSeconds(2), context.getBean(AfterSalesPoller.class).tickBudget()));
        contextRunner.withPropertyValues("AFTER_SALES_POLL_TICK_BUDGET=0s").run(context -> {
            Throwable cause = context.getStartupFailure();
            Assertions.assertNotNull(cause);
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            Assertions.assertInstanceOf(IllegalArgumentException.class, cause);
            Assertions.assertEquals("app.after-sales.poller.tick-budget must be positive, got PT0S",
                    cause.getMessage());
        });
    }
}
