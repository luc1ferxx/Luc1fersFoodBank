package com.laioffer.onlineorder.fulfilment.dispatch;


import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.fulfilment.AfterCommit;
import com.laioffer.onlineorder.fulfilment.FulfilmentEvents;
import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import com.laioffer.onlineorder.messaging.DeliveryEventPayload;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventPayload;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import com.laioffer.onlineorder.service.ProcessedEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;


/**
 * Assigns riders to accepted orders (dispatch consumer group) and tracks each delivery.
 *
 * <p>Both order event handlers first create (if absent) and lock the order's dispatch_orders row, so they serialize
 * per order even on different consumers, and an order.cancelled that was handled first leaves a tombstone there.
 *
 * <p>order.accepted: the least-loaded rider that is active and on duty is locked with FOR UPDATE SKIP LOCKED, the
 * delivery is inserted (unique per order) and the rider's load incremented in one transaction, together with the
 * processed_events mark and the delivery.assigned outbox event. A duplicate delivery of the event, or another event for
 * an order that already has a delivery, assigns no second rider. An order.accepted for an order whose order.cancelled
 * was already handled (a late or replayed event, for example the DLT replay below) assigns no rider. With no rider on
 * duty the event fails, is retried and finally dead-lettered; an operator gets a rider on duty and replays it
 * (README "Order fulfilment").
 *
 * <p>order.cancelled: the tombstone is set, and an open delivery is cancelled and its rider released
 * (delivery.cancelled).
 *
 * <p>Rider actions: picked up and delivered, each a guarded {@link DeliveryStatus} transition under the delivery row
 * lock that writes its delivery event to the outbox in the same transaction. Repeating an action that already happened
 * returns the delivery unchanged. The rider API ({@link RiderService}) only reaches the rider's own deliveries; the
 * admin endpoints (ops and tests) reach any delivery.
 */
@Service
public class DispatchService {

    static final String DISPATCH_CONSUMER = "dispatch-consumer";
    static final String DELIVERY_STATUS_CONFLICT = "DELIVERY_STATUS_CONFLICT";

    private static final Logger LOGGER = LoggerFactory.getLogger(DispatchService.class);

    private final DeliveryRepository deliveryRepository;
    private final DispatchOrderRepository dispatchOrderRepository;
    private final RiderRepository riderRepository;
    private final OrderEventOutboxService outboxService;
    private final ProcessedEventService processedEventService;
    private final ApplicationMetricsService metricsService;
    private final String deliveryTopic;


    public DispatchService(
            DeliveryRepository deliveryRepository,
            DispatchOrderRepository dispatchOrderRepository,
            RiderRepository riderRepository,
            OrderEventOutboxService outboxService,
            ProcessedEventService processedEventService,
            ApplicationMetricsService metricsService,
            @Value("${app.fulfilment.delivery-topic:delivery-events}") String deliveryTopic
    ) {
        this.deliveryRepository = deliveryRepository;
        this.dispatchOrderRepository = dispatchOrderRepository;
        this.riderRepository = riderRepository;
        this.outboxService = outboxService;
        this.processedEventService = processedEventService;
        this.metricsService = metricsService;
        this.deliveryTopic = deliveryTopic;
    }


    @Transactional
    public void handle(OrderEventEnvelope event) {
        if (event == null || !(FulfilmentEvents.ORDER_ACCEPTED.equals(event.eventType())
                || FulfilmentEvents.ORDER_CANCELLED.equals(event.eventType()))) {
            return;
        }
        OrderEventPayload data = event.data();
        if (event.aggregateId() == null || data == null || data.restaurantId() == null || data.customerId() == null) {
            return;
        }
        if (!processedEventService.markIfUnseen(DISPATCH_CONSUMER, event)) {
            return;
        }

        if (FulfilmentEvents.ORDER_ACCEPTED.equals(event.eventType())) {
            assignRider(event.aggregateId(), data);
        } else {
            cancelDelivery(event.aggregateId());
        }
    }


