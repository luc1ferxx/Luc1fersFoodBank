package com.laioffer.onlineorder;


import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventPayload;
import com.laioffer.onlineorder.model.OrderHistoryItemDto;
import com.laioffer.onlineorder.repository.OrderNotificationRepository;
import com.laioffer.onlineorder.service.OrderNotificationService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;


import java.time.LocalDateTime;
import java.util.List;


@ExtendWith(MockitoExtension.class)
class OrderNotificationServiceTests {

    @org.mockito.Mock
    private OrderNotificationRepository orderNotificationRepository;


    @Test
    void recordOrderEvent_shouldInsertNotificationWithConflictSafeRepositoryMethod() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository);
        OrderEventEnvelope payload = buildEvent("evt-1", "order.created", 9L, 4L, "PLACED");

        Mockito.when(orderNotificationRepository.insertIfAbsent(
                Mockito.eq(9L),
                Mockito.eq(4L),
                Mockito.eq("order.created"),
                Mockito.eq("Order placed"),
                Mockito.contains("Order #9 was placed"),
                Mockito.any(LocalDateTime.class)
        )).thenReturn(1);

        service.recordOrderEvent(payload);

        Mockito.verify(orderNotificationRepository).insertIfAbsent(
                Mockito.eq(9L),
                Mockito.eq(4L),
                Mockito.eq("order.created"),
                Mockito.eq("Order placed"),
                Mockito.contains("Order #9 was placed"),
                Mockito.any(LocalDateTime.class)
        );
        Mockito.verify(orderNotificationRepository, Mockito.never()).save(Mockito.any());
    }


    @Test
    void recordOrderEvent_whenDuplicateNotificationInsertIsSkipped_shouldNotThrow() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository);
        OrderEventEnvelope payload = buildEvent("evt-2", "order.paid", 9L, 4L, "PAID");

        Mockito.when(orderNotificationRepository.insertIfAbsent(
                Mockito.anyLong(),
                Mockito.anyLong(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(LocalDateTime.class)
        )).thenReturn(0);

        Assertions.assertDoesNotThrow(() -> service.recordOrderEvent(payload));
    }


    @Test
    void recordOrderEvent_whenRepositoryReportsNonDuplicateIntegrityFailure_shouldPropagate() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository);
        OrderEventEnvelope payload = buildEvent("evt-3", "order.paid", 9L, 4L, "PAID");

        Mockito.when(orderNotificationRepository.insertIfAbsent(
                Mockito.anyLong(),
                Mockito.anyLong(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(LocalDateTime.class)
        )).thenThrow(new DataIntegrityViolationException("foreign key violation"));

        Assertions.assertThrows(DataIntegrityViolationException.class, () -> service.recordOrderEvent(payload));
    }


    private OrderEventEnvelope buildEvent(String eventId, String eventType, Long orderId, Long customerId, String status) {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 4, 4, 12, 0);
        return new OrderEventEnvelope(
                eventId,
                1,
                eventType,
                "ORDER",
                orderId,
                occurredAt,
                "corr-" + eventId,
                "idem-" + eventId,
                new OrderEventPayload(
                        customerId,
                        status,
                        22.0,
                        occurredAt,
                        List.of(new OrderHistoryItemDto(1L, orderId, 2L, 10.0, 1, "Burger", "Classic", "image"))
                )
        );
    }
}
