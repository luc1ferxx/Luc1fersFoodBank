package com.laioffer.onlineorder.push;


import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;


import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


/**
 * The keepalive tick (a comment to every stream, then the re-check of the staff streams) and the shutdown, called
 * directly instead of waiting for the "push-keepalive" thread.
 */
class PushStreamHousekeepingTests {

    private PushStreamRegistry registry;
    private PushStreamHousekeeping housekeeping;


    @BeforeEach
    void setUp() {
        registry = new PushStreamRegistry(10, 5, new PushMetrics(new SimpleMeterRegistry()));
        housekeeping = new PushStreamHousekeeping(registry, Duration.ofHours(1), Duration.ofMinutes(30));
    }


    @Test
    void tick_shouldKeepEveryStreamAliveAndCloseADeactivatedMerchantsWithForbidden() {
        RecordingChannel customer = RecordingChannel.open(registry, 1L, PushRecipient.customer(1L));
        AtomicBoolean merchantActive = new AtomicBoolean(true);
        AtomicInteger checks = new AtomicInteger();
        RecordingChannel merchant = open(2L, () -> {
            checks.incrementAndGet();
            return merchantActive.get();
        }, PushRecipient.customer(2L), PushRecipient.restaurant(5L));

        housekeeping.tick();

        Assertions.assertEquals(List.of(":keepalive\n\n"), customer.frames());
        Assertions.assertEquals(List.of(":keepalive\n\n"), merchant.frames());
        Assertions.assertEquals(1, checks.get(), "only the staff stream is re-checked");

        merchantActive.set(false);
        housekeeping.tick();

        Assertions.assertEquals(List.of(":keepalive\n\n", ":keepalive\n\n", "event: forbidden\ndata: {}\n\n"),
                merchant.frames());
        Assertions.assertTrue(merchant.isCompleted());
        Assertions.assertEquals(1, registry.size());
        Assertions.assertEquals(0, registry.deliver(PushRecipient.restaurant(5L), PushTopic.MERCHANT_ORDERS));
        Assertions.assertFalse(customer.isCompleted());
    }


    @Test
    void tick_whenTheCheckFails_shouldKeepTheStream() {
        RecordingChannel rider = open(3L, () -> {
            throw new DataAccessResourceFailureException("database unreachable");
        }, PushRecipient.customer(3L), PushRecipient.rider(9L));

        housekeeping.tick();

        Assertions.assertEquals(List.of(":keepalive\n\n"), rider.frames());
        Assertions.assertFalse(rider.isCompleted());
        Assertions.assertEquals(1, registry.size());
    }


    @Test
    void tick_shouldDropAStreamWhoseClientWentAway() {
        RecordingChannel gone = RecordingChannel.open(registry, 1L, PushRecipient.customer(1L));
        gone.failWrites();

        housekeeping.tick();

        Assertions.assertEquals(0, registry.size());
    }


    @Test
    void tick_shouldDropAStreamThatOutlivedItsTimeoutByAnInterval() {
        AtomicLong now = new AtomicLong(System.nanoTime());
        PushStreamHousekeeping clocked = new PushStreamHousekeeping(
                registry, Duration.ofSeconds(25), Duration.ofMinutes(30), now::get);
        RecordingChannel stuck = RecordingChannel.open(registry, 1L, PushRecipient.customer(1L));

        now.addAndGet(Duration.ofMinutes(30).toNanos());
        clocked.tick();
        Assertions.assertEquals(List.of(":keepalive\n\n"), stuck.frames(), "within its timeout and one interval");

        now.addAndGet(Duration.ofSeconds(26).toNanos());
        clocked.tick();
        Assertions.assertTrue(stuck.isCompleted());
        Assertions.assertEquals(0, registry.size());
    }


    @Test
    void stop_shouldCompleteTheOpenStreamsAndRefuseNewOnesUntilStartedAgain() {
        housekeeping.start();
        RecordingChannel open = RecordingChannel.open(registry, 1L, PushRecipient.customer(1L));

        housekeeping.stop();

        Assertions.assertFalse(housekeeping.isRunning());
        Assertions.assertTrue(open.isCompleted());
        Assertions.assertEquals(PushStreamRegistry.Registration.CLOSED, registry.register(new PushStream(
                2L, Set.of(PushRecipient.customer(2L)), PushStream.AccessCheck.ALWAYS, new RecordingChannel())));

        housekeeping.start();
        Assertions.assertTrue(housekeeping.isRunning());
        RecordingChannel.open(registry, 2L, PushRecipient.customer(2L));
        housekeeping.stop();
    }


    private RecordingChannel open(long userId, PushStream.AccessCheck check, PushRecipient... recipients) {
        RecordingChannel channel = new RecordingChannel();
        Assertions.assertEquals(PushStreamRegistry.Registration.REGISTERED,
                registry.register(new PushStream(userId, Set.of(recipients), check, channel)));
        return channel;
    }
}
