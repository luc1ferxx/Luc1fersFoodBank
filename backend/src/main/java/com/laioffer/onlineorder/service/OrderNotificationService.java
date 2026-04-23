package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.OrderNotificationEntity;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventPayload;
import com.laioffer.onlineorder.model.OrderNotificationDto;
import com.laioffer.onlineorder.repository.OrderNotificationRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;


import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


@Service
public class OrderNotificationService {

    private final OrderNotificationRepository orderNotificationRepository;


    public OrderNotificationService(OrderNotificationRepository orderNotificationRepository) {
        this.orderNotificationRepository = orderNotificationRepository;
    }


    public void recordOrderEvent(OrderEventEnvelope event) {
        validate(event);
        OrderEventPayload data = event.data();
        try {
            orderNotificationRepository.save(new OrderNotificationEntity(
                    null,
                    event.aggregateId(),
                    data.customerId(),
                    event.eventType(),
                    buildTitle(event),
                    buildMessage(event),
                    LocalDateTime.now()
            ));
        } catch (DataIntegrityViolationException ignored) {
            // Duplicate delivery is expected under at-least-once consumption; ignore it.
        }
    }


    public List<OrderNotificationDto> getNotificationsByCustomerId(Long customerId) {
        List<OrderNotificationDto> results = new ArrayList<>();
        for (OrderNotificationEntity entity : orderNotificationRepository.findAllByCustomerIdOrderByCreatedAtDesc(customerId)) {
            results.add(new OrderNotificationDto(entity));
        }
        return results;
    }


    private void validate(OrderEventEnvelope event) {
        if (event == null || event.aggregateId() == null || event.data() == null || event.data().customerId() == null) {
            throw new IllegalArgumentException("Order event payload is missing required identifiers");
        }
        if (event.eventType() == null || event.eventType().isBlank()) {
            throw new IllegalArgumentException("Order event type is required");
        }
    }


    private String buildTitle(OrderEventEnvelope event) {
        return switch (event.eventType()) {
            case "order.paid" -> "Payment confirmed";
            case "order.created" -> "Order placed";
            case "order.accepted" -> "Order accepted";
            case "order.preparing" -> "Preparing your order";
            case "order.completed" -> "Order completed";
            case "order.cancelled" -> "Order cancelled";
            default -> "Order update";
        };
    }


    private String buildMessage(OrderEventEnvelope event) {
        int itemCount = event.data().items() == null ? 0 : event.data().items().size();
        return switch (event.eventType()) {
            case "order.paid" -> "Order #" + event.aggregateId() + " was paid successfully with " + itemCount + " item(s).";
            case "order.created" -> "Order #" + event.aggregateId() + " was placed and sent for processing.";
            case "order.accepted" -> "Order #" + event.aggregateId() + " was accepted by the restaurant.";
            case "order.preparing" -> "Order #" + event.aggregateId() + " is now being prepared.";
            case "order.completed" -> "Order #" + event.aggregateId() + " is completed and ready.";
            case "order.cancelled" -> "Order #" + event.aggregateId() + " was cancelled.";
            default -> "Order #" + event.aggregateId() + " has a new event: " + event.eventType() + ".";
        };
    }
}
