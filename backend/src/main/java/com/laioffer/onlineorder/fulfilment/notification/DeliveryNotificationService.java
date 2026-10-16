package com.laioffer.onlineorder.fulfilment.notification;


import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import com.laioffer.onlineorder.service.OrderNotificationService;
import com.laioffer.onlineorder.service.ProcessedEventService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * Customer notifications for delivery events (delivery-notification consumer group), deduplicated like the order
 * notifications: a processed_events mark per event plus one notification per order and event type.
 */
@Service
public class DeliveryNotificationService {

    static final String DELIVERY_NOTIFICATION_CONSUMER = "delivery-notification-consumer";

    private final OrderNotificationService orderNotificationService;
    private final ProcessedEventService processedEventService;


    public DeliveryNotificationService(
            OrderNotificationService orderNotificationService,
            ProcessedEventService processedEventService
    ) {
        this.orderNotificationService = orderNotificationService;
        this.processedEventService = processedEventService;
    }


    @Transactional
    public void handle(DeliveryEventEnvelope event) {
        if (event == null || event.eventId() == null || event.eventId().isBlank()) {
            throw new IllegalArgumentException("Event id is required");
        }
        if (!processedEventService.markIfUnseen(
                DELIVERY_NOTIFICATION_CONSUMER,
                event.eventId(),
                event.eventId(),
                event.eventType(),
                event.aggregateType(),
                event.aggregateId()
        )) {
            return;
        }
        orderNotificationService.recordDeliveryEvent(event);
    }
}
