package com.laioffer.onlineorder;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;


import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;


class SchedulingConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(SchedulingTestConfiguration.class);


    @Test
    void taskScheduler_shouldUseTheConfiguredPool() {
        contextRunner.run(context -> {
            ThreadPoolTaskScheduler scheduler = context.getBean(ThreadPoolTaskScheduler.class);

            Assertions.assertEquals(4, scheduler.getScheduledThreadPoolExecutor().getCorePoolSize());
            Assertions.assertEquals("scheduling-", scheduler.getThreadNamePrefix());
        });
    }


    @Test
    void taskScheduler_shouldHonorThePoolSizeOverride() {
        contextRunner
                .withPropertyValues("TASK_SCHEDULING_POOL_SIZE=2")
                .run(context -> Assertions.assertEquals(
                        2,
                        context.getBean(ThreadPoolTaskScheduler.class).getScheduledThreadPoolExecutor().getCorePoolSize()
                ));
    }


    @Test
    void blockedJob_shouldNotStarveOtherScheduledJobs() {
        // Only this test registers the jobs and releases the blocking one, so the other contexts do not wait
        // out the scheduler's 10s shutdown timeout on close.
        contextRunner.withBean(ScheduledJobs.class).run(context -> {
            ScheduledJobs jobs = context.getBean(ScheduledJobs.class);
            try {
                Assertions.assertTrue(jobs.blockingJobStarted.await(5, TimeUnit.SECONDS));
                jobs.ticks.drainPermits();

                Assertions.assertTrue(
                        jobs.ticks.tryAcquire(3, 5, TimeUnit.SECONDS),
                        "The ticking job stopped while the blocking job held a scheduler thread"
                );
                Assertions.assertTrue(jobs.tickThreadName.get().startsWith("scheduling-"));
            } finally {
                jobs.releaseBlockingJob.countDown();
            }
        });
    }


    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingTestConfiguration {
    }


    static class ScheduledJobs {

        private final CountDownLatch blockingJobStarted = new CountDownLatch(1);
        private final CountDownLatch releaseBlockingJob = new CountDownLatch(1);
        private final Semaphore ticks = new Semaphore(0);
        private final AtomicReference<String> tickThreadName = new AtomicReference<>();


        // Stands in for a long MaintenanceCleanupService run.
        @Scheduled(fixedDelay = 60_000)
        public void blockingJob() throws InterruptedException {
            blockingJobStarted.countDown();
            releaseBlockingJob.await();
        }


        // Stands in for the outbox publisher.
        @Scheduled(fixedDelay = 10)
        public void tickingJob() {
            tickThreadName.set(Thread.currentThread().getName());
            ticks.release();
        }
    }
}
