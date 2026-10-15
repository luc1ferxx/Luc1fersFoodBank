package com.laioffer.onlineorder;


import com.laioffer.onlineorder.messaging.OrderEventConsumer;
import com.laioffer.onlineorder.messaging.OrderEventDeadLetterConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;


import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;


class KafkaConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            // SpringApplication registers this so @Value can bind durations such as 1s.
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(KafkaListenerConfig.class);


    @Test
    void producerFactory_shouldUseAnIdempotentProducerWithBoundedTimeouts() {
        contextRunner.run(context -> {
            Map<String, Object> producerConfig = producerConfig(context);

            Assertions.assertEquals("all", producerConfig.get(ProducerConfig.ACKS_CONFIG));
            Assertions.assertEquals("true", producerConfig.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
            Assertions.assertEquals("5", producerConfig.get(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION));
            Assertions.assertEquals("5000", producerConfig.get(ProducerConfig.MAX_BLOCK_MS_CONFIG));
            Assertions.assertEquals("5", producerConfig.get(ProducerConfig.LINGER_MS_CONFIG));
            Assertions.assertEquals("5000", producerConfig.get(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG));
            Assertions.assertEquals("10000", producerConfig.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG));
            // Retries are bounded by delivery.timeout.ms instead of a retry count.
            Assertions.assertFalse(producerConfig.containsKey(ProducerConfig.RETRIES_CONFIG));

            // Kafka rejects idempotence combined with acks other than all or more than 5 in-flight requests.
            ProducerConfig parsed = new ProducerConfig(producerConfig);
            int deliveryTimeoutMs = parsed.getInt(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG);
            Assertions.assertTrue(
                    deliveryTimeoutMs >= parsed.getLong(ProducerConfig.LINGER_MS_CONFIG)
                            + parsed.getInt(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG),
                    "Kafka requires delivery.timeout.ms >= linger.ms + request.timeout.ms"
            );

            Duration publishTimeout = Binder.get(context.getEnvironment())
                    .bind("app.kafka.publish-timeout", Duration.class)
                    .get();
            Assertions.assertEquals(Duration.ofSeconds(12), publishTimeout);
            Assertions.assertTrue(
                    publishTimeout.toMillis() >= deliveryTimeoutMs,
                    "The outbox must not give up on a record the producer may still deliver"
            );
        });
    }


    @Test
    void producerSettings_shouldBeOverridableFromTheEnvironment() {
        contextRunner
                .withPropertyValues(
                        "KAFKA_PRODUCER_LINGER_MS=20",
                        "KAFKA_PRODUCER_DELIVERY_TIMEOUT_MS=30000",
                        "KAFKA_PUBLISH_TIMEOUT=35s"
                )
                .run(context -> {
                    Map<String, Object> producerConfig = producerConfig(context);

                    Assertions.assertEquals("20", producerConfig.get(ProducerConfig.LINGER_MS_CONFIG));
                    Assertions.assertEquals("30000", producerConfig.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG));
                    Assertions.assertEquals(
                            Duration.ofSeconds(35),
                            Binder.get(context.getEnvironment()).bind("app.kafka.publish-timeout", Duration.class).get()
                    );
                });
    }


    @Test
    void listenerContainerFactories_shouldRunOneConsumerPerPartition() {
        contextRunner.run(context -> {
            assertConcurrency(context, "orderEventKafkaListenerContainerFactory", 3);
            assertConcurrency(context, "deadLetterKafkaListenerContainerFactory", 3);
        });
    }


    @Test
    void listenerConcurrency_shouldFollowThePartitionCountUnlessOverridden() {
        contextRunner
                .withPropertyValues("KAFKA_ORDER_TOPIC_PARTITIONS=6")
                .run(context -> {
                    assertConcurrency(context, "orderEventKafkaListenerContainerFactory", 6);
                    assertConcurrency(context, "deadLetterKafkaListenerContainerFactory", 6);
                });

        contextRunner
                .withPropertyValues("KAFKA_ORDER_TOPIC_PARTITIONS=6", "KAFKA_LISTENER_CONCURRENCY=2")
                .run(context -> {
                    assertConcurrency(context, "orderEventKafkaListenerContainerFactory", 2);
                    assertConcurrency(context, "deadLetterKafkaListenerContainerFactory", 2);
                });
    }


    @Test
    void kafkaListeners_shouldNotOverrideTheFactoryConcurrency() {
        Assertions.assertEquals("", kafkaListener(OrderEventConsumer.class).concurrency());
        Assertions.assertEquals("", kafkaListener(OrderEventDeadLetterConsumer.class).concurrency());
    }


    private static Map<String, Object> producerConfig(ApplicationContext context) {
        ProducerFactory<?, ?> producerFactory = context.getBean(ProducerFactory.class);
        return producerFactory.getConfigurationProperties();
    }


    private static void assertConcurrency(ApplicationContext context, String factoryName, int expected) {
        ConcurrentKafkaListenerContainerFactory<?, ?> factory =
                context.getBean(factoryName, ConcurrentKafkaListenerContainerFactory.class);
        ConcurrentMessageListenerContainer<?, ?> container = factory.createContainer("order-events");

        Assertions.assertEquals(expected, container.getConcurrency(), factoryName);
    }


    private static KafkaListener kafkaListener(Class<?> consumerType) {
        Method consume = Arrays.stream(consumerType.getMethods())
                .filter(method -> method.getName().equals("consume"))
                .findFirst()
                .orElseThrow();
        return consume.getAnnotation(KafkaListener.class);
    }
}
