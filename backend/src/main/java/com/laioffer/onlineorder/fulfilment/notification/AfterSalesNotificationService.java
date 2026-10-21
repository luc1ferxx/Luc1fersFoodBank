package com.laioffer.onlineorder.fulfilment.notification;


import com.laioffer.onlineorder.messaging.AfterSalesEventEnvelope;
import com.laioffer.onlineorder.service.EventSequenceTracker;
import com.laioffer.onlineorder.service.OrderNotificationService;
import com.laioffer.onlineorder.service.ProcessedEventService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * Customer notifications for after-sales events (aftersales-notification consumer group, ADR 0002 section 10.3),
 * deduplicated like the delivery notifications: a processed_events mark per event, in the transaction of the
 * notification, plus one notification per order, event type and case (dedup_scope 'case:' || case id). A redelivered
 * event therefore adds nothing, while the second request of an order, whose events have the same types as the first's,
 * notifies again. A dead letter replayed after a later event of its case was notified is stale (README "Out-of-order
 * events"): its notice would describe a step the case has left, so it is not written. Only the events that notify
 * move this consumer's position in the case's events.
 */
@Service
public class AfterSalesNotificationService {

    static final String AFTER_SALES_NOTIFICATION_CONSUMER = "aftersales-notification-consumer";

    private final OrderNotificationService orderNotificationService;
    private final ProcessedEventService processedEventService;
    private final EventSequenceTracker sequenceTracker;


    public AfterSalesNotificationService(
            OrderNotificationService orderNotificationService,
            ProcessedEventService processedEventService,
            EventSequenceTracker sequenceTracker
    ) {
        this.orderNotificationService = orderNotificationService;
        this.processedEventService = processedEventService;
        this.sequenceTracker = sequenceTracker;
    }


    @Transactional
    public void handle(AfterSalesEventEnvelope event) {
        if (event == null || event.eventId() == null || event.eventId().isBlank()) {
            throw new IllegalArgumentException("Event id is required");
        }
        if (!processedEventService.markIfUnseen(
                AFTER_SALES_NOTIFICATION_CONSUMER,
                event.eventId(),
                event.eventId(),
                event.eventType(),
                event.aggregateType(),
                event.aggregateId()
        )) {
            return;
        }
        if (orderNotificationService.notifiesOf(event)
                && !sequenceTracker.advance(AFTER_SALES_NOTIFICATION_CONSUMER, event)) {
            return;
        }
        orderNotificationService.recordAfterSalesEvent(event);
    }
}
