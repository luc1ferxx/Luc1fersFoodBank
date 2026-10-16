package com.laioffer.onlineorder.fulfilment.dispatch;


import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import com.laioffer.onlineorder.service.ProcessedEventService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.time.LocalDateTime;
import java.util.Optional;


import static com.laioffer.onlineorder.fulfilment.FulfilmentTestEvents.orderEvent;


@ExtendWith(MockitoExtension.class)
class DispatchServiceTests {

    private static final long ORDER_ID = 10L;

    @Mock
    private DeliveryRepository deliveryRepository;

    @Mock
    private DispatchOrderRepository dispatchOrderRepository;

    @Mock
    private RiderRepository riderRepository;

    @Mock
    private OrderEventOutboxService outboxService;

    @Mock
    private ProcessedEventService processedEventService;

    @Mock
    private ApplicationMetricsService metricsService;

    private DispatchService service;


    @BeforeEach
    void setup() {
        service = new DispatchService(
                deliveryRepository,
                dispatchOrderRepository,
                riderRepository,
                outboxService,
                processedEventService,
                metricsService,
                "delivery-events"
        );
    }


    @Test
    void orderAccepted_shouldAssignTheLeastLoadedRiderAndPublishDeliveryAssigned() {
        OrderEventEnvelope accepted = acceptedEvent();
        Mockito.when(processedEventService.markIfUnseen("dispatch-consumer", accepted)).thenReturn(true);
        openDispatchOrder();
        Mockito.when(deliveryRepository.findByOrderId(ORDER_ID)).thenReturn(null, delivery(7L, "ASSIGNED"));
        Mockito.when(riderRepository.lockLeastLoadedUnlockedRider()).thenReturn(rider(3L, "Alice"));
        Mockito.when(deliveryRepository.insertAssignedIfAbsent(
                Mockito.eq(ORDER_ID), Mockito.eq(3L), Mockito.eq(2L), Mockito.eq(3L), Mockito.any())).thenReturn(1);

        service.handle(accepted);

        // The dispatch row is created and locked before the rider is chosen.
        org.mockito.InOrder lockOrder = Mockito.inOrder(dispatchOrderRepository, riderRepository);
        lockOrder.verify(dispatchOrderRepository).insertIfAbsent(Mockito.eq(ORDER_ID), Mockito.any());
        lockOrder.verify(dispatchOrderRepository).lockByOrderId(ORDER_ID);
        lockOrder.verify(riderRepository).lockLeastLoadedUnlockedRider();
        Mockito.verify(riderRepository).incrementLoad(Mockito.eq(3L), Mockito.any());
        Mockito.verify(riderRepository, Mockito.never()).lockLeastLoadedRider();
        ArgumentCaptor<Object> envelope = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(outboxService).enqueueEvent(
                Mockito.eq("DELIVERY"),
                Mockito.eq(7L),
                Mockito.eq("delivery-events"),
                Mockito.eq(String.valueOf(ORDER_ID)),
                Mockito.eq("delivery.assigned"),
                Mockito.anyString(),
                envelope.capture()
        );
        DeliveryEventEnvelope event = (DeliveryEventEnvelope) envelope.getValue();
        Assertions.assertEquals(ORDER_ID, event.data().orderId());
        Assertions.assertEquals("Alice", event.data().riderName());
        Assertions.assertEquals("ASSIGNED", event.data().status());
        Mockito.verify(metricsService).recordDispatchAssignment("assigned");
    }


    @Test
    void orderAccepted_whenEveryRiderIsLockedByAnotherAssignment_shouldWaitForTheLeastLoadedOne() {
        OrderEventEnvelope accepted = acceptedEvent();
        Mockito.when(processedEventService.markIfUnseen("dispatch-consumer", accepted)).thenReturn(true);
        openDispatchOrder();
        Mockito.when(deliveryRepository.findByOrderId(ORDER_ID)).thenReturn(null, delivery(7L, "ASSIGNED"));
        Mockito.when(riderRepository.lockLeastLoadedUnlockedRider()).thenReturn(null);
        Mockito.when(riderRepository.lockLeastLoadedRider()).thenReturn(rider(4L, "Bob"));
        Mockito.when(deliveryRepository.insertAssignedIfAbsent(
                Mockito.eq(ORDER_ID), Mockito.anyLong(), Mockito.anyLong(), Mockito.eq(4L), Mockito.any())).thenReturn(1);

        service.handle(accepted);

        Mockito.verify(riderRepository).incrementLoad(Mockito.eq(4L), Mockito.any());
    }


