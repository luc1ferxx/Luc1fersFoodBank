package com.laioffer.onlineorder.fulfilment.notification;


import com.laioffer.onlineorder.messaging.AfterSalesEventEnvelope;
import com.laioffer.onlineorder.messaging.contract.EventContracts;
import com.laioffer.onlineorder.messaging.contract.EventTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


/**
 * After-sales notification consumer group on the after-sales topic; failures are retried and dead-lettered by the
 * shared orderEventKafkaListenerContainerFactory, a record that breaks its contract right away.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class AfterSalesNotificationConsumer {

    private final EventContracts eventContracts;
    private final AfterSalesNotificationService afterSalesNotificationService;


    public AfterSalesNotificationConsumer(
            EventContracts eventContracts,
            AfterSalesNotificationService afterSalesNotificationService
    ) {
        this.eventContracts = eventContracts;
        this.afterSalesNotificationService = afterSalesNotificationService;
    }


    @KafkaListener(
            id = "aftersales-notification",
            topics = "${app.after-sales.topic:after-sales-events}",
            groupId = "${app.after-sales.notification-group:onlineorder-aftersales-notification}",
            containerFactory = "orderEventKafkaListenerContainerFactory",
            concurrency = "${app.fulfilment.listener-concurrency:${app.kafka.listener-concurrency:3}}"
    )
    public void consume(String payload) {
        AfterSalesEventEnvelope event =
                eventContracts.read(EventTopic.AFTER_SALES_EVENTS, payload, AfterSalesEventEnvelope.class);
        try {
            afterSalesNotificationService.handle(event);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to process after-sales event for notifications", ex);
        }
    }
}
