package com.laioffer.onlineorder.fulfilment.merchant;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.fulfilment.FulfilmentEvents;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventPayload;
import com.laioffer.onlineorder.service.ProcessedEventService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;


/**
 * Builds the merchant order inbox from order events (merchant consumer group). order.paid adds the order to its
 * restaurant's inbox; later order events move the row forward, never back. Each event is applied once per
 * consumer (processed_events, in the same transaction as the inbox write), and the unique order_id makes a second
 * order.paid for one order a no-op as well.
 *
 * <p>Events can arrive out of order: an order.paid dead-lettered from this group and replayed after the order was
 * cancelled, for example. A later event that finds no row creates it with the status it reports (a tombstone for
 * order.cancelled), without a paid_at, so the merchant does not see it yet. The replayed order.paid then only sets
 * paid_at and leaves the status alone: the row shows up with the order's real status instead of a stale PAID that no
 * merchant command could ever resolve. Both writes go through the unique order_id, so a concurrent order.paid and
 * order.cancelled end in the same state in either order.
 *
 * <p>Orders without a restaurant (placed before single-restaurant carts) are not shown to merchants.
 */
@Service
public class MerchantInboxService {

    static final String MERCHANT_INBOX_CONSUMER = "merchant-inbox-consumer";

    private static final List<String> BEFORE_ACCEPTED = List.of("PAID");
    private static final List<String> BEFORE_PREPARING = List.of("PAID", "ACCEPTED");
    private static final List<String> NOT_FINISHED = List.of("PAID", "ACCEPTED", "PREPARING");
    private static final Set<String> HANDLED_EVENT_TYPES = Set.of(
            FulfilmentEvents.ORDER_PAID,
            FulfilmentEvents.ORDER_ACCEPTED,
            FulfilmentEvents.ORDER_PREPARING,
            FulfilmentEvents.ORDER_COMPLETED,
            FulfilmentEvents.ORDER_CANCELLED
    );

    private final MerchantOrderRepository merchantOrderRepository;
    private final ObjectMapper objectMapper;
    private final ProcessedEventService processedEventService;


    public MerchantInboxService(
            MerchantOrderRepository merchantOrderRepository,
            ObjectMapper objectMapper,
            ProcessedEventService processedEventService
    ) {
        this.merchantOrderRepository = merchantOrderRepository;
        this.objectMapper = objectMapper;
        this.processedEventService = processedEventService;
    }


    @Transactional
    public void handle(OrderEventEnvelope event) {
        if (event == null || !HANDLED_EVENT_TYPES.contains(event.eventType())) {
            return;
        }
        OrderEventPayload data = event.data();
        if (event.aggregateId() == null || data == null || data.restaurantId() == null) {
            return;
        }
        if (!processedEventService.markIfUnseen(MERCHANT_INBOX_CONSUMER, event)) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        Long orderId = event.aggregateId();
        switch (event.eventType()) {
            case FulfilmentEvents.ORDER_PAID -> {
                LocalDateTime paidAt = event.occurredAt() == null ? now : event.occurredAt();
                int inserted = merchantOrderRepository.insertPaidOrderIfAbsent(
                        orderId,
                        data.restaurantId(),
                        data.customerId(),
                        data.totalPrice(),
                        serializeItems(data),
                        paidAt,
                        now
                );
                if (inserted == 0) {
                    merchantOrderRepository.recordPayment(orderId, paidAt, now);
                }
            }
            case FulfilmentEvents.ORDER_ACCEPTED -> {
                if (insertUnpaidIfAbsent(orderId, data, "ACCEPTED", null, now) == 0) {
                    merchantOrderRepository.advanceStatus(orderId, BEFORE_ACCEPTED, "ACCEPTED", now);
                }
            }
            case FulfilmentEvents.ORDER_PREPARING -> {
                if (insertUnpaidIfAbsent(orderId, data, "PREPARING", null, now) == 0) {
                    merchantOrderRepository.advanceStatus(orderId, BEFORE_PREPARING, "PREPARING", now);
                }
            }
            case FulfilmentEvents.ORDER_COMPLETED -> {
                if (insertUnpaidIfAbsent(orderId, data, "COMPLETED", null, now) == 0) {
                    merchantOrderRepository.advanceStatus(orderId, NOT_FINISHED, "COMPLETED", now);
                }
            }
            case FulfilmentEvents.ORDER_CANCELLED -> {
                if (insertUnpaidIfAbsent(orderId, data, "CANCELLED", data.reason(), now) == 0) {
                    merchantOrderRepository.cancel(orderId, NOT_FINISHED, data.reason(), now);
                }
            }
            default -> {
            }
        }
    }


    /**
     * @return 1 when this event created the order's row (it reached the group before order.paid), 0 when the order
     * already has one
     */
    private int insertUnpaidIfAbsent(
            Long orderId,
            OrderEventPayload data,
            String status,
            String cancelReason,
            LocalDateTime now
    ) {
        return merchantOrderRepository.insertUnpaidOrderIfAbsent(
                orderId,
                data.restaurantId(),
                data.customerId(),
                status,
                data.totalPrice(),
                serializeItems(data),
                cancelReason,
                now
        );
    }


    private String serializeItems(OrderEventPayload data) {
        try {
            return objectMapper.writeValueAsString(data.items() == null ? List.of() : data.items());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize order items for the merchant inbox", ex);
        }
    }
}
