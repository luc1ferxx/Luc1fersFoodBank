package com.laioffer.onlineorder.fulfilment.dispatch;


import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.contract.EventContracts;
import com.laioffer.onlineorder.messaging.contract.EventTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


/**
 * Dispatch consumer group on the order topic; failures (including "no rider on duty") are retried and then
 * dead-lettered by the shared orderEventKafkaListenerContainerFactory, a record that breaks its contract right away.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class DispatchConsumer {

    private final EventContracts eventContracts;
    private final DispatchService dispatchService;


    public DispatchConsumer(EventContracts eventContracts, DispatchService dispatchService) {
        this.eventContracts = eventContracts;
        this.dispatchService = dispatchService;
    }


    @KafkaListener(
            id = "dispatch",
            topics = "${app.kafka.order-topic}",
            groupId = "${app.fulfilment.dispatch-group:onlineorder-dispatch}",
            containerFactory = "orderEventKafkaListenerContainerFactory",
            concurrency = "${app.fulfilment.listener-concurrency:${app.kafka.listener-concurrency:3}}"
    )
    public void consume(String payload) {
        OrderEventEnvelope event = eventContracts.read(EventTopic.ORDER_EVENTS, payload, OrderEventEnvelope.class);
        try {
            dispatchService.handle(event);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to process order event for dispatch", ex);
        }
    }
}
