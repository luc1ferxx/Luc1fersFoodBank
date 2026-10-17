package com.laioffer.onlineorder.fulfilment.notification;


import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import com.laioffer.onlineorder.messaging.contract.EventContracts;
import com.laioffer.onlineorder.messaging.contract.EventTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


/**
 * Delivery-notification consumer group on the delivery topic; failures are retried and dead-lettered by the shared
 * orderEventKafkaListenerContainerFactory, a record that breaks its contract right away.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryNotificationConsumer {

    private final EventContracts eventContracts;
    private final DeliveryNotificationService deliveryNotificationService;


    public DeliveryNotificationConsumer(
            EventContracts eventContracts,
            DeliveryNotificationService deliveryNotificationService
    ) {
        this.eventContracts = eventContracts;
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
        DeliveryEventEnvelope event =
                eventContracts.read(EventTopic.DELIVERY_EVENTS, payload, DeliveryEventEnvelope.class);
        try {
            deliveryNotificationService.handle(event);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to process delivery event for notifications", ex);
        }
    }
}
