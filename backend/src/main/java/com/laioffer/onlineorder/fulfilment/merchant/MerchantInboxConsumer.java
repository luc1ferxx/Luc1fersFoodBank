package com.laioffer.onlineorder.fulfilment.merchant;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


/**
 * Merchant consumer group on the order topic. Failures are retried and then dead-lettered by the shared
 * orderEventKafkaListenerContainerFactory, like every order event consumer.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class MerchantInboxConsumer {

    private final ObjectMapper objectMapper;
    private final MerchantInboxService merchantInboxService;


    public MerchantInboxConsumer(ObjectMapper objectMapper, MerchantInboxService merchantInboxService) {
        this.objectMapper = objectMapper;
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
        try {
            merchantInboxService.handle(objectMapper.readValue(payload, OrderEventEnvelope.class));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to process order event for the merchant inbox", ex);
        }
    }
}
