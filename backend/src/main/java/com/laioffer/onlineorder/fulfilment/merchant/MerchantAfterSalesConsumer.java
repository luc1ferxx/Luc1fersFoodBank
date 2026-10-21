package com.laioffer.onlineorder.fulfilment.merchant;


import com.laioffer.onlineorder.messaging.AfterSalesEventEnvelope;
import com.laioffer.onlineorder.messaging.contract.EventContracts;
import com.laioffer.onlineorder.messaging.contract.EventTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


/**
 * Merchant after-sales consumer group on the after-sales topic (ADR 0002 section 10.3). Failures are retried and then
 * dead-lettered by the shared orderEventKafkaListenerContainerFactory, like every order event consumer; a record that
 * breaks its contract is dead-lettered right away.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class MerchantAfterSalesConsumer {

    private final EventContracts eventContracts;
    private final MerchantAfterSalesInboxService inboxService;


    public MerchantAfterSalesConsumer(EventContracts eventContracts, MerchantAfterSalesInboxService inboxService) {
        this.eventContracts = eventContracts;
        this.inboxService = inboxService;
    }


    @KafkaListener(
            id = "merchant-aftersales",
            topics = "${app.after-sales.topic:after-sales-events}",
            groupId = "${app.after-sales.merchant-group:onlineorder-merchant-aftersales}",
            containerFactory = "orderEventKafkaListenerContainerFactory",
            concurrency = "${app.fulfilment.listener-concurrency:${app.kafka.listener-concurrency:3}}"
    )
    public void consume(String payload) {
        AfterSalesEventEnvelope event =
                eventContracts.read(EventTopic.AFTER_SALES_EVENTS, payload, AfterSalesEventEnvelope.class);
        try {
            inboxService.handle(event);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to process after-sales event for the merchant inbox", ex);
        }
    }
}
