package com.laioffer.onlineorder.messaging;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.service.OrderEventHandlerService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class OrderEventConsumer {

    private final ObjectMapper objectMapper;
    private final OrderEventHandlerService orderEventHandlerService;


    public OrderEventConsumer(
            ObjectMapper objectMapper,
            OrderEventHandlerService orderEventHandlerService
    ) {
        this.objectMapper = objectMapper;
        this.orderEventHandlerService = orderEventHandlerService;
    }


    @KafkaListener(
            topics = "${app.kafka.order-topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "orderEventKafkaListenerContainerFactory"
    )
    public void consume(String payload) {
        try {
            OrderEventEnvelope event = objectMapper.readValue(payload, OrderEventEnvelope.class);
            orderEventHandlerService.handle(event);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to process order event payload", ex);
        }
    }
}
