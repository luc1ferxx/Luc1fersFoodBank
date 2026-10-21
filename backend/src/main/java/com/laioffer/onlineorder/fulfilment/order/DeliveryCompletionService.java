package com.laioffer.onlineorder.fulfilment.order;


import com.laioffer.onlineorder.fulfilment.FulfilmentEvents;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands.DeliveryCompletion;
import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import com.laioffer.onlineorder.model.OrderActor;
import com.laioffer.onlineorder.service.ProcessedEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.Set;


/**
 * The order module's reaction to delivery events (order-delivery consumer group): delivery.picked_up records the
 * pickup on the order (see {@link OrderLifecycleCommands#recordPickup}), so a customer cancel afterwards refunds only
 * part of it, and delivery.delivered completes the order (see {@link OrderLifecycleCommands#completeDeliveredOrder}).
 * The processed_events mark and the order's write commit together, so a redelivered event is applied once. Both are
 * safe out of order: a pickup is recorded only while none is, and a delivered order ignores a late pickup's effect.
 */
@Service
public class DeliveryCompletionService {

    static final String ORDER_DELIVERY_CONSUMER = "order-delivery-consumer";

    private static final Set<String> APPLIED_EVENT_TYPES =
            Set.of(FulfilmentEvents.DELIVERY_PICKED_UP, FulfilmentEvents.DELIVERY_DELIVERED);

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
        if (event == null || !APPLIED_EVENT_TYPES.contains(event.eventType())) {
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
        // The rider who picked up or delivered, as far as the order module knows it: the rider id the event carries.
        OrderActor actor = event.data().riderId() == null
                ? OrderActor.system()
                : OrderActor.rider(event.data().riderId());
        if (FulfilmentEvents.DELIVERY_PICKED_UP.equals(event.eventType())) {
            if (event.occurredAt() == null) {
                throw new IllegalArgumentException("Pickup event is missing its time");
            }
            if (!orderLifecycleCommands.recordPickup(orderId, event.occurredAt(), actor)) {
                LOGGER.info("Pickup of order {} not recorded: unknown order, cancelled or completed already, or pickup "
                        + "already recorded (delivery {})", orderId, event.aggregateId());
            }
            return;
        }
        DeliveryCompletion completion = orderLifecycleCommands.completeDeliveredOrder(orderId, actor);
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
