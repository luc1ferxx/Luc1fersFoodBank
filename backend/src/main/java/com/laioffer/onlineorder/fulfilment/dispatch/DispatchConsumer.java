package com.laioffer.onlineorder.fulfilment.dispatch;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


/**
 * Dispatch consumer group on the order topic; failures (including "no active rider") are retried and then
 * dead-lettered by the shared orderEventKafkaListenerContainerFactory.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class DispatchConsumer {

    private final ObjectMapper objectMapper;
    private final DispatchService dispatchService;


    public DispatchConsumer(ObjectMapper objectMapper, DispatchService dispatchService) {
        this.objectMapper = objectMapper;
        this.dispatchService = dispatchService;
    }


    @KafkaListener(
            id = "dispatch",
            topics = "${app.kafka.order-topic}",
            groupId = "${app.fulfilment.dispatch-group:onlineorder-dispatch}",
            containerFactory = "orderEventKafkaListenerContainerFactory"
    )
    public void consume(String payload) {
        try {
            dispatchService.handle(objectMapper.readValue(payload, OrderEventEnvelope.class));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to process order event for dispatch", ex);
        }
    }
}
