package com.laioffer.onlineorder.fulfilment.notification;


import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import com.laioffer.onlineorder.service.EventSequenceTracker;
import com.laioffer.onlineorder.service.OrderNotificationService;
import com.laioffer.onlineorder.service.ProcessedEventService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * Customer notifications for delivery events (delivery-notification consumer group), deduplicated like the order
 * notifications: a processed_events mark per event plus one notification per order and event type. A dead letter
 * replayed after a later event of its delivery was notified is stale (docs/fulfilment.md "Out-of-order events") and
 * writes nothing. Only the events that notify (assigned, picked up, delivered) move this consumer's position in the
 * delivery's events.
 */
@Service
public class DeliveryNotificationService {

    static final String DELIVERY_NOTIFICATION_CONSUMER = "delivery-notification-consumer";

    private final OrderNotificationService orderNotificationService;
    private final ProcessedEventService processedEventService;
    private final EventSequenceTracker sequenceTracker;


    public DeliveryNotificationService(
            OrderNotificationService orderNotificationService,
            ProcessedEventService processedEventService,
            EventSequenceTracker sequenceTracker
    ) {
        this.orderNotificationService = orderNotificationService;
        this.processedEventService = processedEventService;
        this.sequenceTracker = sequenceTracker;
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
        if (orderNotificationService.notifiesOf(event)
                && !sequenceTracker.advance(DELIVERY_NOTIFICATION_CONSUMER, event)) {
            return;
        }
        orderNotificationService.recordDeliveryEvent(event);
    }
}
