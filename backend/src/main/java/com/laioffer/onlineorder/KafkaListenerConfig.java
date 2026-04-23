package com.laioffer.onlineorder;


import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;


import java.time.Duration;


@Configuration
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaListenerConfig {


    @Bean
    @SuppressWarnings("unchecked")
    ConcurrentKafkaListenerContainerFactory<String, String> orderEventKafkaListenerContainerFactory(
            ConsumerFactory<?, ?> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${app.kafka.order-dlt-topic}") String deadLetterTopic,
            @Value("${app.kafka.listener-retries:3}") long retries,
            @Value("${app.kafka.listener-backoff:1s}") Duration backOff
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory((ConsumerFactory<String, String>) consumerFactory);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception ex) -> new TopicPartition(deadLetterTopic, record.partition())
        );
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(backOff.toMillis(), retries)
        );
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
