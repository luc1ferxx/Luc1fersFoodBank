package com.laioffer.onlineorder;


import com.laioffer.onlineorder.messaging.OrderEventDeadLetterConsumer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.messaging.converter.MessageConversionException;


import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;


class DeadLetterListenerErrorHandlerTests {

    private static final TopicPartition DLT_PARTITION = new TopicPartition("order-events.dlt", 0);


    @Test
    void deadLetterErrorHandler_shouldKeepSeekingBackWhileStorageIsDown() {
        DefaultErrorHandler errorHandler = KafkaListenerConfig.deadLetterErrorHandler(Duration.ofMillis(1), Duration.ofMillis(2));
        Consumer<?, ?> consumer = Mockito.mock(Consumer.class);
        MessageListenerContainer container = runningContainer();
        List<ConsumerRecord<?, ?>> records = List.of(dltRecord(5L), dltRecord(6L));
        Exception databaseDown = new ListenerExecutionFailedException(
                "listener failed",
                new DataAccessResourceFailureException("database unavailable")
        );

        // Boot's fallback DefaultErrorHandler gives up after 10 attempts; this one must not.
        int attempts = 25;
        for (int attempt = 0; attempt < attempts; attempt++) {
            Assertions.assertThrows(
                    RuntimeException.class,
                    () -> errorHandler.handleRemaining(databaseDown, records, consumer, container)
            );
        }

        Mockito.verify(consumer, Mockito.times(attempts)).seek(DLT_PARTITION, 5L);
        Mockito.verify(consumer, Mockito.never()).seek(DLT_PARTITION, 6L);
    }


    @Test
    void deadLetterErrorHandler_shouldNotSkipRecordsForExceptionsSpringKafkaTreatsAsFatal() {
        DefaultErrorHandler errorHandler = KafkaListenerConfig.deadLetterErrorHandler(Duration.ofMillis(1), Duration.ofMillis(2));
        Consumer<?, ?> consumer = Mockito.mock(Consumer.class);
        List<ConsumerRecord<?, ?>> records = List.of(dltRecord(5L));
        Exception conversionFailure = new ListenerExecutionFailedException(
                "listener failed",
                new MessageConversionException("unexpected payload")
        );

        Assertions.assertThrows(
                RuntimeException.class,
                () -> errorHandler.handleRemaining(conversionFailure, records, consumer, runningContainer())
        );

        Mockito.verify(consumer).seek(DLT_PARTITION, 5L);
    }


    @Test
    void deadLetterKafkaListenerContainerFactory_shouldApplyTheNonLossyErrorHandler() {
        DefaultKafkaConsumerFactory<String, String> consumerFactory = new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        ));

        ConcurrentKafkaListenerContainerFactory<String, String> factory = new KafkaListenerConfig()
                .deadLetterKafkaListenerContainerFactory(consumerFactory, Duration.ofMillis(1), Duration.ofMillis(2));
        ConcurrentMessageListenerContainer<String, String> container = factory.createContainer("order-events.dlt");
        Consumer<?, ?> consumer = Mockito.mock(Consumer.class);

        CommonErrorHandler errorHandler = container.getCommonErrorHandler();
        Assertions.assertNotNull(errorHandler);
        for (int attempt = 0; attempt < 15; attempt++) {
            Assertions.assertThrows(
                    RuntimeException.class,
                    () -> errorHandler.handleRemaining(
                            new IllegalStateException("database unavailable"),
                            List.of(dltRecord(5L)),
                            consumer,
                            runningContainer()
                    )
            );
        }

        Mockito.verify(consumer, Mockito.times(15)).seek(DLT_PARTITION, 5L);
    }


    @Test
    void deadLetterConsumer_shouldUseTheDedicatedContainerFactory() {
        Method consume = Arrays.stream(OrderEventDeadLetterConsumer.class.getMethods())
                .filter(method -> method.getName().equals("consume"))
                .findFirst()
                .orElseThrow();

        KafkaListener listener = consume.getAnnotation(KafkaListener.class);

        Assertions.assertEquals("deadLetterKafkaListenerContainerFactory", listener.containerFactory());
    }


    private static MessageListenerContainer runningContainer() {
        MessageListenerContainer container = Mockito.mock(MessageListenerContainer.class);
        Mockito.when(container.isRunning()).thenReturn(true);
        return container;
    }


    private static ConsumerRecord<String, String> dltRecord(long offset) {
        return new ConsumerRecord<>(DLT_PARTITION.topic(), DLT_PARTITION.partition(), offset, "order-" + offset, "{}");
    }
}
