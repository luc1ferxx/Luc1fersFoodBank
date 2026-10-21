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
import com.laioffer.onlineorder.service.EventSequenceTracker;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import com.laioffer.onlineorder.service.ProcessedEventService;
import com.laioffer.onlineorder.statemachine.CompareAndSet;
import com.laioffer.onlineorder.statemachine.Decision;
import com.laioffer.onlineorder.statemachine.StaleTransitionException;
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
 * <p>Rider actions: picked up and delivered, each a guarded transition under the delivery row lock that writes its
 * delivery event to the outbox in the same transaction. Repeating an action that already happened returns the delivery
 * unchanged. The rider API ({@link RiderService}) only reaches the rider's own deliveries; the admin endpoints (ops and
 * tests) reach any delivery.
 *
 * <p>Reassignment (admin): an open delivery moves to another active, on-duty rider, for example when its rider was
 * deactivated. The delivery keeps its status, the load moves with it, and delivery.reassigned is published.
 *
 * <p>Every change of a delivery's status or rider is decided by {@link DeliveryStateMachine} on the row read under the
 * lock and written with a compare-and-set on the status and rider read there. A write that does not change exactly one
 * row throws {@link StaleTransitionException} and rolls back; with the lock held it cannot happen. A delivery that
 * leaves a status holding its rider releases that rider (the one the compare-and-set confirmed) once.
 */
@Service
public class DispatchService {

    static final String DISPATCH_CONSUMER = "dispatch-consumer";
    static final String DELIVERY_STATUS_CONFLICT = "DELIVERY_STATUS_CONFLICT";
    static final String RIDER_NOT_DISPATCHABLE = "RIDER_NOT_DISPATCHABLE";
    static final String DELIVERY_AGGREGATE = "DELIVERY";

    /**
     * The row named in a {@link StaleTransitionException}.
     */
    private static final String DELIVERY_ROW = "delivery";

    /**
     * The schema version of the delivery events written here: event-schemas/delivery-events/&lt;event_type&gt;/v1.json.
     */
    static final int DELIVERY_EVENT_VERSION = 1;

    private static final Logger LOGGER = LoggerFactory.getLogger(DispatchService.class);

    private final DeliveryRepository deliveryRepository;
    private final DispatchOrderRepository dispatchOrderRepository;
    private final RiderRepository riderRepository;
    private final OrderEventOutboxService outboxService;
    private final ProcessedEventService processedEventService;
    private final EventSequenceTracker sequenceTracker;
    private final ApplicationMetricsService metricsService;
    private final String deliveryTopic;


    public DispatchService(
            DeliveryRepository deliveryRepository,
            DispatchOrderRepository dispatchOrderRepository,
            RiderRepository riderRepository,
            OrderEventOutboxService outboxService,
            ProcessedEventService processedEventService,
            EventSequenceTracker sequenceTracker,
            ApplicationMetricsService metricsService,
            @Value("${app.fulfilment.delivery-topic:delivery-events}") String deliveryTopic
    ) {
        this.deliveryRepository = deliveryRepository;
        this.dispatchOrderRepository = dispatchOrderRepository;
        this.riderRepository = riderRepository;
        this.outboxService = outboxService;
        this.processedEventService = processedEventService;
        this.sequenceTracker = sequenceTracker;
        this.metricsService = metricsService;
        this.deliveryTopic = deliveryTopic;
    }


    /**
     * order.accepted and order.cancelled only. An event older (by sequence) than the last of the two this consumer
     * applied for the order is stale and skipped: an order.accepted replayed after the order.cancelled that followed it
     * assigns no rider (the tombstone below covers events without a sequence). The other order event types do not move
     * that position, so an order.accepted dead-lettered for want of a rider and replayed after order.preparing still
     * assigns one.
     */
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
        if (!sequenceTracker.advance(DISPATCH_CONSUMER, event)) {
            return;
        }

