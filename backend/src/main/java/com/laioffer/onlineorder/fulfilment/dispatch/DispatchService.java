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
 *
 * <p>Reassignment (admin): an open delivery moves to another active, on-duty rider, for example when its rider was
 * deactivated. The delivery keeps its status, the load moves with it, and delivery.reassigned is published.
 */
@Service
public class DispatchService {

    static final String DISPATCH_CONSUMER = "dispatch-consumer";
    static final String DELIVERY_STATUS_CONFLICT = "DELIVERY_STATUS_CONFLICT";
    static final String RIDER_NOT_DISPATCHABLE = "RIDER_NOT_DISPATCHABLE";

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


    /**
     * Moves an open delivery (ASSIGNED or PICKED_UP, status unchanged) to {@code riderId}, which must be active and on
     * duty. Under the delivery row lock, both riders' rows are locked in id order (two opposite reassignments cannot
     * deadlock), the load moves from the previous rider to the new one, and delivery.reassigned is written to the
     * outbox, all in one transaction. Retrying with the same rider returns the delivery unchanged. The previous rider
     * no longer sees the delivery and gets 404 on it; the new rider finishes it through the rider API.
     *
     * @throws ResourceNotFoundException for an unknown delivery or rider
     * @throws ConflictException         DELIVERY_STATUS_CONFLICT when the delivery is DELIVERED or CANCELLED;
     *                                   RIDER_NOT_DISPATCHABLE when the rider is inactive or off duty
     */
    @Transactional
    public DeliveryDto reassign(Long deliveryId, Long riderId) {
        DeliveryEntity delivery = deliveryRepository.lockById(deliveryId);
        if (delivery == null) {
            throw new ResourceNotFoundException("Delivery not found");
        }
        if (riderId.equals(delivery.riderId())) {
            return new DeliveryDto(delivery, riderName(riderId));
        }
        DeliveryStatus status = DeliveryStatus.valueOf(delivery.status());
        if (!status.holdsRider()) {
            throw new ConflictException(
                    DELIVERY_STATUS_CONFLICT,
                    "Delivery is " + status + "; only an open delivery can be reassigned"
            );
        }
        Long previousRiderId = delivery.riderId();
        RiderEntity rider = lockRidersInIdOrder(previousRiderId, riderId);
        if (rider == null) {
            throw new ResourceNotFoundException("Rider not found");
        }
        if (!rider.active() || !rider.onDuty()) {
            throw new ConflictException(RIDER_NOT_DISPATCHABLE, "Rider " + riderId + " is not active and on duty");
        }

        LocalDateTime now = LocalDateTime.now();
        deliveryRepository.updateRider(delivery.id(), riderId, now);
        riderRepository.decrementLoad(previousRiderId, now);
        riderRepository.incrementLoad(riderId, now);
        DeliveryEntity reassigned = new DeliveryEntity(
                delivery.id(),
                delivery.orderId(),
                delivery.customerId(),
                delivery.restaurantId(),
                riderId,
                delivery.status(),
                delivery.assignedAt(),
                delivery.pickedUpAt(),
                delivery.deliveredAt(),
                delivery.cancelledAt(),
                now
        );
        enqueueDeliveryEvent(reassigned, FulfilmentEvents.DELIVERY_REASSIGNED, rider.name(), now);
        LOGGER.info(
                "Delivery reassigned: delivery_id={}, order_id={}, status={}, from_rider_id={}, to_rider_id={}",
                delivery.id(),
                delivery.orderId(),
                delivery.status(),
                previousRiderId,
                riderId
        );
        return new DeliveryDto(reassigned, rider.name());
    }


    /**
     * The admin's delivery list (GET /admin/deliveries); read-only, a replica read when replicas are configured.
     *
     * @param riderId a rider's open deliveries (ASSIGNED and PICKED_UP), when no order id is given
     */
    @Transactional(readOnly = true)
    public List<DeliveryDto> getDeliveries(Long orderId, Long riderId, int limit) {
        List<DeliveryEntity> deliveries;
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        if (orderId != null) {
            DeliveryEntity delivery = deliveryRepository.findByOrderId(orderId);
            deliveries = delivery == null ? List.of() : List.of(delivery);
        } else if (riderId != null) {
            deliveries = deliveryRepository.findByRiderIdAndStatuses(
                    riderId,
                    List.of(DeliveryStatus.ASSIGNED.name(), DeliveryStatus.PICKED_UP.name()),
                    boundedLimit
            );
        } else {
            deliveries = deliveryRepository.findRecent(boundedLimit);
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


    /**
     * Locks both riders' rows in ascending id order and returns {@code riderId}'s, or null when it does not exist.
     */
    private RiderEntity lockRidersInIdOrder(Long previousRiderId, Long riderId) {
        if (previousRiderId != null && previousRiderId < riderId) {
            riderRepository.lockById(previousRiderId);
            return riderRepository.lockById(riderId);
        }
        RiderEntity rider = riderRepository.lockById(riderId);
        if (previousRiderId != null) {
            riderRepository.lockById(previousRiderId);
        }
        return rider;
    }


    private void enqueueDeliveryEvent(DeliveryEntity delivery, String riderName, LocalDateTime occurredAt) {
        enqueueDeliveryEvent(delivery, DeliveryStatus.valueOf(delivery.status()).eventType(), riderName, occurredAt);
    }


    private void enqueueDeliveryEvent(
            DeliveryEntity delivery,
            String eventType,
            String riderName,
            LocalDateTime occurredAt
    ) {
        String eventId = UUID.randomUUID().toString();
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
