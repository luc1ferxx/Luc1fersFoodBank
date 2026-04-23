package com.laioffer.onlineorder;


import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventPayload;
import com.laioffer.onlineorder.model.OrderHistoryItemDto;
import com.laioffer.onlineorder.repository.OrderNotificationRepository;
import com.laioffer.onlineorder.service.OrderNotificationService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;


import java.time.LocalDateTime;
import java.util.List;


@ExtendWith(MockitoExtension.class)
class OrderNotificationServiceTests {

    @Mock
    private OrderNotificationRepository orderNotificationRepository;


    @Test
    void recordOrderEvent_shouldPersistNotification() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository);
        LocalDateTime occurredAt = LocalDateTime.of(2026, 4, 4, 12, 0);
        OrderEventEnvelope payload = new OrderEventEnvelope(
                "evt-1",
                1,
                "order.created",
                "ORDER",
                9L,
                occurredAt,
                "corr-1",
                "idem-1",
                new OrderEventPayload(
                        4L,
                        "PLACED",
                        22.0,
                        occurredAt,
                        List.of(new OrderHistoryItemDto(1L, 9L, 2L, 10.0, 1, "Burger", "Classic", "image"))
                )
        );

        service.recordOrderEvent(payload);

        ArgumentCaptor<com.laioffer.onlineorder.entity.OrderNotificationEntity> captor =
                ArgumentCaptor.forClass(com.laioffer.onlineorder.entity.OrderNotificationEntity.class);
        Mockito.verify(orderNotificationRepository).save(captor.capture());
        Assertions.assertEquals(9L, captor.getValue().orderId());
        Assertions.assertEquals(4L, captor.getValue().customerId());
        Assertions.assertEquals("order.created", captor.getValue().eventType());
    }


    @Test
    void recordOrderEvent_shouldIgnoreDuplicateNotificationInsert() {
        OrderNotificationService service = new OrderNotificationService(orderNotificationRepository);
        LocalDateTime occurredAt = LocalDateTime.of(2026, 4, 4, 12, 0);
        OrderEventEnvelope payload = new OrderEventEnvelope(
                "evt-2",
                1,
                "order.paid",
                "ORDER",
                9L,
                occurredAt,
                "corr-2",
                "idem-2",
                new OrderEventPayload(
                        4L,
                        "PAID",
                        22.0,
                        occurredAt,
                        List.of()
                )
        );

        Mockito.when(orderNotificationRepository.save(Mockito.any()))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        Assertions.assertDoesNotThrow(() -> service.recordOrderEvent(payload));
    }
}
