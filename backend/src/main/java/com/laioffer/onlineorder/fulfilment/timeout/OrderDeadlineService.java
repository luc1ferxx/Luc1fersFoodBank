package com.laioffer.onlineorder.fulfilment.timeout;


import com.laioffer.onlineorder.fulfilment.FulfilmentEvents;
import com.laioffer.onlineorder.messaging.MerchantEventEnvelope;
import com.laioffer.onlineorder.messaging.MerchantEventPayload;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.service.EventSequenceTracker;
import com.laioffer.onlineorder.service.ProcessedEventService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;


/**
 * Keeps the order deadlines in step with the order and merchant events (timeout consumer groups):
 * <ul>
 *     <li>order.created: a PAYMENT deadline at the order time plus app.fulfilment.payment-timeout;</li>
 *     <li>order.paid: the PAYMENT deadline is dropped;</li>
 *     <li>merchant.order_received (merchant topic, own consumer group): the merchant module saw the paid order arrive
 *     at a restaurant with at least one active merchant account, so, when app.fulfilment.accept-timeout-enabled is on
 *     (the default), an ACCEPT deadline is set at app.fulfilment.accept-timeout after the order reached the inbox
 *     (the later of the payment time and the event time). A restaurant without an active merchant gets no such event
 *     and its orders no ACCEPT deadline: nobody could accept them;</li>
 *     <li>any later order event: the order no longer waits for anything, so its deadlines are dropped.</li>
 * </ul>
 * Deadlines are measured from event times, so this consumer lagging does not extend them. The ACCEPT deadline starts
 * when the merchant could first see the order: an inbox that received order.paid late (a backlog, or a dead-lettered
 * order.paid replayed later) still gives the merchant the whole accept window instead of a deadline that is already
 * due. Dropping a deadline early only saves the poller a no-op: the poller cancels an order only while it is
 * still in the status the deadline waits in. That also covers a merchant.order_received handled after the order was
 * accepted or cancelled (the two topics are consumed independently): its deadline fires as a no-op. Each event is
 * applied once per consumer (processed_events, same transaction). The module never reads the merchant module's
 * tables; the staffing decision reaches it only as that event. See README "Order fulfilment".
 */
@Service
public class OrderDeadlineService {

    static final String ORDER_TIMEOUT_CONSUMER = "order-timeout-consumer";
    static final String MERCHANT_TIMEOUT_CONSUMER = "order-timeout-merchant-consumer";

    private static final List<String> ALL_DEADLINES = List.of(DeadlineType.PAYMENT.name(), DeadlineType.ACCEPT.name());
    private static final Set<String> HANDLED_EVENT_TYPES = Set.of(
            FulfilmentEvents.ORDER_CREATED,
            FulfilmentEvents.ORDER_PAID,
            FulfilmentEvents.ORDER_ACCEPTED,
            FulfilmentEvents.ORDER_PREPARING,
            FulfilmentEvents.ORDER_COMPLETED,
            FulfilmentEvents.ORDER_CANCELLED
    );

    private final OrderDeadlineRepository orderDeadlineRepository;
    private final ProcessedEventService processedEventService;
    private final EventSequenceTracker sequenceTracker;
    private final Duration paymentTimeout;
    private final Duration acceptTimeout;
    private final boolean acceptTimeoutEnabled;


    public OrderDeadlineService(
            OrderDeadlineRepository orderDeadlineRepository,
            ProcessedEventService processedEventService,
            EventSequenceTracker sequenceTracker,
            @Value("${app.fulfilment.payment-timeout:15m}") Duration paymentTimeout,
            @Value("${app.fulfilment.accept-timeout:10m}") Duration acceptTimeout,
            @Value("${app.fulfilment.accept-timeout-enabled:true}") boolean acceptTimeoutEnabled
    ) {
        this.orderDeadlineRepository = orderDeadlineRepository;
        this.processedEventService = processedEventService;
        this.sequenceTracker = sequenceTracker;
        this.paymentTimeout = paymentTimeout;
        this.acceptTimeout = acceptTimeout;
        this.acceptTimeoutEnabled = acceptTimeoutEnabled;
    }