    @Transactional
    public DeliveryDto markPickedUp(Long deliveryId) {
        return advance(deliveryId, DeliveryStatus.PICKED_UP);
    }


    @Transactional
    public DeliveryDto markDelivered(Long deliveryId) {
        return advance(deliveryId, DeliveryStatus.DELIVERED);
    }


    /**
     * The same guarded transition as the admin actions, only on a delivery assigned to {@code riderId}.
     *
     * @throws ResourceNotFoundException for another rider's delivery, the same as for one that does not exist
     * @throws ConflictException         DELIVERY_STATUS_CONFLICT when the delivery cannot move to {@code target}
     */
    @Transactional
    public DeliveryEntity advanceForRider(Long riderId, Long deliveryId, DeliveryStatus target) {
        DeliveryEntity delivery = deliveryRepository.lockByIdAndRiderId(deliveryId, riderId);
        if (delivery == null) {
            throw new ResourceNotFoundException("Delivery not found");
        }
        return transition(delivery, target);
    }


    public List<DeliveryDto> getDeliveries(Long orderId, int limit) {
        List<DeliveryEntity> deliveries;
        if (orderId != null) {
            DeliveryEntity delivery = deliveryRepository.findByOrderId(orderId);
            deliveries = delivery == null ? List.of() : List.of(delivery);
        } else {
            deliveries = deliveryRepository.findRecent(Math.max(1, Math.min(limit, 100)));
        }
        return deliveries.stream()
                .map(delivery -> new DeliveryDto(delivery, riderName(delivery.riderId())))
                .toList();
    }


    private void assignRider(Long orderId, OrderEventPayload order) {
        DispatchOrderEntity dispatchOrder = lockDispatchOrder(orderId);
        if (dispatchOrder.cancelledAt() != null) {
            AfterCommit.run(() -> metricsService.recordDispatchAssignment("cancelled"));
            LOGGER.info(
                    "Order {} was cancelled at {} before dispatch saw it accepted; no rider assigned",
                    orderId,
                    dispatchOrder.cancelledAt()
            );
            return;
        }
        if (deliveryRepository.findByOrderId(orderId) != null) {
            AfterCommit.run(() -> metricsService.recordDispatchAssignment("duplicate"));
            return;
        }

        RiderEntity rider = riderRepository.lockLeastLoadedUnlockedRider();
        if (rider == null) {
            rider = riderRepository.lockLeastLoadedRider();
        }
        if (rider == null) {
            // Counted per attempt (this transaction rolls back and the listener retries the record), not per order.
            metricsService.recordDispatchAssignment("no_rider_attempt");
            throw new IllegalStateException("No rider is active and on duty for order " + orderId);
        }

        LocalDateTime now = LocalDateTime.now();
        int inserted = deliveryRepository.insertAssignedIfAbsent(
                orderId,
                order.customerId(),
                order.restaurantId(),
                rider.id(),
                now
        );
        if (inserted == 0) {
            AfterCommit.run(() -> metricsService.recordDispatchAssignment("duplicate"));
            return;
        }
        riderRepository.incrementLoad(rider.id(), now);
        DeliveryEntity delivery = deliveryRepository.findByOrderId(orderId);
        enqueueDeliveryEvent(delivery, rider.name(), now);
        AfterCommit.run(() -> metricsService.recordDispatchAssignment("assigned"));
        LOGGER.info(
                "Rider assigned: order_id={}, delivery_id={}, rider_id={}, rider_load_before={}",
                orderId,
                delivery.id(),
                rider.id(),
                rider.activeDeliveries()
        );
    }


    private void cancelDelivery(Long orderId) {
        lockDispatchOrder(orderId);
        // The tombstone, also when the order has no delivery yet: a later order.accepted must not assign a rider.
        dispatchOrderRepository.markCancelled(orderId, LocalDateTime.now());
        DeliveryEntity delivery = deliveryRepository.lockByOrderId(orderId);
        if (delivery == null || !DeliveryStatus.valueOf(delivery.status()).holdsRider()) {
            return;
        }
        applyTransition(delivery, DeliveryStatus.CANCELLED);
    }


