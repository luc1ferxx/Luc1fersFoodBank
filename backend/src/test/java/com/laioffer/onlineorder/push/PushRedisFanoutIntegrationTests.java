package com.laioffer.onlineorder.push;


import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;


import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;


/**
 * The redis fan-out against a real Redis: two instances, each with its own streams, publisher and subscription on its
 * own connection factory. A signal published on one reaches the other's stream of the recipient (and its own), the
 * subscription comes back after Redis drops its connection, and a publish to an unreachable Redis fails open.
 */
@Testcontainers(disabledWithoutDocker = true)
class PushRedisFanoutIntegrationTests {

    private static final String CHANNEL = "onlineorder:push:v1";
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    @Container
    static GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory raw;

    private final List<Instance> instances = new ArrayList<>();


    @BeforeAll
    static void connect() {
        raw = connectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
    }


    @AfterAll
    static void disconnect() {
        raw.destroy();
    }


    @AfterEach
    void stopInstances() throws Exception {
        for (Instance instance : instances) {
            instance.stop();
        }
    }


    @Test
    void aSignalPublishedOnOneInstance_shouldReachTheRecipientsStreamsOnEveryInstance() {
        Instance first = newInstance();
        Instance second = newInstance();
        awaitSubscribed(first, second);
        RecordingChannel merchantOnSecond = RecordingChannel.open(
                second.registry(), 7L, PushRecipient.customer(7L), PushRecipient.restaurant(2L));
        RecordingChannel merchantOnFirst = RecordingChannel.open(
                first.registry(), 8L, PushRecipient.customer(8L), PushRecipient.restaurant(2L));
        RecordingChannel otherRestaurant = RecordingChannel.open(
                second.registry(), 9L, PushRecipient.customer(9L), PushRecipient.restaurant(3L));

        first.signals().publish(PushRecipient.restaurant(2L), PushTopic.MERCHANT_ORDERS);

        merchantOnSecond.awaitSignal(PushTopic.MERCHANT_ORDERS, WAIT);
        merchantOnFirst.awaitSignal(PushTopic.MERCHANT_ORDERS, WAIT);
        // Redis delivers the channel's messages in order and each instance hands them to one delivery thread: once this
        // later signal arrived, the earlier one was handled on the second instance too.
        second.signals().publish(PushRecipient.restaurant(3L), PushTopic.MERCHANT_AFTER_SALES);
        otherRestaurant.awaitSignal(PushTopic.MERCHANT_AFTER_SALES, WAIT);
        Assertions.assertEquals(0, otherRestaurant.signals(PushTopic.MERCHANT_ORDERS));
        Awaitility.await().atMost(WAIT).until(() -> first.deliveries("merchant.orders") == 1.0
                && second.deliveries("merchant.orders") == 1.0);
        Assertions.assertEquals(1.0, first.signalsPublished("merchant.orders"), "counted where it was published");
        Assertions.assertEquals(0.0, second.signalsPublished("merchant.orders"));
    }


    @Test
    void theSubscription_whenRedisDropsTheConnection_shouldSubscribeAgainAndDeliverLaterSignals() throws Exception {
        Instance publisher = newInstance();
        Instance subscriber = newInstance();
        awaitSubscribed(publisher, subscriber);
        RecordingChannel rider = RecordingChannel.open(
                subscriber.registry(), 4L, PushRecipient.customer(4L), PushRecipient.rider(11L));
        long subscriptionsBefore = subscriber.subscription().subscriptionCount();

        REDIS.execInContainer("redis-cli", "CLIENT", "KILL", "TYPE", "pubsub");

        Awaitility.await("subscribed again after the connection was killed").atMost(WAIT)
                .until(() -> subscriber.subscription().subscriptionCount() > subscriptionsBefore);
        publisher.signals().publish(PushRecipient.rider(11L), PushTopic.RIDER_DELIVERIES);
        rider.awaitSignal(PushTopic.RIDER_DELIVERIES, WAIT);
    }


    @Test
    void aMalformedMessage_shouldBeIgnored() {
        Instance instance = newInstance();
        awaitSubscribed(instance);
        RecordingChannel customer = RecordingChannel.open(instance.registry(), 5L, PushRecipient.customer(5L));
        StringRedisTemplate template = new StringRedisTemplate(raw);

        template.convertAndSend(CHANNEL, "not json");
        template.convertAndSend(CHANNEL, "{\"recipient\":\"customer:5\",\"topic\":\"orders\"}");
        template.convertAndSend(CHANNEL, "{\"recipient\":\"admin:5\",\"topic\":\"notifications\"}");
        template.convertAndSend(CHANNEL,
                "{\"recipient\":\"customer:5\",\"topic\":\"notifications\",\"added_later\":true}");

        Assertions.assertEquals(SseFrames.signal(PushTopic.NOTIFICATIONS),
                customer.awaitFrame(frame -> true, WAIT), "the only valid message is the first frame written");
    }


    @Test
    void publishing_whenRedisIsUnreachable_shouldFailOpenAndCountTheFailure() throws Exception {
        LettuceConnectionFactory unreachable = connectionFactory("127.0.0.1", unusedPort());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PushMetrics metrics = new PushMetrics(meters);
        RedisPushFanout fanout = new RedisPushFanout(
                new StringRedisTemplate(unreachable), OBJECT_MAPPER, CHANNEL, metrics);
        try {
            PushSignals signals = new PushSignals(fanout, metrics);

            Assertions.assertDoesNotThrow(() -> signals.publish(PushRecipient.customer(1L), PushTopic.NOTIFICATIONS));

            Awaitility.await().atMost(WAIT)
                    .until(() -> meters.get(PushMetrics.PUBLISH_FAILURES).counter().count() == 1.0);
        } finally {
            fanout.destroy();
            unreachable.destroy();
        }
    }


    private Instance newInstance() {
        LettuceConnectionFactory factory = connectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PushMetrics metrics = new PushMetrics(meters);
        PushStreamRegistry registry = new PushStreamRegistry(100, 10, metrics);
        RedisPushFanout fanout = new RedisPushFanout(
                new StringRedisTemplate(factory), OBJECT_MAPPER, CHANNEL, metrics);
        PushSubscription subscription = new PushSubscription(
                factory, registry, OBJECT_MAPPER, CHANNEL, Duration.ofMillis(100), Duration.ofSeconds(1));
        subscription.start();
        Instance instance = new Instance(
                factory, meters, registry, fanout, subscription, new PushSignals(fanout, metrics));
        instances.add(instance);
        return instance;
    }


    private static void awaitSubscribed(Instance... toAwait) {
        for (Instance instance : toAwait) {
            Awaitility.await("subscribed to the push channel").atMost(WAIT)
                    .until(() -> instance.subscription().subscriptionCount() > 0);
        }
    }


    private static LettuceConnectionFactory connectionFactory(String host, int port) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }


    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }


    private record Instance(
            LettuceConnectionFactory factory,
            SimpleMeterRegistry meters,
            PushStreamRegistry registry,
            RedisPushFanout fanout,
            PushSubscription subscription,
            PushSignals signals
    ) {

        double deliveries(String topic) {
            return meters.get(PushMetrics.DELIVERIES).tag("topic", topic).counter().count();
        }


        double signalsPublished(String topic) {
            return meters.get(PushMetrics.SIGNALS).tag("topic", topic).counter().count();
        }


        void stop() throws Exception {
            subscription.destroy();
            fanout.destroy();
            factory.destroy();
        }
    }
}
