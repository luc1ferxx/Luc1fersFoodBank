package com.laioffer.onlineorder.fulfilment.order;


import com.laioffer.onlineorder.fulfilment.FulfilmentEvents;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands.DeliveryCompletion;
import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import com.laioffer.onlineorder.service.ProcessedEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * The order module's reaction to delivery events (order-delivery consumer group): delivery.delivered completes the
 * order (see {@link OrderLifecycleCommands#completeDeliveredOrder}). The processed_events mark and the order
 * transition commit together, so a redelivered event completes the order once.
 */
@Service
public class DeliveryCompletionService {

    static final String ORDER_DELIVERY_CONSUMER = "order-delivery-consumer";

    private static final Logger LOGGER = LoggerFactory.getLogger(DeliveryCompletionService.class);

    private final OrderLifecycleCommands orderLifecycleCommands;
    private final ProcessedEventService processedEventService;


    public DeliveryCompletionService(
            OrderLifecycleCommands orderLifecycleCommands,
            ProcessedEventService processedEventService
    ) {
        this.orderLifecycleCommands = orderLifecycleCommands;
        this.processedEventService = processedEventService;
    }


    @Transactional
    public void handle(DeliveryEventEnvelope event) {
        if (event == null || !FulfilmentEvents.DELIVERY_DELIVERED.equals(event.eventType())) {
            return;
        }
        if (event.data() == null || event.data().orderId() == null) {
            throw new IllegalArgumentException("Delivery event is missing the order id");
        }
        if (event.eventId() == null || event.eventId().isBlank()) {
            throw new IllegalArgumentException("Event id is required");
        }
        if (!processedEventService.markIfUnseen(
                ORDER_DELIVERY_CONSUMER,
                event.eventId(),
                event.eventId(),
                event.eventType(),
                event.aggregateType(),
                event.aggregateId()
        )) {
            return;
        }

        Long orderId = event.data().orderId();
        DeliveryCompletion completion = orderLifecycleCommands.completeDeliveredOrder(orderId);
        if (completion == DeliveryCompletion.IGNORED || completion == DeliveryCompletion.NOT_FOUND) {
            LOGGER.warn(
                    "Delivered order {} was not completed: {} (delivery {})",
                    orderId,
                    completion,
                    event.aggregateId()
            );
        }
    }
}