        if (FulfilmentEvents.ORDER_ACCEPTED.equals(event.eventType())) {
            assignRider(event.aggregateId(), data);
        } else {
            cancelDelivery(event.aggregateId());
        }
    }


    /**
     * The admin's simulated pick-up (an {@link DeliveryActor#ADMIN} move).
     */
    @Transactional
    public DeliveryDto markPickedUp(Long deliveryId) {
        return advance(deliveryId, DeliveryStatus.PICKED_UP);
    }


    /**
     * The admin's simulated delivery (an {@link DeliveryActor#ADMIN} move).
     */
    @Transactional
    public DeliveryDto markDelivered(Long deliveryId) {
        return advance(deliveryId, DeliveryStatus.DELIVERED);
    }


    /**
     * The same guarded transition as the admin actions, only on a delivery assigned to {@code riderId} (an
     * {@link DeliveryActor#RIDER} move).
     *
     * @param target PICKED_UP or DELIVERED
     * @throws ResourceNotFoundException for another rider's delivery, the same as for one that does not exist
     * @throws ConflictException         DELIVERY_STATUS_CONFLICT when the delivery cannot move to {@code target}
     */
    @Transactional
    public DeliveryEntity advanceForRider(Long riderId, Long deliveryId, DeliveryStatus target) {
        DeliveryEntity delivery = deliveryRepository.lockByIdAndRiderId(deliveryId, riderId);
        if (delivery == null) {
            throw new ResourceNotFoundException("Delivery not found");
        }
        return transition(delivery, target, DeliveryActor.RIDER);
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
        DeliveryStatus status = DeliveryStatus.valueOf(delivery.status());
        Decision<DeliveryStatus, DeliveryTrigger> decision = DeliveryStateMachine.DEFINITION.decide(
                status,
                DeliveryTrigger.REASSIGN,
                DeliveryActor.ADMIN,
                new DeliveryCommandContext(delivery.riderId(), riderId)
        );
        // The same rider is answered before the status: a retry also gets a finished delivery unchanged.
        if (decision instanceof Decision.AlreadyDone) {
            return new DeliveryDto(delivery, riderName(riderId));
        }
        if (decision instanceof Decision.Rejected) {
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
        int rows = deliveryRepository.compareAndSetRider(delivery.id(), previousRiderId, riderId, now);
        CompareAndSet.expectOneRow(rows, DELIVERY_ROW, delivery.id(), status + " with rider " + previousRiderId);
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
        if (delivery == null) {
            return;
        }
        Decision<DeliveryStatus, DeliveryTrigger> decision = DeliveryStateMachine.DEFINITION.decide(
                DeliveryStatus.valueOf(delivery.status()),
                DeliveryTrigger.CANCEL_FOR_ORDER,
                DeliveryActor.SYSTEM,
                null
        );
        // Only an open delivery is cancelled. A cancelled one (AlreadyDone) released its rider already, and a delivered
        // one (Rejected) stays delivered: the order was cancelled after the rider delivered it.
        if (decision instanceof Decision.Apply<DeliveryStatus, DeliveryTrigger> apply) {
            applyTransition(delivery, apply);
        }
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
        DeliveryEntity updated = transition(delivery, target, DeliveryActor.ADMIN);
        return new DeliveryDto(updated, riderName(updated.riderId()));
    }


    /**
     * @param delivery locked by the caller
     * @return the delivery after the transition, or unchanged when it already is in {@code target} (a retry)
     */
    private DeliveryEntity transition(DeliveryEntity delivery, DeliveryStatus target, DeliveryActor actor) {
        DeliveryStatus current = DeliveryStatus.valueOf(delivery.status());
        Decision<DeliveryStatus, DeliveryTrigger> decision = DeliveryStateMachine.DEFINITION.decide(
                current,
                DeliveryStateMachine.advanceTrigger(target),
                actor,
                null
        );
        if (decision instanceof Decision.Apply<DeliveryStatus, DeliveryTrigger> apply) {
            return applyTransition(delivery, apply);
        }
        if (decision instanceof Decision.Rejected) {
            throw new ConflictException(
                    DELIVERY_STATUS_CONFLICT,
                    "Delivery is " + current + "; it cannot move to " + target
            );
        }
        // Already done: a retry of a move that happened.
        return delivery;
    }


    /**
     * Writes the move with a compare-and-set on the status and rider of the locked row, releases the rider when the
     * delivery leaves a status that holds one, and writes the delivery event; all with the same time.
     */
    private DeliveryEntity applyTransition(
            DeliveryEntity delivery,
            Decision.Apply<DeliveryStatus, DeliveryTrigger> move
    ) {
        DeliveryStatus from = move.from();
        DeliveryStatus to = move.to();
        Long riderId = delivery.riderId();
        LocalDateTime now = LocalDateTime.now();
        DeliveryEntity updated = new DeliveryEntity(
                delivery.id(),
                delivery.orderId(),
                delivery.customerId(),
                delivery.restaurantId(),
                riderId,
                to.name(),
                delivery.assignedAt(),
                to == DeliveryStatus.PICKED_UP ? now : delivery.pickedUpAt(),
                to == DeliveryStatus.DELIVERED ? now : delivery.deliveredAt(),
                to == DeliveryStatus.CANCELLED ? now : delivery.cancelledAt(),
                now
        );
        int rows = deliveryRepository.compareAndSetStatus(
                updated.id(),
                from.name(),
                riderId,
                updated.status(),
                updated.pickedUpAt(),
                updated.deliveredAt(),
                updated.cancelledAt(),
                now
        );
        CompareAndSet.expectOneRow(rows, DELIVERY_ROW, updated.id(), from + " with rider " + riderId);
        if (from.holdsRider() && !to.holdsRider()) {
            riderRepository.decrementLoad(riderId, now);
        }
        enqueueDeliveryEvent(updated, riderName(riderId), now);
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
                DELIVERY_EVENT_VERSION,
                eventType,
                DELIVERY_AGGREGATE,
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
        outboxService.enqueueEvent(deliveryTopic, String.valueOf(delivery.orderId()), envelope);
    }


    private String riderName(Long riderId) {
        return riderRepository.findById(riderId).map(RiderEntity::name).orElse(null);
    }
}