    @Test
    void orderAccepted_whenNoRiderIsActive_shouldFailForARetry() {
        OrderEventEnvelope accepted = acceptedEvent();
        Mockito.when(processedEventService.markIfUnseen("dispatch-consumer", accepted)).thenReturn(true);
        openDispatchOrder();
        Mockito.when(deliveryRepository.findByOrderId(ORDER_ID)).thenReturn(null);

        Assertions.assertThrows(IllegalStateException.class, () -> service.handle(accepted));

        // Counted per attempt: the listener retries the record and every attempt counts again.
        Mockito.verify(metricsService).recordDispatchAssignment("no_rider_attempt");
        Mockito.verify(deliveryRepository, Mockito.never())
                .insertAssignedIfAbsent(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.any());
        Mockito.verifyNoInteractions(outboxService);
    }


    @Test
    void duplicateOrderAccepted_shouldNotAssignASecondRider() {
        OrderEventEnvelope accepted = acceptedEvent();
        Mockito.when(processedEventService.markIfUnseen("dispatch-consumer", accepted)).thenReturn(false);

        service.handle(accepted);

        Mockito.verifyNoInteractions(deliveryRepository, dispatchOrderRepository, riderRepository, outboxService);
    }


    @Test
    void orderAccepted_afterTheOrderWasCancelled_shouldAssignNoRider() {
        OrderEventEnvelope accepted = acceptedEvent();
        Mockito.when(processedEventService.markIfUnseen("dispatch-consumer", accepted)).thenReturn(true);
        LocalDateTime cancelledAt = LocalDateTime.of(2026, 4, 4, 12, 5);
        Mockito.when(dispatchOrderRepository.lockByOrderId(ORDER_ID))
                .thenReturn(new DispatchOrderEntity(ORDER_ID, cancelledAt, cancelledAt, cancelledAt));

        service.handle(accepted);

        Mockito.verifyNoInteractions(deliveryRepository, riderRepository, outboxService);
        Mockito.verify(metricsService).recordDispatchAssignment("cancelled");
    }


    @Test
    void orderAccepted_whenTheOrderAlreadyHasADelivery_shouldNotAssignASecondRider() {
        OrderEventEnvelope accepted = acceptedEvent();
        Mockito.when(processedEventService.markIfUnseen("dispatch-consumer", accepted)).thenReturn(true);
        openDispatchOrder();
        Mockito.when(deliveryRepository.findByOrderId(ORDER_ID)).thenReturn(delivery(7L, "ASSIGNED"));

        service.handle(accepted);

        Mockito.verifyNoInteractions(riderRepository, outboxService);
        Mockito.verify(metricsService).recordDispatchAssignment("duplicate");
    }


    @Test
    void orderAccepted_whenAConcurrentAssignmentInsertedFirst_shouldNotLoadTheRider() {
        OrderEventEnvelope accepted = acceptedEvent();
        Mockito.when(processedEventService.markIfUnseen("dispatch-consumer", accepted)).thenReturn(true);
        openDispatchOrder();
        Mockito.when(deliveryRepository.findByOrderId(ORDER_ID)).thenReturn(null);
        Mockito.when(riderRepository.lockLeastLoadedUnlockedRider()).thenReturn(rider(3L, "Alice"));
        Mockito.when(deliveryRepository.insertAssignedIfAbsent(
                Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.any())).thenReturn(0);

        service.handle(accepted);

        Mockito.verify(riderRepository, Mockito.never()).incrementLoad(Mockito.anyLong(), Mockito.any());
        Mockito.verifyNoInteractions(outboxService);
    }