    /**
     * Creates the order's dispatch row if absent and locks it. When another transaction is inserting or holding the
     * same row, this waits for it to finish, so the accepted and cancelled handlers of one order never interleave.
     */
    private DispatchOrderEntity lockDispatchOrder(Long orderId) {
        dispatchOrderRepository.insertIfAbsent(orderId, LocalDateTime.now());
        DispatchOrderEntity dispatchOrder = dispatchOrderRepository.lockByOrderId(orderId);
        if (dispatchOrder == null) {
            throw new IllegalStateException("Dispatch row of order " + orderId + " is missing after its insert");
        }
        return dispatchOrder;
    }


    private DeliveryDto advance(Long deliveryId, DeliveryStatus target) {
        DeliveryEntity delivery = deliveryRepository.lockById(deliveryId);
        if (delivery == null) {
            throw new ResourceNotFoundException("Delivery not found");
        }
        DeliveryEntity updated = transition(delivery, target);
        return new DeliveryDto(updated, riderName(updated.riderId()));
    }


    /**
     * @param delivery locked by the caller
     * @return the delivery after the transition, or unchanged when it already is in {@code target} (a retry)
     */
    private DeliveryEntity transition(DeliveryEntity delivery, DeliveryStatus target) {
        DeliveryStatus current = DeliveryStatus.valueOf(delivery.status());
        if (current == target) {
            return delivery;
        }
        if (!current.canTransitionTo(target)) {
            throw new ConflictException(
                    DELIVERY_STATUS_CONFLICT,
                    "Delivery is " + current + "; it cannot move to " + target
            );
        }
        return applyTransition(delivery, target);
    }


    private DeliveryEntity applyTransition(DeliveryEntity delivery, DeliveryStatus target) {
        LocalDateTime now = LocalDateTime.now();
        DeliveryEntity updated = new DeliveryEntity(
                delivery.id(),
                delivery.orderId(),
                delivery.customerId(),
                delivery.restaurantId(),
                delivery.riderId(),
                target.name(),
                delivery.assignedAt(),
                target == DeliveryStatus.PICKED_UP ? now : delivery.pickedUpAt(),
                target == DeliveryStatus.DELIVERED ? now : delivery.deliveredAt(),
                target == DeliveryStatus.CANCELLED ? now : delivery.cancelledAt(),
                now
        );
        deliveryRepository.updateStatus(
                updated.id(),
                updated.status(),
                updated.pickedUpAt(),
                updated.deliveredAt(),
                updated.cancelledAt(),
                now
        );
        if (!target.holdsRider()) {
            riderRepository.decrementLoad(delivery.riderId(), now);
        }
        enqueueDeliveryEvent(updated, riderName(updated.riderId()), now);
        return updated;
    }


    private void enqueueDeliveryEvent(DeliveryEntity delivery, String riderName, LocalDateTime occurredAt) {
        String eventId = UUID.randomUUID().toString();
        String eventType = DeliveryStatus.valueOf(delivery.status()).eventType();
        DeliveryEventEnvelope envelope = new DeliveryEventEnvelope(
                eventId,
                1,
                eventType,
                "DELIVERY",
                delivery.id(),
                occurredAt,
                eventId,
                null,
                new DeliveryEventPayload(
                        delivery.orderId(),
                        delivery.customerId(),
                        delivery.restaurantId(),
                        delivery.riderId(),
                        riderName,
                        delivery.status()
                )
        );
        outboxService.enqueueEvent(
                "DELIVERY",
                delivery.id(),
                deliveryTopic,
                String.valueOf(delivery.orderId()),
                eventType,
                eventId,
                envelope
        );
    }


    private String riderName(Long riderId) {
        return riderRepository.findById(riderId).map(RiderEntity::name).orElse(null);
    }
}
