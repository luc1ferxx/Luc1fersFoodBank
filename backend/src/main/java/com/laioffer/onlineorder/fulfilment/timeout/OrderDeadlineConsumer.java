package com.laioffer.onlineorder.fulfilment.timeout;


import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.contract.EventContracts;
import com.laioffer.onlineorder.messaging.contract.EventTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


/**
 * Timeout consumer group on the order topic; failures are retried and dead-lettered by the shared
 * orderEventKafkaListenerContainerFactory, a record that breaks its contract right away.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class OrderDeadlineConsumer {

    private final EventContracts eventContracts;
    private final OrderDeadlineService orderDeadlineService;


    public OrderDeadlineConsumer(EventContracts eventContracts, OrderDeadlineService orderDeadlineService) {
        this.eventContracts = eventContracts;
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
        OrderEventEnvelope event = eventContracts.read(EventTopic.ORDER_EVENTS, payload, OrderEventEnvelope.class);
        try {
            orderDeadlineService.handle(event);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to process order event for the order deadlines", ex);
        }
    }
}
