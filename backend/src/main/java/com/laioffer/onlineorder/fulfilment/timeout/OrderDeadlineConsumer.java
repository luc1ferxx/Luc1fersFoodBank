package com.laioffer.onlineorder.fulfilment.timeout;


import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;


/**
 * Timeout consumer group on the order topic; failures are retried and dead-lettered by the shared
 * orderEventKafkaListenerContainerFactory.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class OrderDeadlineConsumer {

    private final ObjectMapper objectMapper;
    private final OrderDeadlineService orderDeadlineService;


    public OrderDeadlineConsumer(ObjectMapper objectMapper, OrderDeadlineService orderDeadlineService) {
        this.objectMapper = objectMapper;
        this.orderDeadlineService = orderDeadlineService;
    }


    @KafkaListener(
            id = "order-timeout",
            topics = "${app.kafka.order-topic}",
            groupId = "${app.fulfilment.timeout-group:onlineorder-timeout}",
            containerFactory = "orderEventKafkaListenerContainerFactory",
            concurrency = "${app.fulfilment.listener-concurrency:${app.kafka.listener-concurrency:3}}"
    )
    public void consume(String payload) {
        try {
            orderDeadlineService.handle(objectMapper.readValue(payload, OrderEventEnvelope.class));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to process order event for the order deadlines", ex);
        }
    }
}
