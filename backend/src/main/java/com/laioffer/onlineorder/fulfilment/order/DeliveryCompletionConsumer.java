package com.laioffer.onlineorder.fulfilment.order;


import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;


/**
 * Order-delivery consumer group on the delivery topic; failures are retried and dead-lettered by the shared
 * orderEventKafkaListenerContainerFactory (to the order DLT, from where a replay goes back to the delivery topic).
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryCompletionConsumer {

    private final ObjectMapper objectMapper;
    private final DeliveryCompletionService deliveryCompletionService;


    public DeliveryCompletionConsumer(ObjectMapper objectMapper, DeliveryCompletionService deliveryCompletionService) {
        this.objectMapper = objectMapper;
        this.deliveryCompletionService = deliveryCompletionService;
    }


    @KafkaListener(
            id = "order-delivery",
            topics = "${app.fulfilment.delivery-topic:delivery-events}",
            groupId = "${app.fulfilment.order-delivery-group:onlineorder-order-delivery}",
            containerFactory = "orderEventKafkaListenerContainerFactory",
            concurrency = "${app.fulfilment.listener-concurrency:${app.kafka.listener-concurrency:3}}"
    )
    public void consume(String payload) {
        try {
            deliveryCompletionService.handle(objectMapper.readValue(payload, DeliveryEventEnvelope.class));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to process delivery event for the order", ex);
        }
    }
}