    /**
     * An order event older (by sequence) than the last one this consumer applied for the order is stale and skipped:
     * every later order event already dropped the deadlines it could add or drop, so a late order.created no longer
     * writes a PAYMENT deadline for an order that was paid in the meantime.
     */
    @Transactional
    public void handle(OrderEventEnvelope event) {
        if (event == null || !HANDLED_EVENT_TYPES.contains(event.eventType())) {
            return;
        }
        // Orders without a restaurant (placed before single-restaurant carts) stay on the admin-driven flow.
        if (event.aggregateId() == null || event.data() == null || event.data().restaurantId() == null) {
            return;
        }
        if (!processedEventService.markIfUnseen(ORDER_TIMEOUT_CONSUMER, event)) {
            return;
        }
        if (!sequenceTracker.advance(ORDER_TIMEOUT_CONSUMER, event)) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime occurredAt = event.occurredAt() == null ? now : event.occurredAt();
        Long orderId = event.aggregateId();
        switch (event.eventType()) {
            case FulfilmentEvents.ORDER_CREATED -> orderDeadlineRepository.insertIfAbsent(
                    orderId,
                    DeadlineType.PAYMENT.name(),
                    occurredAt.plus(paymentTimeout),
                    now
            );
            case FulfilmentEvents.ORDER_PAID ->
                    orderDeadlineRepository.deleteByOrderIdAndDeadlineTypes(orderId, List.of(DeadlineType.PAYMENT.name()));
            default -> orderDeadlineRepository.deleteByOrderIdAndDeadlineTypes(orderId, ALL_DEADLINES);
        }
    }


    /**
     * merchant.order_received: the paid order reached a staffed restaurant; sets its ACCEPT deadline from the moment
     * the inbox received it (the event time, or the payment time if that is later, for clock skew between instances).
     * A duplicate (or a copy under a new event id) adds no second deadline: (order_id, deadline_type) is unique.
     *
     * @throws IllegalArgumentException for an event without an event id or order id (retried, then dead-lettered)
     */
    @Transactional
    public void handle(MerchantEventEnvelope event) {
        if (event == null || !FulfilmentEvents.MERCHANT_ORDER_RECEIVED.equals(event.eventType())) {
            return;
        }
        if (event.eventId() == null || event.eventId().isBlank()) {
            throw new IllegalArgumentException("Event id is required");
        }
        MerchantEventPayload data = event.data();
        if (data == null || data.orderId() == null) {
            throw new IllegalArgumentException("Merchant event is missing the order id");
        }
        if (!processedEventService.markIfUnseen(
                MERCHANT_TIMEOUT_CONSUMER,
                event.eventId(),
                event.eventId(),
                event.eventType(),
                event.aggregateType(),
                event.aggregateId()
        )) {
            return;
        }
        if (!acceptTimeoutEnabled) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        orderDeadlineRepository.insertIfAbsent(
                data.orderId(),
                DeadlineType.ACCEPT.name(),
                receivedAt(event, now).plus(acceptTimeout),
                now
        );
    }


    /**
     * When the merchant inbox received the paid order: the announcement's event time, which the inbox sets in the
     * transaction that makes the order visible to the merchant (this consumer's own time for an event without one, a
     * later bound), but never before the payment.
     */
    static LocalDateTime receivedAt(MerchantEventEnvelope event, LocalDateTime now) {
        LocalDateTime announcedAt = event.occurredAt() == null ? now : event.occurredAt();
        LocalDateTime paidAt = event.data().paidAt();
        return paidAt != null && paidAt.isAfter(announcedAt) ? paidAt : announcedAt;
    }
}
