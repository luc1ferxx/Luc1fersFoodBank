package com.laioffer.onlineorder.fulfilment.notification;


import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;


/**
 * Delivery-notification consumer group on the delivery topic; failures are retried and dead-lettered by the shared
 * orderEventKafkaListenerContainerFactory.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryNotificationConsumer {

    private final ObjectMapper objectMapper;
    private final DeliveryNotificationService deliveryNotificationService;


    public DeliveryNotificationConsumer(
            ObjectMapper objectMapper,
            DeliveryNotificationService deliveryNotificationService
    ) {
        this.objectMapper = objectMapper;
        this.deliveryNotificationService = deliveryNotificationService;
    }


    @KafkaListener(
            id = "delivery-notification",
            topics = "${app.fulfilment.delivery-topic:delivery-events}",
            groupId = "${app.fulfilment.notification-delivery-group:onlineorder-notification-delivery}",
            containerFactory = "orderEventKafkaListenerContainerFactory",
            concurrency = "${app.fulfilment.listener-concurrency:${app.kafka.listener-concurrency:3}}"
    )
    public void consume(String payload) {
        try {
            deliveryNotificationService.handle(objectMapper.readValue(payload, DeliveryEventEnvelope.class));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to process delivery event for notifications", ex);
        }
    }
}
