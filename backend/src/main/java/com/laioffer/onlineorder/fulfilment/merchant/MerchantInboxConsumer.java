package com.laioffer.onlineorder.fulfilment.merchant;


import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.contract.EventContracts;
import com.laioffer.onlineorder.messaging.contract.EventTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


/**
 * Merchant consumer group on the order topic. Failures are retried and then dead-lettered by the shared
 * orderEventKafkaListenerContainerFactory, like every order event consumer; a record that breaks its contract is
 * dead-lettered right away.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class MerchantInboxConsumer {

    private final EventContracts eventContracts;
    private final MerchantInboxService merchantInboxService;


    public MerchantInboxConsumer(EventContracts eventContracts, MerchantInboxService merchantInboxService) {
        this.eventContracts = eventContracts;
        this.merchantInboxService = merchantInboxService;
    }


    @KafkaListener(
            id = "merchant-inbox",
            topics = "${app.kafka.order-topic}",
            groupId = "${app.fulfilment.merchant-group:onlineorder-merchant}",
            containerFactory = "orderEventKafkaListenerContainerFactory",
            concurrency = "${app.fulfilment.listener-concurrency:${app.kafka.listener-concurrency:3}}"
    )
    public void consume(String payload) {
        OrderEventEnvelope event = eventContracts.read(EventTopic.ORDER_EVENTS, payload, OrderEventEnvelope.class);
        try {
            merchantInboxService.handle(event);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to process order event for the merchant inbox", ex);
        }
    }
}
