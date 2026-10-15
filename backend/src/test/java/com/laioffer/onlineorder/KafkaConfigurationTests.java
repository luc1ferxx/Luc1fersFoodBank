package com.laioffer.onlineorder;


import com.laioffer.onlineorder.messaging.OrderEventConsumer;
import com.laioffer.onlineorder.messaging.OrderEventDeadLetterConsumer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOffExecution;


import java.lang.reflect.Method;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;


class KafkaConfigurationTests {

    private static final TopicPartition ORDER_PARTITION = new TopicPartition("order-events", 0);

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


    @Test
    void orderListener_shouldKeepRetryingWhileNoPooledConnectionIsAvailable() {
        KafkaTemplate<String, String> kafkaTemplate = deadLetterTemplate();
        contextRunner
                .withBean(KafkaTemplate.class, () -> kafkaTemplate)
                .withPropertyValues(
                        "KAFKA_LISTENER_BACKOFF=1ms",
                        "KAFKA_LISTENER_TRANSIENT_BACKOFF_MAX=1ms",
                        "KAFKA_LISTENER_TRANSIENT_RETRY_TIMEOUT=40ms"
                )
                .run(context -> {
                    CommonErrorHandler errorHandler = orderEventErrorHandler(context);
                    Consumer<?, ?> consumer = Mockito.mock(Consumer.class);
                    // What OrderEventConsumer throws when @Transactional times out on a saturated Hikari pool.
                    Exception poolExhausted = listenerFailure(new CannotCreateTransactionException(
                            "Could not open JDBC Connection for transaction",
                            new SQLTransientConnectionException("onlineorder-db - Connection is not available, request timed out after 2000ms.")
                    ));

                    // listener-retries (3) alone would dead-letter the event on the 4th failure.
                    int attempts = 20;
                    for (int attempt = 0; attempt < attempts; attempt++) {
                        Assertions.assertThrows(
                                RuntimeException.class,
                                () -> errorHandler.handleRemaining(poolExhausted, List.of(orderRecord()), consumer, runningContainer())
                        );
                    }
                    Mockito.verify(consumer, Mockito.times(attempts)).seek(ORDER_PARTITION, 7L);
                    Mockito.verify(kafkaTemplate, Mockito.never()).send(Mockito.<ProducerRecord<String, String>>any());

                    // Once the transient retry budget is spent, the event still goes to the DLT.
                    Assertions.assertTrue(handleUntilRecovered(errorHandler, poolExhausted, consumer, 100) > 0);
                    Mockito.verify(kafkaTemplate).send(Mockito.<ProducerRecord<String, String>>any());
                });
    }


    @Test
    void orderListener_shouldStillDeadLetterOtherFailuresAfterTheListenerRetries() {
        KafkaTemplate<String, String> kafkaTemplate = deadLetterTemplate();
        contextRunner
                .withBean(KafkaTemplate.class, () -> kafkaTemplate)
                .withPropertyValues("KAFKA_LISTENER_BACKOFF=1ms")
                .run(context -> {
                    CommonErrorHandler errorHandler = orderEventErrorHandler(context);
                    Exception invalidEvent = listenerFailure(new IllegalArgumentException("Event id is required"));

                    // Three retries, then the fourth failure is dead-lettered.
                    Assertions.assertEquals(4, handleUntilRecovered(errorHandler, invalidEvent, Mockito.mock(Consumer.class), 100));
                    Mockito.verify(kafkaTemplate).send(Mockito.<ProducerRecord<String, String>>any());
                });
    }


    @Test
    void transientDatabaseFailures_shouldGetAMinutesLongRetryBudgetByDefault() {
        Assertions.assertTrue(KafkaListenerConfig.isTransientDatabaseFailure(listenerFailure(
                new CannotAcquireLockException("canceling statement due to lock timeout")
        )));
        Assertions.assertTrue(KafkaListenerConfig.isTransientDatabaseFailure(listenerFailure(
                new QueryTimeoutException("canceling statement due to statement timeout")
        )));
        Assertions.assertTrue(KafkaListenerConfig.isTransientDatabaseFailure(listenerFailure(
                new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection")
        )));
        Assertions.assertFalse(KafkaListenerConfig.isTransientDatabaseFailure(listenerFailure(
                new DuplicateKeyException("duplicate key value violates unique constraint")
        )));

        contextRunner.run(context -> {
            Binder binder = Binder.get(context.getEnvironment());
            Duration backOff = binder.bind("app.kafka.listener-backoff", Duration.class).get();
            Duration maxBackOff = binder.bind("app.kafka.listener-transient-backoff-max", Duration.class).get();
            Duration retryTimeout = binder.bind("app.kafka.listener-transient-retry-timeout", Duration.class).get();
            Assertions.assertEquals(Duration.ofSeconds(30), maxBackOff);
            Assertions.assertEquals(Duration.ofMinutes(5), retryTimeout);

            BackOffExecution execution = KafkaListenerConfig.transientDatabaseBackOff(backOff, maxBackOff, retryTimeout).start();
            long waitedMillis = 0;
            for (long interval = execution.nextBackOff(); interval != BackOffExecution.STOP; interval = execution.nextBackOff()) {
                // The consumer sleeps between attempts, so no single wait may approach max.poll.interval.ms.
                Assertions.assertTrue(interval <= maxBackOff.toMillis());
                waitedMillis += interval;
            }
            Assertions.assertTrue(waitedMillis >= retryTimeout.toMillis());
        });
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


    private static CommonErrorHandler orderEventErrorHandler(ApplicationContext context) {
        CommonErrorHandler errorHandler = context
                .getBean("orderEventKafkaListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class)
                .createContainer("order-events")
                .getCommonErrorHandler();
        Assertions.assertNotNull(errorHandler);
        return errorHandler;
    }


    /**
     * Redelivers the failed record until the error handler recovers it (publishes it to the DLT) and returns
     * the number of failed deliveries that took.
     */
    private static int handleUntilRecovered(
            CommonErrorHandler errorHandler,
            Exception failure,
            Consumer<?, ?> consumer,
            int maxAttempts
    ) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                errorHandler.handleRemaining(failure, List.of(orderRecord()), consumer, runningContainer());
                return attempt;
            } catch (RuntimeException retried) {
                // Not recovered yet: the handler seeks back so the record is redelivered.
            }
        }
        return -1;
    }


    private static Exception listenerFailure(Exception cause) {
        return new ListenerExecutionFailedException(
                "Listener method threw exception",
                new IllegalStateException("Unable to process order event payload", cause)
        );
    }


    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> deadLetterTemplate() {
        KafkaTemplate<String, String> kafkaTemplate = Mockito.mock(KafkaTemplate.class);
        Mockito.when(kafkaTemplate.send(Mockito.<ProducerRecord<String, String>>any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        return kafkaTemplate;
    }


    private static MessageListenerContainer runningContainer() {
        MessageListenerContainer container = Mockito.mock(MessageListenerContainer.class);
        Mockito.when(container.isRunning()).thenReturn(true);
        return container;
    }


    private static ConsumerRecord<String, String> orderRecord() {
        return new ConsumerRecord<>(ORDER_PARTITION.topic(), ORDER_PARTITION.partition(), 7L, "order-7", "{}");
    }


    private static KafkaListener kafkaListener(Class<?> consumerType) {
        Method consume = Arrays.stream(consumerType.getMethods())
                .filter(method -> method.getName().equals("consume"))
                .findFirst()
                .orElseThrow();
        return consume.getAnnotation(KafkaListener.class);
    }
}
