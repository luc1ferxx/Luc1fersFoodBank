package com.laioffer.onlineorder.push;


import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
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
 * subscription comes back after Redis drops its connection, and a publish to an unreachable Redis fails open. Each
 * instance's probe proves its own subscription ({@code onlineorder.push.subscribed}): 1 once the probe came back,
 * 0 while Redis hangs, 1 again afterwards, and another instance's probe changes nothing.
 */
@Testcontainers(disabledWithoutDocker = true)
class PushRedisFanoutIntegrationTests {

    private static final String CHANNEL = "onlineorder:push:v1";
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final Duration PROBE_INTERVAL = Duration.ofMillis(200);
    /** Short enough for a probe publish to a hung Redis to fail within a few probe intervals. */
    private static final Duration SHORT_COMMAND_TIMEOUT = Duration.ofSeconds(1);
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    @Container
    static GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory raw;

    private final List<Instance> instances = new ArrayList<>();
    private Logger subscriptionLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> captured;


    @BeforeAll
    static void connect() {
        raw = connectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
    }


    @AfterAll
    static void disconnect() {
        raw.destroy();
    }


    @BeforeEach
    void captureSubscriptionLog() {
        subscriptionLogger = (Logger) LoggerFactory.getLogger(PushSubscription.class);
        originalLevel = subscriptionLogger.getLevel();
        subscriptionLogger.setLevel(Level.DEBUG);
        captured = new ListAppender<>();
        captured.start();
        subscriptionLogger.addAppender(captured);
    }


    @AfterEach
    void stopInstances() throws Exception {
        for (Instance instance : instances) {
            instance.stop();
        }
        subscriptionLogger.detachAppender(captured);
        captured.stop();
        subscriptionLogger.setLevel(originalLevel);
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
    void theSubscribedGauge_shouldReadOneOnEveryInstanceOnceItsOwnProbeCameBack() {
        Instance first = newInstance();
        Instance second = newInstance();
        awaitSubscribed(first, second);

        awaitReceiving(first, second);

        Assertions.assertEquals(1.0, first.subscribed());
        Assertions.assertEquals(1.0, second.subscribed());
        Assertions.assertTrue(first.subscription().isReceiving() && second.subscription().isReceiving());
        // The probe channel's confirmation arrived before the probe did; only the signal channel's is counted.
        Assertions.assertEquals(1L, first.subscription().subscriptionCount());
        Assertions.assertEquals(1L, second.subscription().subscriptionCount());
        Assertions.assertEquals(2L, captured.list.stream()
                .filter(event -> event.getFormattedMessage().equals("Subscribed to push channel " + CHANNEL)).count(),
                "one subscription log line per instance");
    }


    @Test
    void anotherInstancesProbe_shouldNotMakeAnInstanceWhoseOwnProbeCannotBePublishedReceiving() {
        // Subscribed on the container, but its probes go to an unreachable Redis: only its own probe may make it
        // receiving, and those never come back.
        LettuceConnectionFactory unreachable = connectionFactory("127.0.0.1", unusedPort());
        try {
            Instance broken = newInstance(connectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort()), unreachable);
            Instance healthy = newInstance();
            awaitSubscribed(broken, healthy);
            awaitReceiving(healthy);

            // Five probe rounds of the healthy instance land on the broken one.
            Awaitility.await("the broken instance never reads another instance's probe as its own")
                    .during(Duration.ofSeconds(1)).atMost(WAIT)
                    .until(() -> broken.subscribed() == 0.0 && !broken.subscription().isReceiving());
            Assertions.assertEquals(1L, captured.list.stream()
                    .filter(event -> event.getFormattedMessage().startsWith("Push subscription confirmed")).count(),
                    "only the healthy instance's probe came back");
        } finally {
            unreachable.destroy();
        }
    }


