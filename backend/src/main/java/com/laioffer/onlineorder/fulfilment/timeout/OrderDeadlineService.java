package com.laioffer.onlineorder.fulfilment.timeout;


import com.laioffer.onlineorder.fulfilment.FulfilmentEvents;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.service.ProcessedEventService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;


/**
 * Keeps the order deadlines in step with the order events (timeout consumer group):
 * <ul>
 *     <li>order.created: a PAYMENT deadline at the order time plus app.fulfilment.payment-timeout;</li>
 *     <li>order.paid: the PAYMENT deadline is dropped and an ACCEPT deadline is set at the payment time plus
 *     app.fulfilment.accept-timeout;</li>
 *     <li>any later order event: the order no longer waits for anything, so its deadlines are dropped.</li>
 * </ul>
 * Deadlines are measured from the event's occurredAt, so a consumer that lags does not extend them. Dropping a
 * deadline early only saves the poller a no-op: the poller cancels an order only while it is still in the status the
 * deadline waits in. Each event is applied once per consumer (processed_events, same transaction).
 */
@Service
public class OrderDeadlineService {

    static final String ORDER_TIMEOUT_CONSUMER = "order-timeout-consumer";

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
    private final Duration paymentTimeout;
    private final Duration acceptTimeout;


    public OrderDeadlineService(
            OrderDeadlineRepository orderDeadlineRepository,
            ProcessedEventService processedEventService,
            @Value("${app.fulfilment.payment-timeout:15m}") Duration paymentTimeout,
            @Value("${app.fulfilment.accept-timeout:10m}") Duration acceptTimeout
    ) {
        this.orderDeadlineRepository = orderDeadlineRepository;
        this.processedEventService = processedEventService;
        this.paymentTimeout = paymentTimeout;
        this.acceptTimeout = acceptTimeout;
    }


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
            case FulfilmentEvents.ORDER_PAID -> {
                orderDeadlineRepository.deleteByOrderIdAndDeadlineTypes(orderId, List.of(DeadlineType.PAYMENT.name()));
                orderDeadlineRepository.insertIfAbsent(
                        orderId,
                        DeadlineType.ACCEPT.name(),
                        occurredAt.plus(acceptTimeout),
                        now
                );
            }
            default -> orderDeadlineRepository.deleteByOrderIdAndDeadlineTypes(orderId, ALL_DEADLINES);
        }
    }
}
