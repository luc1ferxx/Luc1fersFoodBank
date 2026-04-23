package com.laioffer.onlineorder.messaging;


import com.laioffer.onlineorder.service.DeadLetterProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;


@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class OrderEventDeadLetterConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderEventDeadLetterConsumer.class);

    private final DeadLetterProcessingService deadLetterProcessingService;


    public OrderEventDeadLetterConsumer(DeadLetterProcessingService deadLetterProcessingService) {
        this.deadLetterProcessingService = deadLetterProcessingService;
    }


    @KafkaListener(topics = "${app.kafka.order-dlt-topic}", groupId = "${app.kafka.order-dlt-group}")
    public void consume(
            String payload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String deadLetterTopic,
            @Header(name = KafkaHeaders.DLT_ORIGINAL_TOPIC, required = false) String sourceTopic,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String messageKey,
            @Header(name = KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false) String errorMessage
    ) {
        deadLetterProcessingService.handle(sourceTopic, deadLetterTopic, messageKey, payload, errorMessage);
        LOGGER.warn("Stored dead-letter order event for key {} from topic {}", messageKey, sourceTopic);
    }
}
