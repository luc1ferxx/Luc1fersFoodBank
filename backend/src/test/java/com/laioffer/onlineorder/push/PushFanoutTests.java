package com.laioffer.onlineorder.push;


import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;


import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


/**
 * The two fan-outs without Redis: the local one delivers on its own thread, the Redis one publishes the JSON message
 * and fails open when Redis refuses. The Redis round trip between instances is {@link PushRedisFanoutIntegrationTests}.
 */
class PushFanoutTests {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private SimpleMeterRegistry meters;
    private PushMetrics metrics;
    private PushStreamRegistry registry;
    private LocalPushFanout local;
    private RedisPushFanout redis;


    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        metrics = new PushMetrics(meters);
        registry = new PushStreamRegistry(10, 5, metrics);
    }


    @AfterEach
    void tearDown() {
        if (local != null) {
            local.destroy();
        }
        if (redis != null) {
            redis.destroy();
        }
    }


    @Test
    void localFanout_shouldDeliverToThisInstancesStreamsOnTheDeliveryThread() {
        local = new LocalPushFanout(registry, metrics);
        RecordingChannel rider = RecordingChannel.open(
                registry, 1L, PushRecipient.customer(1L), PushRecipient.rider(4L));

        local.send(PushRecipient.rider(4L), PushTopic.RIDER_DELIVERIES);

        Assertions.assertEquals("event: signal\ndata: {\"topic\":\"rider.deliveries\"}\n\n",
                rider.awaitSignal(PushTopic.RIDER_DELIVERIES, WAIT));
    }


    @Test
    void localFanout_shouldReportTheSubscribedGaugeAsOne() {
        local = new LocalPushFanout(registry, metrics);

        Assertions.assertEquals(1.0, meters.get(PushMetrics.SUBSCRIBED).gauge().value(),
                "no subscription to lose: the series exists and reads 1");
    }


    @Test
    void redisFanout_shouldPublishTheSignalAsJsonOnTheChannel() throws Exception {
        StringRedisTemplate template = Mockito.mock(StringRedisTemplate.class);
        CountDownLatch published = new CountDownLatch(1);
        Mockito.when(template.convertAndSend(Mockito.anyString(), Mockito.any())).thenAnswer(invocation -> {
            published.countDown();
            return 1L;
        });
        redis = new RedisPushFanout(template, JsonMapper.builder().build(), "onlineorder:push:v1", metrics);

        redis.send(PushRecipient.restaurant(5L), PushTopic.MERCHANT_AFTER_SALES);

        Assertions.assertTrue(published.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        Mockito.verify(template).convertAndSend("onlineorder:push:v1",
                "{\"recipient\":\"restaurant:5\",\"topic\":\"merchant.after_sales\"}");
        Assertions.assertEquals(0.0, failures());
    }


    @Test
    void redisFanout_whenRedisRefuses_shouldCountTheFailureAndKeepPublishingLaterSignals() throws Exception {
        StringRedisTemplate template = Mockito.mock(StringRedisTemplate.class);
        CountDownLatch attempts = new CountDownLatch(3);
        Mockito.when(template.convertAndSend(Mockito.anyString(), Mockito.any())).thenAnswer(invocation -> {
            attempts.countDown();
            throw new RedisConnectionFailureException("Unable to connect to Redis");
        });
        redis = new RedisPushFanout(template, JsonMapper.builder().build(), "onlineorder:push:v1", metrics);

        for (int i = 0; i < 3; i++) {
            Assertions.assertDoesNotThrow(() -> redis.send(PushRecipient.customer(1L), PushTopic.NOTIFICATIONS));
        }

        Assertions.assertTrue(attempts.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "every signal is tried");
        Awaitility.await().atMost(WAIT).until(() -> failures() == 3.0);
    }


    @Test
    void aFullQueue_shouldDropTheTaskInsteadOfBlockingTheCaller() throws Exception {
        PushWorker worker = new PushWorker("push-test", 1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        try {
            Assertions.assertTrue(worker.submit(() -> {
                running.countDown();
                await(release);
            }));
            Assertions.assertTrue(running.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            Assertions.assertTrue(worker.submit(() -> {
            }), "one task fits in the queue");
            Assertions.assertFalse(worker.submit(() -> {
            }), "the next one is dropped");
        } finally {
            release.countDown();
            worker.shutdown();
        }
        Assertions.assertFalse(worker.submit(() -> {
        }), "a worker that was shut down takes nothing");
    }


    private double failures() {
        return meters.get(PushMetrics.PUBLISH_FAILURES).counter().count();
    }


    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
