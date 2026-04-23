package com.laioffer.onlineorder;


import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class KafkaConfig {


    @Bean
    @ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
    NewTopic orderEventsTopic(
            @Value("${app.kafka.order-topic}") String topicName,
            @Value("${app.kafka.partitions:3}") int partitions,
            @Value("${app.kafka.replicas:1}") short replicas
    ) {
        return new NewTopic(topicName, partitions, replicas);
    }


    @Bean
    @ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
    NewTopic orderEventsDeadLetterTopic(
            @Value("${app.kafka.order-dlt-topic}") String topicName,
            @Value("${app.kafka.partitions:3}") int partitions,
            @Value("${app.kafka.replicas:1}") short replicas
    ) {
        return new NewTopic(topicName, partitions, replicas);
    }
}
