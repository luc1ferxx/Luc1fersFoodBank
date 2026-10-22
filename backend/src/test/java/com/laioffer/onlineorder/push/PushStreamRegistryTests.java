package com.laioffer.onlineorder.push;


import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import java.util.List;
import java.util.Set;


/**
 * The registry of open streams: delivery by recipient, the per-user and per-instance limits, and a stream leaving
 * whatever closes it.
 */
class PushStreamRegistryTests {

    private static final PushRecipient CUSTOMER = PushRecipient.customer(7L);
    private static final PushRecipient RESTAURANT = PushRecipient.restaurant(2L);

    private SimpleMeterRegistry meters;
    private PushStreamRegistry registry;


    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        PushMetrics metrics = new PushMetrics(meters);
        registry = new PushStreamRegistry(4, 2, metrics);
        metrics.bindStreams(registry::size);
    }


    @Test
    void deliver_shouldWriteTheSignalToEveryStreamOfTheRecipientOnly() {
        RecordingChannel merchant = RecordingChannel.open(registry, 7L, CUSTOMER, RESTAURANT);
        RecordingChannel otherTab = RecordingChannel.open(registry, 8L, PushRecipient.customer(8L), RESTAURANT);
        RecordingChannel stranger = RecordingChannel.open(registry, 9L, PushRecipient.customer(9L));

        Assertions.assertEquals(2, registry.deliver(RESTAURANT, PushTopic.MERCHANT_ORDERS));
        Assertions.assertEquals(0, registry.deliver(PushRecipient.rider(2L), PushTopic.RIDER_DELIVERIES),
                "a rider id is not a restaurant id");

        String frame = "event: signal\ndata: {\"topic\":\"merchant.orders\"}\n\n";
        Assertions.assertEquals(List.of(frame), merchant.frames());
        Assertions.assertEquals(List.of(frame), otherTab.frames());
        Assertions.assertTrue(stranger.frames().isEmpty());
        Assertions.assertEquals(2.0, deliveries("merchant.orders"));
        Assertions.assertEquals(0.0, deliveries("rider.deliveries"));
        Assertions.assertEquals(3.0, meters.get(PushMetrics.STREAMS).gauge().value());
    }


    @Test
    void register_shouldRefuseALoginAtItsLimitAndAnInstanceAtItsLimit() {
        RecordingChannel.open(registry, 7L, CUSTOMER);
        RecordingChannel.open(registry, 7L, CUSTOMER);

        Assertions.assertEquals(PushStreamRegistry.Registration.USER_LIMIT, registry.register(stream(7L)),
                "max-streams-per-user is 2");
        RecordingChannel.open(registry, 8L, PushRecipient.customer(8L));
        RecordingChannel.open(registry, 9L, PushRecipient.customer(9L));
        Assertions.assertEquals(PushStreamRegistry.Registration.INSTANCE_LIMIT, registry.register(stream(10L)),
                "max-streams is 4");
        Assertions.assertEquals(4, registry.size());
    }


    @Test
    void aStreamThatEnds_shouldLeaveTheRegistryAndFreeItsLoginsSlot() {
        PushStream first = stream(7L);
        Assertions.assertEquals(PushStreamRegistry.Registration.REGISTERED, registry.register(first));
        RecordingChannel.open(registry, 7L, CUSTOMER);
        Assertions.assertEquals(PushStreamRegistry.Registration.USER_LIMIT, registry.register(stream(7L)));

        first.ended();
        first.ended();

        Assertions.assertEquals(1, registry.size());
        Assertions.assertEquals(PushStreamRegistry.Registration.REGISTERED, registry.register(stream(7L)));
        Assertions.assertEquals(2, registry.size());
    }


    @Test
    void aFailedWrite_shouldCloseTheStreamWithoutCompletingIt() {
        RecordingChannel gone = RecordingChannel.open(registry, 7L, CUSTOMER);
        RecordingChannel open = RecordingChannel.open(registry, 8L, CUSTOMER);
        gone.failWrites();

        Assertions.assertEquals(1, registry.deliver(CUSTOMER, PushTopic.NOTIFICATIONS));

        Assertions.assertEquals(1, registry.size());
        Assertions.assertFalse(gone.isCompleted(), "the container completes a request whose client went away");
        Assertions.assertEquals(1, open.frames().size());
        Assertions.assertEquals(1, registry.deliver(CUSTOMER, PushTopic.NOTIFICATIONS));
    }


    @Test
    void aStreamClosedWhileItWasRegistered_shouldNotStayRegistered() {
        PushStream stream = stream(7L);
        stream.ended();

        Assertions.assertEquals(PushStreamRegistry.Registration.REGISTERED, registry.register(stream));

        Assertions.assertEquals(0, registry.size());
        Assertions.assertEquals(0, registry.deliver(CUSTOMER, PushTopic.NOTIFICATIONS));
    }


    @Test
    void close_shouldCompleteEveryStreamAndRefuseNewOnesUntilOpenedAgain() {
        RecordingChannel first = RecordingChannel.open(registry, 7L, CUSTOMER);
        RecordingChannel second = RecordingChannel.open(registry, 8L, RESTAURANT);

        registry.close();

        Assertions.assertTrue(first.isCompleted());
        Assertions.assertTrue(second.isCompleted());
        Assertions.assertEquals(0, registry.size());
        Assertions.assertEquals(PushStreamRegistry.Registration.CLOSED, registry.register(stream(9L)));
        registry.open();
        Assertions.assertEquals(PushStreamRegistry.Registration.REGISTERED, registry.register(stream(9L)));
    }


    @Test
    void complete_shouldWriteTheLastFrameAndCompleteOnce() {
        RecordingChannel channel = new RecordingChannel();
        PushStream stream = new PushStream(7L, Set.of(CUSTOMER), PushStream.AccessCheck.ALWAYS, channel);
        registry.register(stream);

        stream.complete(SseFrames.FORBIDDEN);
        stream.complete(SseFrames.FORBIDDEN);

        Assertions.assertEquals(List.of("event: forbidden\ndata: {}\n\n"), channel.frames());
        Assertions.assertTrue(channel.isCompleted());
        Assertions.assertFalse(stream.send(SseFrames.KEEPALIVE), "a closed stream writes nothing");
        Assertions.assertEquals(0, registry.size());
    }


    @Test
    void wireNames_shouldRoundTripAndRejectAnythingElse() {
        for (PushTopic topic : PushTopic.values()) {
            Assertions.assertEquals(topic, PushTopic.fromWireName(topic.wireName()));
        }
        Assertions.assertEquals(PushRecipient.restaurant(5L), PushRecipient.parse("restaurant:5"));
        Assertions.assertEquals("rider:12", PushRecipient.rider(12L).wireName());
        Assertions.assertEquals("customer:3", PushRecipient.parse("customer:3").toString());
        for (String bad : new String[]{null, "", "customer", "customer:", "admin:1", "rider:x", ":4"}) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> PushRecipient.parse(bad), bad);
        }
        Assertions.assertThrows(IllegalArgumentException.class, () -> PushTopic.fromWireName("orders"));
        Assertions.assertEquals("retry: 5000\n\n", SseFrames.OPEN);
        Assertions.assertEquals(":keepalive\n\n", SseFrames.KEEPALIVE);
    }


    private double deliveries(String topic) {
        return meters.get(PushMetrics.DELIVERIES).tag("topic", topic).counter().count();
    }


    private static PushStream stream(long userId) {
        return new PushStream(userId, Set.of(PushRecipient.customer(userId)), PushStream.AccessCheck.ALWAYS,
                new RecordingChannel());
    }
}
