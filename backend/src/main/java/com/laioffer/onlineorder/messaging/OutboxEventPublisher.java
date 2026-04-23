package com.laioffer.onlineorder.messaging;


import com.laioffer.onlineorder.entity.OutboxEventEntity;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;


@Component
@ConditionalOnProperty(name = "app.outbox.publisher-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Duration publishTimeout;
    private final OrderEventOutboxService orderEventOutboxService;
    private final ApplicationMetricsService metricsService;


    public OutboxEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${app.kafka.publish-timeout:5s}") Duration publishTimeout,
            OrderEventOutboxService orderEventOutboxService,
            ApplicationMetricsService metricsService
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.publishTimeout = publishTimeout;
        this.orderEventOutboxService = orderEventOutboxService;
        this.metricsService = metricsService;
    }


    @Scheduled(fixedDelayString = "${app.outbox.publish-interval:3s}")
    public void publishPendingEvents() {
        List<OutboxEventEntity> events = orderEventOutboxService.claimNextBatch();
        for (OutboxEventEntity event : events) {
            try {
                kafkaTemplate.send(event.topic(), event.eventKey(), event.payload())
                        .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
                orderEventOutboxService.markPublished(event.id());
                metricsService.recordOutboxPublishResult(event.topic(), true);
            } catch (Exception ex) {
                orderEventOutboxService.markRetryableFailure(event.id(), ex.getMessage());
                metricsService.recordOutboxPublishResult(event.topic(), false);
                LOGGER.warn("Failed to publish outbox event {} to Kafka topic {}", event.id(), event.topic(), ex);
            }
        }
    }
}
