package com.laioffer.onlineorder.aftersales;


import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class AfterSalesKafkaConfig {

    /**
     * After-sales events (aftersales.*). Same partition count as the order topic: its consumers use the shared
     * orderEventKafkaListenerContainerFactory, whose dead-letter recoverer writes a failed record to the same
     * partition number of the order DLT.
     */
    @Bean
    @ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
    NewTopic afterSalesEventsTopic(
            @Value("${app.after-sales.topic:after-sales-events}") String topicName,
            @Value("${app.kafka.partitions:3}") int partitions,
            @Value("${app.kafka.replicas:1}") short replicas
    ) {
        return new NewTopic(topicName, partitions, replicas);
    }
}