    @Test
    void orderCancelled_shouldCancelTheOpenDeliveryAndReleaseTheRider() {
        OrderEventEnvelope cancelled = orderEvent("order.cancelled", ORDER_ID, 2L, "CANCELLED");
        Mockito.when(processedEventService.markIfUnseen("dispatch-consumer", cancelled)).thenReturn(true);
        openDispatchOrder();
        Mockito.when(deliveryRepository.lockByOrderId(ORDER_ID)).thenReturn(delivery(7L, "ASSIGNED"));
        Mockito.when(riderRepository.findById(3L)).thenReturn(Optional.of(rider(3L, "Alice")));

        service.handle(cancelled);

        Mockito.verify(deliveryRepository).updateStatus(
                Mockito.eq(7L), Mockito.eq("CANCELLED"), Mockito.isNull(), Mockito.isNull(), Mockito.any(), Mockito.any());
        Mockito.verify(riderRepository).decrementLoad(Mockito.eq(3L), Mockito.any());
        Mockito.verify(outboxService).enqueueEvent(
                Mockito.eq("DELIVERY"), Mockito.eq(7L), Mockito.eq("delivery-events"), Mockito.eq("10"),
                Mockito.eq("delivery.cancelled"), Mockito.anyString(), Mockito.any());
    }


    @Test
    void orderCancelled_whenThereIsNoDelivery_shouldOnlyLeaveTheTombstone() {
        OrderEventEnvelope cancelled = orderEvent("order.cancelled", ORDER_ID, 2L, "CANCELLED");
        Mockito.when(processedEventService.markIfUnseen("dispatch-consumer", cancelled)).thenReturn(true);
        openDispatchOrder();

        service.handle(cancelled);

        org.mockito.InOrder tombstone = Mockito.inOrder(dispatchOrderRepository, deliveryRepository);
        tombstone.verify(dispatchOrderRepository).insertIfAbsent(Mockito.eq(ORDER_ID), Mockito.any());
        tombstone.verify(dispatchOrderRepository).lockByOrderId(ORDER_ID);
        tombstone.verify(dispatchOrderRepository).markCancelled(Mockito.eq(ORDER_ID), Mockito.any());
        tombstone.verify(deliveryRepository).lockByOrderId(ORDER_ID);
        Mockito.verifyNoMoreInteractions(deliveryRepository);
        Mockito.verifyNoInteractions(riderRepository, outboxService);
    }


    @Test
    void markDelivered_shouldRequirePickUpFirst() {
        Mockito.when(deliveryRepository.lockById(7L)).thenReturn(delivery(7L, "ASSIGNED"));

        ConflictException conflict = Assertions.assertThrows(ConflictException.class, () -> service.markDelivered(7L));

        Assertions.assertEquals("DELIVERY_STATUS_CONFLICT", conflict.getCode());
        Mockito.verifyNoInteractions(outboxService);
    }


    @Test
    void markDelivered_afterPickUp_shouldReleaseTheRiderAndPublishDeliveryDelivered() {
        Mockito.when(deliveryRepository.lockById(7L)).thenReturn(delivery(7L, "PICKED_UP"));
        Mockito.when(riderRepository.findById(3L)).thenReturn(Optional.of(rider(3L, "Alice")));

        DeliveryDto delivered = service.markDelivered(7L);

        Assertions.assertEquals("DELIVERED", delivered.status());
        Assertions.assertNotNull(delivered.deliveredAt());
        Mockito.verify(riderRepository).decrementLoad(Mockito.eq(3L), Mockito.any());
        Mockito.verify(outboxService).enqueueEvent(
                Mockito.eq("DELIVERY"), Mockito.eq(7L), Mockito.eq("delivery-events"), Mockito.eq("10"),
                Mockito.eq("delivery.delivered"), Mockito.anyString(), Mockito.any());
    }


