package com.laioffer.onlineorder.fulfilment.timeout;


import com.laioffer.onlineorder.messaging.MerchantEventEnvelope;
import com.laioffer.onlineorder.messaging.contract.EventContracts;
import com.laioffer.onlineorder.messaging.contract.EventTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


/**
 * Timeout module's consumer group on the merchant topic: merchant.order_received sets the accept deadline. Its own
 * group (not the order-topic timeout group), like every consumer; failures are retried and dead-lettered by the shared
 * orderEventKafkaListenerContainerFactory (a record that breaks its contract right away), and a replay goes back to the
 * merchant topic.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class MerchantEventDeadlineConsumer {

    private final EventContracts eventContracts;
    private final OrderDeadlineService orderDeadlineService;


    public MerchantEventDeadlineConsumer(EventContracts eventContracts, OrderDeadlineService orderDeadlineService) {
        this.eventContracts = eventContracts;
        this.orderDeadlineService = orderDeadlineService;
    }


    @KafkaListener(
            id = "order-timeout-merchant",
            topics = "${app.fulfilment.merchant-topic:merchant-events}",
            groupId = "${app.fulfilment.timeout-merchant-group:onlineorder-timeout-merchant}",
            containerFactory = "orderEventKafkaListenerContainerFactory",
            concurrency = "${app.fulfilment.listener-concurrency:${app.kafka.listener-concurrency:3}}"
    )
    public void consume(String payload) {
        MerchantEventEnvelope event =
                eventContracts.read(EventTopic.MERCHANT_EVENTS, payload, MerchantEventEnvelope.class);
        try {
            orderDeadlineService.handle(event);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to process merchant event for the order deadlines", ex);
        }
    }
}
