package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * Customer notifications for order events (order-notification consumer group): a processed_events mark per event, in
 * the transaction of the notification, plus one notification per order and event type. A dead letter replayed after a
 * later event of its order was notified is stale (README "Out-of-order events"): its notice would describe a step the
 * order has left, so it is not written. Every order event notifies, so each one moves this consumer's position.
 */
@Service
public class OrderEventHandlerService {

    static final String ORDER_NOTIFICATION_CONSUMER = "order-notification-consumer";

    private final OrderNotificationService orderNotificationService;
    private final ProcessedEventService processedEventService;
    private final EventSequenceTracker sequenceTracker;


    public OrderEventHandlerService(
            OrderNotificationService orderNotificationService,
            ProcessedEventService processedEventService,
            EventSequenceTracker sequenceTracker
    ) {
        this.orderNotificationService = orderNotificationService;
        this.processedEventService = processedEventService;
        this.sequenceTracker = sequenceTracker;
    }


    @Transactional
    public void handle(OrderEventEnvelope event) {
        if (!processedEventService.markIfUnseen(ORDER_NOTIFICATION_CONSUMER, event)) {
            return;
        }
        if (!sequenceTracker.advance(ORDER_NOTIFICATION_CONSUMER, event)) {
            return;
        }
        orderNotificationService.recordOrderEvent(event);
    }
}