    @Test
    void anotherInstancesProbe_shouldBeIgnoredWithoutAWarning() {
        Instance first = newInstance();
        Instance second = newInstance();
        awaitSubscribed(first, second);
        awaitReceiving(first, second);

        // Five probe rounds of each instance land on the other; neither reads them as its own or as a signal.
        Awaitility.await("both gauges stay at 1 while the instances probe").during(Duration.ofSeconds(1)).atMost(WAIT)
                .until(() -> first.subscribed() == 1.0 && second.subscribed() == 1.0);

        Assertions.assertEquals(List.of(), messagesAtLeast(Level.WARN), "no warning for another instance's probe");
        Assertions.assertEquals(0L, captured.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("Push subscription restored")).count());
    }


    @Test
    void theSubscribedGauge_whenRedisHangs_shouldDropToZeroAndComeBackWithTheSignalsAfterTheUnpause() {
        Instance publisher = newInstance(SHORT_COMMAND_TIMEOUT);
        Instance subscriber = newInstance(SHORT_COMMAND_TIMEOUT);
        awaitSubscribed(publisher, subscriber);
        awaitReceiving(publisher, subscriber);
        RecordingChannel rider = RecordingChannel.open(
                subscriber.registry(), 4L, PushRecipient.customer(4L), PushRecipient.rider(11L));

        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            Awaitility.await("both gauges at 0 while Redis hangs").atMost(WAIT)
                    .until(() -> publisher.subscribed() == 0.0 && subscriber.subscribed() == 0.0);
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }

        awaitReceiving(publisher, subscriber);
        publisher.signals().publish(PushRecipient.rider(11L), PushTopic.RIDER_DELIVERIES);
        rider.awaitSignal(PushTopic.RIDER_DELIVERIES, WAIT);
        List<String> warnings = messagesAtLeast(Level.WARN);
        Assertions.assertEquals(2, warnings.size(), "one warning per instance: " + warnings);
        Assertions.assertTrue(warnings.stream().allMatch(message -> message.startsWith(
                "Push subscription lost on channel " + CHANNEL + ": this instance's probe did not come back within "
                        + PROBE_INTERVAL)), warnings.toString());
        Assertions.assertEquals(2, captured.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .filter(event -> event.getFormattedMessage().equals(
                        "Push subscription restored on channel " + CHANNEL + ": the probe came back"))
                .count(), "one restore per instance");
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
    void aMalformedProbe_shouldBeIgnoredWithoutAWarning() {
        Instance instance = newInstance();
        awaitSubscribed(instance);
        awaitReceiving(instance);
        RecordingChannel customer = RecordingChannel.open(instance.registry(), 5L, PushRecipient.customer(5L));
        StringRedisTemplate template = new StringRedisTemplate(raw);
        String probeChannel = CHANNEL + PushSubscription.PROBE_CHANNEL_SUFFIX;

        template.convertAndSend(probeChannel, "not json");
        template.convertAndSend(probeChannel, "{\"seq\":1}");
        template.convertAndSend(probeChannel, "{\"probe\":\"another-instance\",\"seq\":1,\"added_later\":true}");
        // The signal channel's messages are still read as signals, so this one is the first frame written.
        template.convertAndSend(CHANNEL, "{\"recipient\":\"customer:5\",\"topic\":\"notifications\"}");

        Assertions.assertEquals(SseFrames.signal(PushTopic.NOTIFICATIONS), customer.awaitFrame(frame -> true, WAIT));
        Assertions.assertEquals(List.of(), messagesAtLeast(Level.WARN), "probes never warn like a malformed signal");
        Assertions.assertEquals(1.0, instance.subscribed());
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
        return newInstance(null);
    }


    /**
     * @param commandTimeout the Lettuce command timeout of the instance's connections; null for Lettuce's default
     */
    private Instance newInstance(Duration commandTimeout) {
        LettuceConnectionFactory factory = commandTimeout == null
                ? connectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort())
                : connectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort(), commandTimeout);
        return newInstance(factory, factory);
    }


    /**
     * @param subscriptionFactory the connections of the subscription (signals and probes in); stopped with the instance
     * @param publishFactory      the connections the signals and probes are published through
     */
    private Instance newInstance(LettuceConnectionFactory subscriptionFactory, LettuceConnectionFactory publishFactory) {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PushMetrics metrics = new PushMetrics(meters);
        PushStreamRegistry registry = new PushStreamRegistry(100, 10, metrics);
        StringRedisTemplate template = new StringRedisTemplate(publishFactory);
        RedisPushFanout fanout = new RedisPushFanout(template, OBJECT_MAPPER, CHANNEL, metrics);
        PushSubscription subscription = new PushSubscription(
                subscriptionFactory, template, registry, metrics, OBJECT_MAPPER, CHANNEL, PROBE_INTERVAL,
                Duration.ofMillis(100), Duration.ofSeconds(1));
        subscription.start();
        Instance instance = new Instance(
                subscriptionFactory, meters, registry, fanout, subscription, new PushSignals(fanout, metrics));
        instances.add(instance);
        return instance;
    }


    private static void awaitSubscribed(Instance... toAwait) {
        for (Instance instance : toAwait) {
            Awaitility.await("subscribed to the push channel").atMost(WAIT)
                    .until(() -> instance.subscription().subscriptionCount() > 0);
        }
    }


    private static void awaitReceiving(Instance... toAwait) {
        for (Instance instance : toAwait) {
            Awaitility.await("the instance's own probe came back").atMost(WAIT)
                    .until(() -> instance.subscribed() == 1.0);
        }
    }


    private List<String> messagesAtLeast(Level level) {
        return captured.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(level))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }


    private static LettuceConnectionFactory connectionFactory(String host, int port) {
        return connectionFactory(new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port)));
    }


    private static LettuceConnectionFactory connectionFactory(String host, int port, Duration commandTimeout) {
        LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                .commandTimeout(commandTimeout)
                .build();
        return connectionFactory(
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), clientConfiguration));
    }


    private static LettuceConnectionFactory connectionFactory(LettuceConnectionFactory factory) {
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


        double subscribed() {
            return meters.get(PushMetrics.SUBSCRIBED).gauge().value();
        }


        void stop() throws Exception {
            subscription.destroy();
            fanout.destroy();
            factory.destroy();
        }
    }
}
