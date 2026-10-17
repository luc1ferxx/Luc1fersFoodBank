package com.laioffer.onlineorder.messaging;


import com.laioffer.onlineorder.messaging.contract.EventContracts;
import com.laioffer.onlineorder.messaging.contract.EventTopic;
import com.laioffer.onlineorder.service.OrderEventHandlerService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


/**
 * Customer notifications for order events (consumer group app.kafka consumer group-id). A record that breaks its
 * contract is dead-lettered without retries ({@link EventContracts#read}).
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class OrderEventConsumer {

    private final EventContracts eventContracts;
    private final OrderEventHandlerService orderEventHandlerService;


    public OrderEventConsumer(
            EventContracts eventContracts,
            OrderEventHandlerService orderEventHandlerService
    ) {
        this.eventContracts = eventContracts;
        this.orderEventHandlerService = orderEventHandlerService;
    }


    @KafkaListener(
            topics = "${app.kafka.order-topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "orderEventKafkaListenerContainerFactory"
    )
    public void consume(String payload) {
        OrderEventEnvelope event = eventContracts.read(EventTopic.ORDER_EVENTS, payload, OrderEventEnvelope.class);
        try {
            orderEventHandlerService.handle(event);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to process order event payload", ex);
        }
    }
}
