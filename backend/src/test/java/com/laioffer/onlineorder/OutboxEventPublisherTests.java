package com.laioffer.onlineorder;


import com.laioffer.onlineorder.entity.OutboxEventEntity;
import com.laioffer.onlineorder.messaging.OutboxEventPublisher;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;


import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;


@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTests {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private OrderEventOutboxService orderEventOutboxService;

    @Mock
    private ApplicationMetricsService metricsService;


    @Test
    void publishPendingEvents_shouldMarkEventPublishedAfterKafkaSend() {
        OutboxEventEntity event = new OutboxEventEntity(
                1L,
                "ORDER",
                5L,
                "evt-1",
                "order-events",
                "5",
                "order.created",
                "{\"order_id\":5}",
                "PENDING",
                0,
                null,
                LocalDateTime.of(2026, 4, 4, 12, 0),
                LocalDateTime.of(2026, 4, 4, 12, 0),
                null
        );
        OutboxEventPublisher publisher = new OutboxEventPublisher(
                kafkaTemplate,
                Duration.ofSeconds(5),
                orderEventOutboxService,
                metricsService
        );

        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(event));
        Mockito.when(kafkaTemplate.send(event.topic(), event.eventKey(), event.payload()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService).markPublished(event.id());
        Mockito.verify(metricsService).recordOutboxPublishResult(event.topic(), true);
        Mockito.verify(orderEventOutboxService, Mockito.never()).markRetryableFailure(Mockito.anyLong(), Mockito.anyString());
    }


    @Test
    void publishPendingEvents_shouldReturnEventToPendingWhenKafkaSendFails() {
        OutboxEventEntity event = new OutboxEventEntity(
                2L,
                "ORDER",
                6L,
                "evt-2",
                "order-events",
                "6",
                "order.paid",
                "{\"order_id\":6}",
                "PENDING",
                1,
                null,
                LocalDateTime.of(2026, 4, 4, 12, 0),
                LocalDateTime.of(2026, 4, 4, 12, 0),
                null
        );
        OutboxEventPublisher publisher = new OutboxEventPublisher(
                kafkaTemplate,
                Duration.ofSeconds(5),
                orderEventOutboxService,
                metricsService
        );

        Mockito.when(orderEventOutboxService.claimNextBatch()).thenReturn(List.of(event));
        Mockito.when(kafkaTemplate.send(event.topic(), event.eventKey(), event.payload()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")));

        publisher.publishPendingEvents();

        Mockito.verify(orderEventOutboxService).markRetryableFailure(Mockito.eq(event.id()), Mockito.contains("Kafka unavailable"));
        Mockito.verify(metricsService).recordOutboxPublishResult(event.topic(), false);
        Mockito.verify(orderEventOutboxService, Mockito.never()).markPublished(Mockito.anyLong());
    }
}