    @Test
    void markDelivered_whenRepeated_shouldReturnTheDeliveryUnchanged() {
        Mockito.when(deliveryRepository.lockById(7L)).thenReturn(delivery(7L, "DELIVERED"));
        Mockito.when(riderRepository.findById(3L)).thenReturn(Optional.of(rider(3L, "Alice")));

        Assertions.assertEquals("DELIVERED", service.markDelivered(7L).status());

        Mockito.verify(deliveryRepository, Mockito.never())
                .updateStatus(Mockito.anyLong(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(riderRepository, Mockito.never()).decrementLoad(Mockito.anyLong(), Mockito.any());
        Mockito.verifyNoInteractions(outboxService);
    }


    @Test
    void markPickedUp_whenDeliveryDoesNotExist_shouldReturnNotFound() {
        Assertions.assertThrows(ResourceNotFoundException.class, () -> service.markPickedUp(99L));
    }


    @Test
    void advanceForRider_onTheRidersOwnDelivery_shouldMakeTheSameGuardedTransition() {
        Mockito.when(deliveryRepository.lockByIdAndRiderId(7L, 3L)).thenReturn(delivery(7L, "ASSIGNED"));
        Mockito.when(riderRepository.findById(3L)).thenReturn(Optional.of(rider(3L, "Alice")));

        DeliveryEntity pickedUp = service.advanceForRider(3L, 7L, DeliveryStatus.PICKED_UP);

        Assertions.assertEquals("PICKED_UP", pickedUp.status());
        Assertions.assertNotNull(pickedUp.pickedUpAt());
        Mockito.verify(deliveryRepository, Mockito.never()).lockById(Mockito.anyLong());
        Mockito.verify(riderRepository, Mockito.never()).decrementLoad(Mockito.anyLong(), Mockito.any());
        Mockito.verify(outboxService).enqueueEvent(
                Mockito.eq("DELIVERY"), Mockito.eq(7L), Mockito.eq("delivery-events"), Mockito.eq("10"),
                Mockito.eq("delivery.picked_up"), Mockito.anyString(), Mockito.any());
    }


    @Test
    void advanceForRider_onAnotherRidersDelivery_shouldReturnNotFoundAndChangeNothing() {
        // lockByIdAndRiderId finds nothing: the delivery belongs to rider 3, not rider 4.
        Assertions.assertThrows(
                ResourceNotFoundException.class, () -> service.advanceForRider(4L, 7L, DeliveryStatus.PICKED_UP));

        Mockito.verify(deliveryRepository, Mockito.never())
                .updateStatus(Mockito.anyLong(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verifyNoInteractions(outboxService, riderRepository);
    }


    @Test
    void advanceForRider_whenRepeatedOrInTheWrongState_shouldBeIdempotentOrConflict() {
        Mockito.when(deliveryRepository.lockByIdAndRiderId(7L, 3L))
                .thenReturn(delivery(7L, "DELIVERED"), delivery(7L, "CANCELLED"));

        Assertions.assertEquals("DELIVERED", service.advanceForRider(3L, 7L, DeliveryStatus.DELIVERED).status());
        ConflictException conflict = Assertions.assertThrows(
                ConflictException.class, () -> service.advanceForRider(3L, 7L, DeliveryStatus.PICKED_UP));

        Assertions.assertEquals("DELIVERY_STATUS_CONFLICT", conflict.getCode());
        Mockito.verifyNoInteractions(outboxService);
    }


    private void openDispatchOrder() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 4, 4, 12, 0);
        Mockito.when(dispatchOrderRepository.lockByOrderId(ORDER_ID))
                .thenReturn(new DispatchOrderEntity(ORDER_ID, null, createdAt, createdAt));
    }


    private OrderEventEnvelope acceptedEvent() {
        return orderEvent("order.accepted", ORDER_ID, 2L, "ACCEPTED");
    }


    private RiderEntity rider(Long id, String name) {
        LocalDateTime now = LocalDateTime.now();
        return new RiderEntity(id, null, name, null, true, true, 0, now, now);
    }


    private DeliveryEntity delivery(Long id, String status) {
        LocalDateTime assignedAt = LocalDateTime.of(2026, 4, 4, 12, 0);
        return new DeliveryEntity(
                id,
                ORDER_ID,
                3L,
                2L,
                3L,
                status,
                assignedAt,
                "PICKED_UP".equals(status) || "DELIVERED".equals(status) ? assignedAt.plusMinutes(5) : null,
                "DELIVERED".equals(status) ? assignedAt.plusMinutes(20) : null,
                null,
                assignedAt
        );
    }
}
