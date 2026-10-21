package com.laioffer.onlineorder.fulfilment.dispatch;


import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import com.laioffer.onlineorder.messaging.EventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.service.EventSequenceTracker;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import com.laioffer.onlineorder.service.ProcessedEventService;
import com.laioffer.onlineorder.statemachine.StaleTransitionException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;


import static com.laioffer.onlineorder.fulfilment.FulfilmentTestEvents.orderEvent;
import static com.laioffer.onlineorder.fulfilment.FulfilmentTestEvents.withOrderNo;


@ExtendWith(MockitoExtension.class)
class DispatchServiceTests {

    private static final long ORDER_ID = 10L;

    /**
     * A Snowflake order number past 2^53.
     */
    private static final long ORDER_NO = 7_254_189_912_938_504_192L;

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
    private EventSequenceTracker sequenceTracker;

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
                sequenceTracker,
                metricsService,
                "delivery-events"
        );
        // Events built by FulfilmentTestEvents carry no sequence; the tracker applies them (EventSequenceTrackerTests).
        Mockito.lenient().when(sequenceTracker.advance(Mockito.anyString(), Mockito.any())).thenReturn(true);
    }


    @Test
    void orderAccepted_shouldAssignTheLeastLoadedRiderAndPublishDeliveryAssigned() {
        OrderEventEnvelope accepted = acceptedEvent();
        Mockito.when(processedEventService.markIfUnseen("dispatch-consumer", accepted)).thenReturn(true);
        openDispatchOrder();
        Mockito.when(deliveryRepository.findByOrderId(ORDER_ID)).thenReturn(null, delivery(7L, "ASSIGNED"));
        Mockito.when(riderRepository.lockLeastLoadedUnlockedRider()).thenReturn(rider(3L, "Alice"));
        Mockito.when(deliveryRepository.insertAssignedIfAbsent(
                Mockito.eq(ORDER_ID), Mockito.isNull(), Mockito.eq(3L), Mockito.eq(2L), Mockito.eq(3L), Mockito.any()))
                .thenReturn(1);

        service.handle(accepted);

        // The dispatch row is created and locked before the rider is chosen.
        org.mockito.InOrder lockOrder = Mockito.inOrder(dispatchOrderRepository, riderRepository);
        lockOrder.verify(dispatchOrderRepository).insertIfAbsent(Mockito.eq(ORDER_ID), Mockito.any());
        lockOrder.verify(dispatchOrderRepository).lockByOrderId(ORDER_ID);
        lockOrder.verify(riderRepository).lockLeastLoadedUnlockedRider();
        Mockito.verify(riderRepository).incrementLoad(Mockito.eq(3L), Mockito.any());
        Mockito.verify(riderRepository, Mockito.never()).lockLeastLoadedRider();
        ArgumentCaptor<EventEnvelope> envelope = ArgumentCaptor.forClass(EventEnvelope.class);
        Mockito.verify(outboxService).enqueueEvent(
                Mockito.eq("delivery-events"),
                Mockito.eq(String.valueOf(ORDER_ID)),
                envelope.capture()
        );
        DeliveryEventEnvelope event = (DeliveryEventEnvelope) envelope.getValue();
        Assertions.assertEquals("DELIVERY", event.aggregateType());
        Assertions.assertEquals(7L, event.aggregateId());
        Assertions.assertEquals("delivery.assigned", event.eventType());
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
                Mockito.eq(ORDER_ID), Mockito.any(), Mockito.anyLong(), Mockito.anyLong(), Mockito.eq(4L),
                Mockito.any()))
                .thenReturn(1);

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
                .insertAssignedIfAbsent(Mockito.anyLong(), Mockito.any(), Mockito.anyLong(), Mockito.anyLong(),
                        Mockito.anyLong(), Mockito.any());
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
                Mockito.anyLong(), Mockito.any(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(),
                Mockito.any()))
                .thenReturn(0);

        service.handle(accepted);

        Mockito.verify(riderRepository, Mockito.never()).incrementLoad(Mockito.anyLong(), Mockito.any());
        Mockito.verifyNoInteractions(outboxService);
    }


    @Test
    void orderAccepted_version2_shouldStoreTheOrderNumberWithTheDelivery() {
        OrderEventEnvelope accepted = withOrderNo(acceptedEvent(), ORDER_NO);
        Mockito.when(processedEventService.markIfUnseen("dispatch-consumer", accepted)).thenReturn(true);
        openDispatchOrder();
        Mockito.when(deliveryRepository.findByOrderId(ORDER_ID)).thenReturn(null, numbered(delivery(7L, "ASSIGNED")));
        Mockito.when(riderRepository.lockLeastLoadedUnlockedRider()).thenReturn(rider(3L, "Alice"));
        Mockito.when(deliveryRepository.insertAssignedIfAbsent(
                Mockito.eq(ORDER_ID), Mockito.eq(ORDER_NO), Mockito.eq(3L), Mockito.eq(2L), Mockito.eq(3L),
                Mockito.any())).thenReturn(1);

        service.handle(accepted);

        Mockito.verify(riderRepository).incrementLoad(Mockito.eq(3L), Mockito.any());
        // The insert wrote it; there is no other delivery to fill.
        Mockito.verify(deliveryRepository, Mockito.never()).recordOrderNo(Mockito.anyLong(), Mockito.any());
    }


    @Test
    void orderAccepted_version2_forAnOrderThatHasADelivery_shouldOnlyFillAMissingNumber() {
        OrderEventEnvelope accepted = withOrderNo(acceptedEvent(), ORDER_NO);
        Mockito.when(processedEventService.markIfUnseen("dispatch-consumer", accepted)).thenReturn(true);
        openDispatchOrder();
        Mockito.when(deliveryRepository.findByOrderId(ORDER_ID)).thenReturn(delivery(7L, "ASSIGNED"));

        service.handle(accepted);

        Mockito.verify(deliveryRepository).recordOrderNo(ORDER_ID, ORDER_NO);
        Mockito.verifyNoInteractions(riderRepository, outboxService);
        Mockito.verify(metricsService).recordDispatchAssignment("duplicate");
    }


    @Test
    void orderCancelled_version2_shouldFillAMissingNumberUnderTheLocksAndStillCancelTheDelivery() {
        OrderEventEnvelope cancelled = withOrderNo(orderEvent("order.cancelled", ORDER_ID, 2L, "CANCELLED"), ORDER_NO);
        Mockito.when(processedEventService.markIfUnseen("dispatch-consumer", cancelled)).thenReturn(true);
        openDispatchOrder();
        Mockito.when(deliveryRepository.lockByOrderId(ORDER_ID)).thenReturn(delivery(7L, "ASSIGNED"));
        Mockito.when(riderRepository.findById(3L)).thenReturn(Optional.of(rider(3L, "Alice")));
        Mockito.when(deliveryRepository.compareAndSetStatus(
                Mockito.eq(7L), Mockito.eq("ASSIGNED"), Mockito.eq(3L), Mockito.eq("CANCELLED"),
                Mockito.isNull(), Mockito.isNull(), Mockito.any(), Mockito.any())).thenReturn(1);

        service.handle(cancelled);

        // The dispatch row, then the delivery row, as in every handler; the number is written under both locks.
        org.mockito.InOrder writeOrder = Mockito.inOrder(dispatchOrderRepository, deliveryRepository);
        writeOrder.verify(dispatchOrderRepository).lockByOrderId(ORDER_ID);
        writeOrder.verify(deliveryRepository).lockByOrderId(ORDER_ID);
        writeOrder.verify(deliveryRepository).recordOrderNo(ORDER_ID, ORDER_NO);
        writeOrder.verify(deliveryRepository).compareAndSetStatus(
                Mockito.eq(7L), Mockito.eq("ASSIGNED"), Mockito.eq(3L), Mockito.eq("CANCELLED"),
                Mockito.isNull(), Mockito.isNull(), Mockito.any(), Mockito.any());
        Mockito.verify(riderRepository).decrementLoad(Mockito.eq(3L), Mockito.any());
    }


    /**
     * A stale event changes no delivery and assigns no rider, but still fills a missing number: it never changes.
     */
    @Test
    void staleOrderEvent_version2_shouldOnlyFillAMissingNumber() {
        OrderEventEnvelope accepted = withOrderNo(orderEvent("order.accepted", ORDER_ID, 2L, "ACCEPTED", 3), ORDER_NO);
        Mockito.when(processedEventService.markIfUnseen("dispatch-consumer", accepted)).thenReturn(true);
        Mockito.when(sequenceTracker.advance("dispatch-consumer", accepted)).thenReturn(false);

        service.handle(accepted);

        Mockito.verify(deliveryRepository).recordOrderNo(ORDER_ID, ORDER_NO);
        Mockito.verifyNoMoreInteractions(deliveryRepository);
        Mockito.verifyNoInteractions(dispatchOrderRepository, riderRepository, outboxService);
    }


    @Test
    void orderEvents_version1_shouldRecordNoOrderNumber() {
        OrderEventEnvelope accepted = acceptedEvent();
        Mockito.when(processedEventService.markIfUnseen("dispatch-consumer", accepted)).thenReturn(true);
        openDispatchOrder();
        Mockito.when(deliveryRepository.findByOrderId(ORDER_ID)).thenReturn(delivery(7L, "ASSIGNED"));

        service.handle(accepted);

        Mockito.verify(deliveryRepository, Mockito.never()).recordOrderNo(Mockito.anyLong(), Mockito.any());
    }


    /**
     * Status moves and reassignments write neither the order id nor its number, and return the delivery with both.
     */
    @Test
    void movesAndReassignment_shouldKeepTheDeliverysOrderNumber() {
        Mockito.when(deliveryRepository.lockByIdAndRiderId(7L, 3L)).thenReturn(numbered(delivery(7L, "ASSIGNED")));
        Mockito.when(deliveryRepository.lockById(8L)).thenReturn(numbered(delivery(8L, "PICKED_UP")));
        Mockito.when(riderRepository.findById(3L)).thenReturn(Optional.of(rider(3L, "Alice")));
        Mockito.when(riderRepository.lockById(3L)).thenReturn(rider(3L, "Alice"));
        Mockito.when(riderRepository.lockById(4L)).thenReturn(rider(4L, "Bob"));
        Mockito.when(deliveryRepository.compareAndSetStatus(
                Mockito.eq(7L), Mockito.eq("ASSIGNED"), Mockito.eq(3L), Mockito.eq("PICKED_UP"),
                Mockito.any(), Mockito.isNull(), Mockito.isNull(), Mockito.any())).thenReturn(1);
        Mockito.when(deliveryRepository.compareAndSetRider(
                Mockito.eq(8L), Mockito.eq(3L), Mockito.eq(4L), Mockito.any())).thenReturn(1);

        DeliveryEntity pickedUp = service.advanceForRider(3L, 7L, DeliveryStatus.PICKED_UP);
        DeliveryDto reassigned = service.reassign(8L, 4L);

        Assertions.assertEquals(ORDER_NO, pickedUp.orderNo());
        Assertions.assertEquals(ORDER_NO, reassigned.orderNo());
        Assertions.assertEquals(ORDER_ID, reassigned.orderId());
        Mockito.verify(deliveryRepository, Mockito.never()).recordOrderNo(Mockito.anyLong(), Mockito.any());
    }


    @Test
    void orderCancelled_shouldCancelTheOpenDeliveryAndReleaseTheRider() {
        OrderEventEnvelope cancelled = orderEvent("order.cancelled", ORDER_ID, 2L, "CANCELLED");
        Mockito.when(processedEventService.markIfUnseen("dispatch-consumer", cancelled)).thenReturn(true);
        openDispatchOrder();
        Mockito.when(deliveryRepository.lockByOrderId(ORDER_ID)).thenReturn(delivery(7L, "ASSIGNED"));
        Mockito.when(riderRepository.findById(3L)).thenReturn(Optional.of(rider(3L, "Alice")));
        Mockito.when(deliveryRepository.compareAndSetStatus(
                Mockito.eq(7L), Mockito.eq("ASSIGNED"), Mockito.eq(3L), Mockito.eq("CANCELLED"),
                Mockito.isNull(), Mockito.isNull(), Mockito.any(), Mockito.any())).thenReturn(1);

        service.handle(cancelled);

        Mockito.verify(deliveryRepository).compareAndSetStatus(
                Mockito.eq(7L), Mockito.eq("ASSIGNED"), Mockito.eq(3L), Mockito.eq("CANCELLED"),
                Mockito.isNull(), Mockito.isNull(), Mockito.any(), Mockito.any());
        Mockito.verify(riderRepository).decrementLoad(Mockito.eq(3L), Mockito.any());
        Mockito.verify(outboxService).enqueueEvent(
                Mockito.eq("delivery-events"), Mockito.eq("10"), Mockito.argThat(event -> "DELIVERY".equals(event.aggregateType())
                        && Long.valueOf(7L).equals(event.aggregateId()) && "delivery.cancelled".equals(event.eventType())));
    }


    @Test
    void orderCancelled_whenThereIsNoDelivery_shouldOnlyLeaveTheTombstone() {
        assertOrderCancelledOnlySetsTheTombstone(null);
    }


    @Test
    void orderCancelled_forADeliveredDelivery_shouldOnlySetTheTombstone() {
        // The order was cancelled after the rider delivered it: the delivery stays DELIVERED (ADR 0002 I23, I24).
        assertOrderCancelledOnlySetsTheTombstone(delivery(7L, "DELIVERED"));
    }


    @Test
    void orderCancelled_forACancelledDelivery_shouldOnlySetTheTombstone() {
        // A delivery that is already cancelled has released its rider once; it is not cancelled (or released) again.
        assertOrderCancelledOnlySetsTheTombstone(delivery(7L, "CANCELLED"));
    }


    @Test
    void orderCancelled_forAPickedUpDelivery_shouldWriteThenReleaseTheConfirmedRiderThenPublish() {
        OrderEventEnvelope cancelled = orderEvent("order.cancelled", ORDER_ID, 2L, "CANCELLED");
        Mockito.when(processedEventService.markIfUnseen("dispatch-consumer", cancelled)).thenReturn(true);
        openDispatchOrder();
        DeliveryEntity pickedUp = delivery(7L, "PICKED_UP", 8L);
        Mockito.when(deliveryRepository.lockByOrderId(ORDER_ID)).thenReturn(pickedUp);
        Mockito.when(riderRepository.findById(8L)).thenReturn(Optional.of(rider(8L, "Erin")));
        Mockito.when(deliveryRepository.compareAndSetStatus(
                Mockito.eq(7L), Mockito.eq("PICKED_UP"), Mockito.eq(8L), Mockito.eq("CANCELLED"),
                Mockito.eq(pickedUp.pickedUpAt()), Mockito.isNull(), Mockito.any(), Mockito.any())).thenReturn(1);

        service.handle(cancelled);

        // The compare-and-set expects the status and rider read under the lock; that rider is released, once, after
        // the write and before the event. The cancellation time is the write's time, and picked_up_at stays.
        ArgumentCaptor<LocalDateTime> cancelledAt = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> updatedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.InOrder writeOrder = Mockito.inOrder(deliveryRepository, riderRepository, outboxService);
        writeOrder.verify(deliveryRepository).compareAndSetStatus(
                Mockito.eq(7L), Mockito.eq("PICKED_UP"), Mockito.eq(8L), Mockito.eq("CANCELLED"),
                Mockito.eq(pickedUp.pickedUpAt()), Mockito.isNull(), cancelledAt.capture(), updatedAt.capture());
        writeOrder.verify(riderRepository).decrementLoad(Mockito.eq(8L), Mockito.eq(updatedAt.getValue()));
        writeOrder.verify(outboxService).enqueueEvent(
                Mockito.eq("delivery-events"), Mockito.eq("10"), Mockito.argThat(event -> "delivery.cancelled".equals(event.eventType())
                        && Long.valueOf(8L).equals(((DeliveryEventEnvelope) event).data().riderId())));
        Assertions.assertNotNull(cancelledAt.getValue());
        Assertions.assertEquals(updatedAt.getValue(), cancelledAt.getValue());
        Mockito.verify(riderRepository).decrementLoad(Mockito.anyLong(), Mockito.any());
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
        Mockito.when(deliveryRepository.compareAndSetStatus(
                Mockito.eq(7L), Mockito.eq("PICKED_UP"), Mockito.eq(3L), Mockito.eq("DELIVERED"),
                Mockito.any(), Mockito.any(), Mockito.isNull(), Mockito.any())).thenReturn(1);

        DeliveryDto delivered = service.markDelivered(7L);

        Assertions.assertEquals("DELIVERED", delivered.status());
        Assertions.assertNotNull(delivered.deliveredAt());
        Mockito.verify(riderRepository).decrementLoad(Mockito.eq(3L), Mockito.any());
        Mockito.verify(outboxService).enqueueEvent(
                Mockito.eq("delivery-events"), Mockito.eq("10"), Mockito.argThat(event -> "DELIVERY".equals(event.aggregateType())
                        && Long.valueOf(7L).equals(event.aggregateId()) && "delivery.delivered".equals(event.eventType())));
    }


    @Test
    void markDelivered_whenRepeated_shouldReturnTheDeliveryUnchanged() {
        Mockito.when(deliveryRepository.lockById(7L)).thenReturn(delivery(7L, "DELIVERED"));
        Mockito.when(riderRepository.findById(3L)).thenReturn(Optional.of(rider(3L, "Alice")));

        Assertions.assertEquals("DELIVERED", service.markDelivered(7L).status());

        Mockito.verify(deliveryRepository, Mockito.never())
                .compareAndSetStatus(Mockito.anyLong(), Mockito.anyString(), Mockito.any(), Mockito.anyString(),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(riderRepository, Mockito.never()).decrementLoad(Mockito.anyLong(), Mockito.any());
        Mockito.verifyNoInteractions(outboxService);
    }


    @Test
    void markDelivered_whenTheCompareAndSetChangesNoRow_shouldThrowBeforeReleasingTheRiderOrPublishing() {
        // The row no longer has the status or rider read under the lock: only a write that bypassed the lock can do
        // that, and the transaction rolls back instead of releasing the rider a second time.
        Mockito.when(deliveryRepository.lockById(7L)).thenReturn(delivery(7L, "PICKED_UP"));
        Mockito.when(deliveryRepository.compareAndSetStatus(
                Mockito.eq(7L), Mockito.eq("PICKED_UP"), Mockito.eq(3L), Mockito.eq("DELIVERED"),
                Mockito.any(), Mockito.any(), Mockito.isNull(), Mockito.any())).thenReturn(0);

        StaleTransitionException stale = Assertions.assertThrows(
                StaleTransitionException.class, () -> service.markDelivered(7L));

        Assertions.assertEquals(
                "Compare-and-set of delivery 7 from PICKED_UP with rider 3 changed 0 rows instead of 1",
                stale.getMessage()
        );
        Mockito.verifyNoInteractions(riderRepository, outboxService);
    }


    @Test
    void markPickedUp_whenDeliveryDoesNotExist_shouldReturnNotFound() {
        Assertions.assertThrows(ResourceNotFoundException.class, () -> service.markPickedUp(99L));
    }


    @Test
    void advanceForRider_onTheRidersOwnDelivery_shouldMakeTheSameGuardedTransition() {
        Mockito.when(deliveryRepository.lockByIdAndRiderId(7L, 3L)).thenReturn(delivery(7L, "ASSIGNED"));
        Mockito.when(riderRepository.findById(3L)).thenReturn(Optional.of(rider(3L, "Alice")));
        Mockito.when(deliveryRepository.compareAndSetStatus(
                Mockito.eq(7L), Mockito.eq("ASSIGNED"), Mockito.eq(3L), Mockito.eq("PICKED_UP"),
                Mockito.any(), Mockito.isNull(), Mockito.isNull(), Mockito.any())).thenReturn(1);

        DeliveryEntity pickedUp = service.advanceForRider(3L, 7L, DeliveryStatus.PICKED_UP);

        Assertions.assertEquals("PICKED_UP", pickedUp.status());
        Assertions.assertNotNull(pickedUp.pickedUpAt());
        Mockito.verify(deliveryRepository, Mockito.never()).lockById(Mockito.anyLong());
        Mockito.verify(riderRepository, Mockito.never()).decrementLoad(Mockito.anyLong(), Mockito.any());
        Mockito.verify(outboxService).enqueueEvent(
                Mockito.eq("delivery-events"), Mockito.eq("10"), Mockito.argThat(event -> "DELIVERY".equals(event.aggregateType())
                        && Long.valueOf(7L).equals(event.aggregateId()) && "delivery.picked_up".equals(event.eventType())));
    }


    @Test
    void advanceForRider_onAnotherRidersDelivery_shouldReturnNotFoundAndChangeNothing() {
        // lockByIdAndRiderId finds nothing: the delivery belongs to rider 3, not rider 4.
        Assertions.assertThrows(
                ResourceNotFoundException.class, () -> service.advanceForRider(4L, 7L, DeliveryStatus.PICKED_UP));

        Mockito.verify(deliveryRepository, Mockito.never())
                .compareAndSetStatus(Mockito.anyLong(), Mockito.anyString(), Mockito.any(), Mockito.anyString(),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
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


    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"ASSIGNED", "CANCELLED"})
    void advanceForRider_toAStatusNoRiderMoveReaches_shouldThrowWithoutWriting(DeliveryStatus target) {
        // A rider only picks up and delivers; cancelling is the order.cancelled consumer's (a programming error here).
        Mockito.when(deliveryRepository.lockByIdAndRiderId(7L, 3L)).thenReturn(delivery(7L, "ASSIGNED"));

        IllegalArgumentException refused = Assertions.assertThrows(
                IllegalArgumentException.class, () -> service.advanceForRider(3L, 7L, target));

        Assertions.assertEquals("A rider cannot move a delivery to " + target, refused.getMessage());
        Mockito.verify(deliveryRepository).lockByIdAndRiderId(7L, 3L);
        Mockito.verifyNoMoreInteractions(deliveryRepository);
        Mockito.verifyNoInteractions(riderRepository, outboxService);
    }


    /**
     * The 409 text of a refused delivery move, the same through the admin action and the rider API: the current status
     * and the target.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            DELIVERED | PICKED_UP | Delivery is DELIVERED; it cannot move to PICKED_UP
            ASSIGNED  | DELIVERED | Delivery is ASSIGNED; it cannot move to DELIVERED
            CANCELLED | PICKED_UP | Delivery is CANCELLED; it cannot move to PICKED_UP
            CANCELLED | DELIVERED | Delivery is CANCELLED; it cannot move to DELIVERED
            """)
    void advance_illegalMoves_shouldConflictWithTheExactMessage(
            String currentStatus,
            DeliveryStatus target,
            String expectedMessage
    ) {
        Mockito.when(deliveryRepository.lockById(7L)).thenReturn(delivery(7L, currentStatus));
        Mockito.when(deliveryRepository.lockByIdAndRiderId(7L, 3L)).thenReturn(delivery(7L, currentStatus));
        Function<Long, DeliveryDto> adminAction =
                target == DeliveryStatus.PICKED_UP ? service::markPickedUp : service::markDelivered;

        ConflictException admin = Assertions.assertThrows(ConflictException.class, () -> adminAction.apply(7L));
        ConflictException rider = Assertions.assertThrows(
                ConflictException.class, () -> service.advanceForRider(3L, 7L, target));

        for (ConflictException conflict : List.of(admin, rider)) {
            Assertions.assertEquals("DELIVERY_STATUS_CONFLICT", conflict.getCode());
            Assertions.assertEquals(expectedMessage, conflict.getMessage());
        }
        Mockito.verify(deliveryRepository, Mockito.never())
                .compareAndSetStatus(Mockito.anyLong(), Mockito.anyString(), Mockito.any(), Mockito.anyString(),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verifyNoInteractions(riderRepository, outboxService);
    }


    @Test
    void reassign_shouldMoveTheOpenDeliveryAndItsLoadToTheNewRiderAndPublishDeliveryReassigned() {
        Mockito.when(deliveryRepository.lockById(7L)).thenReturn(delivery(7L, "PICKED_UP"));
        Mockito.when(riderRepository.lockById(3L)).thenReturn(rider(3L, "Alice"));
        Mockito.when(riderRepository.lockById(4L)).thenReturn(rider(4L, "Bob"));
        Mockito.when(deliveryRepository.compareAndSetRider(
                Mockito.eq(7L), Mockito.eq(3L), Mockito.eq(4L), Mockito.any())).thenReturn(1);

        DeliveryDto reassigned = service.reassign(7L, 4L);

        Assertions.assertEquals(4L, reassigned.riderId());
        Assertions.assertEquals("Bob", reassigned.riderName());
        Assertions.assertEquals("PICKED_UP", reassigned.status(), "the delivery keeps its status");
        Assertions.assertNotNull(reassigned.pickedUpAt());
        // The delivery first, then both riders in id order.
        org.mockito.InOrder lockOrder = Mockito.inOrder(deliveryRepository, riderRepository);
        lockOrder.verify(deliveryRepository).lockById(7L);
        lockOrder.verify(riderRepository).lockById(3L);
        lockOrder.verify(riderRepository).lockById(4L);
        Mockito.verify(deliveryRepository)
                .compareAndSetRider(Mockito.eq(7L), Mockito.eq(3L), Mockito.eq(4L), Mockito.any());
        Mockito.verify(riderRepository).decrementLoad(Mockito.eq(3L), Mockito.any());
        Mockito.verify(riderRepository).incrementLoad(Mockito.eq(4L), Mockito.any());
        Mockito.verify(deliveryRepository, Mockito.never())
                .compareAndSetStatus(Mockito.anyLong(), Mockito.anyString(), Mockito.any(), Mockito.anyString(),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        ArgumentCaptor<EventEnvelope> envelope = ArgumentCaptor.forClass(EventEnvelope.class);
        Mockito.verify(outboxService).enqueueEvent(
                Mockito.eq("delivery-events"),
                Mockito.eq(String.valueOf(ORDER_ID)),
                envelope.capture()
        );
        DeliveryEventEnvelope event = (DeliveryEventEnvelope) envelope.getValue();
        Assertions.assertEquals("DELIVERY", event.aggregateType());
        Assertions.assertEquals(7L, event.aggregateId());
        Assertions.assertEquals("delivery.reassigned", event.eventType());
        Assertions.assertEquals(4L, event.data().riderId());
        Assertions.assertEquals("Bob", event.data().riderName());
        Assertions.assertEquals("PICKED_UP", event.data().status());
    }


    @Test
    void reassign_toALowerRiderId_shouldStillLockTheRidersInIdOrder() {
        Mockito.when(deliveryRepository.lockById(7L)).thenReturn(delivery(7L, "ASSIGNED"));
        Mockito.when(riderRepository.lockById(2L)).thenReturn(rider(2L, "Carol"));
        Mockito.when(deliveryRepository.compareAndSetRider(
                Mockito.eq(7L), Mockito.eq(3L), Mockito.eq(2L), Mockito.any())).thenReturn(1);

        Assertions.assertEquals(2L, service.reassign(7L, 2L).riderId());

        org.mockito.InOrder lockOrder = Mockito.inOrder(riderRepository);
        lockOrder.verify(riderRepository).lockById(2L);
        lockOrder.verify(riderRepository).lockById(3L);
        Mockito.verify(riderRepository).decrementLoad(Mockito.eq(3L), Mockito.any());
        Mockito.verify(riderRepository).incrementLoad(Mockito.eq(2L), Mockito.any());
    }


    @Test
    void reassign_whenTheCompareAndSetChangesNoRow_shouldThrowBeforeMovingTheLoadOrPublishing() {
        Mockito.when(deliveryRepository.lockById(7L)).thenReturn(delivery(7L, "ASSIGNED"));
        Mockito.when(riderRepository.lockById(3L)).thenReturn(rider(3L, "Alice"));
        Mockito.when(riderRepository.lockById(4L)).thenReturn(rider(4L, "Bob"));
        Mockito.when(deliveryRepository.compareAndSetRider(
                Mockito.eq(7L), Mockito.eq(3L), Mockito.eq(4L), Mockito.any())).thenReturn(0);

        StaleTransitionException stale = Assertions.assertThrows(
                StaleTransitionException.class, () -> service.reassign(7L, 4L));

        Assertions.assertEquals(
                "Compare-and-set of delivery 7 from ASSIGNED with rider 3 changed 0 rows instead of 1",
                stale.getMessage()
        );
        Mockito.verify(riderRepository, Mockito.never()).decrementLoad(Mockito.anyLong(), Mockito.any());
        Mockito.verify(riderRepository, Mockito.never()).incrementLoad(Mockito.anyLong(), Mockito.any());
        Mockito.verifyNoInteractions(outboxService);
    }


    @Test
    void reassign_toTheCurrentRider_shouldReturnTheDeliveryUnchanged() {
        Mockito.when(deliveryRepository.lockById(7L)).thenReturn(delivery(7L, "ASSIGNED"));
        Mockito.when(riderRepository.findById(3L)).thenReturn(Optional.of(rider(3L, "Alice")));

        DeliveryDto unchanged = service.reassign(7L, 3L);

        Assertions.assertEquals(3L, unchanged.riderId());
        Mockito.verify(deliveryRepository, Mockito.never())
                .compareAndSetRider(Mockito.anyLong(), Mockito.any(), Mockito.anyLong(), Mockito.any());
        Mockito.verify(riderRepository, Mockito.never()).lockById(Mockito.anyLong());
        Mockito.verifyNoInteractions(outboxService);
    }


    @Test
    void reassign_toTheCurrentRiderOfADeliveredDelivery_shouldReturnItUnchanged() {
        Mockito.when(deliveryRepository.lockById(7L)).thenReturn(delivery(7L, "DELIVERED"));
        Mockito.when(riderRepository.findById(3L)).thenReturn(Optional.of(rider(3L, "Alice")));

        // The same-rider check comes before the status check: a retried reassignment is answered even after the
        // rider finished the delivery, where a reassignment to another rider would be refused.
        DeliveryDto unchanged = service.reassign(7L, 3L);

        Assertions.assertEquals(3L, unchanged.riderId());
        Assertions.assertEquals("Alice", unchanged.riderName());
        Assertions.assertEquals("DELIVERED", unchanged.status());
        Assertions.assertNotNull(unchanged.deliveredAt());
        Mockito.verify(deliveryRepository).lockById(7L);
        Mockito.verifyNoMoreInteractions(deliveryRepository);
        Mockito.verify(riderRepository, Mockito.never()).lockById(Mockito.anyLong());
        Mockito.verify(riderRepository, Mockito.never()).decrementLoad(Mockito.anyLong(), Mockito.any());
        Mockito.verify(riderRepository, Mockito.never()).incrementLoad(Mockito.anyLong(), Mockito.any());
        Mockito.verifyNoInteractions(outboxService);
    }


    @Test
    void reassign_ofAFinishedDelivery_shouldConflict() {
        Mockito.when(deliveryRepository.lockById(7L)).thenReturn(delivery(7L, "DELIVERED"), delivery(7L, "CANCELLED"));

        for (int attempt = 0; attempt < 2; attempt++) {
            ConflictException conflict = Assertions.assertThrows(ConflictException.class, () -> service.reassign(7L, 4L));
            Assertions.assertEquals("DELIVERY_STATUS_CONFLICT", conflict.getCode());
        }

        Mockito.verifyNoInteractions(riderRepository, outboxService);
    }


    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            DELIVERED | Delivery is DELIVERED; only an open delivery can be reassigned
            CANCELLED | Delivery is CANCELLED; only an open delivery can be reassigned
            """)
    void reassign_ofAFinishedDelivery_shouldConflictWithTheExactMessage(String status, String expectedMessage) {
        Mockito.when(deliveryRepository.lockById(7L)).thenReturn(delivery(7L, status));

        ConflictException conflict = Assertions.assertThrows(ConflictException.class, () -> service.reassign(7L, 4L));

        Assertions.assertEquals("DELIVERY_STATUS_CONFLICT", conflict.getCode());
        Assertions.assertEquals(expectedMessage, conflict.getMessage());
        Mockito.verify(deliveryRepository).lockById(7L);
        Mockito.verifyNoMoreInteractions(deliveryRepository);
        Mockito.verifyNoInteractions(riderRepository, outboxService);
    }


    /**
     * Either flag makes the rider undispatchable; the text names the requested rider. Inactive and on duty does not occur
     * in the riders table (an inactive rider is kept off duty) but is refused the same way.
     */
    @ParameterizedTest
    @CsvSource({
            "true, false",
            "false, false",
            "false, true"
    })
    void reassign_toARiderThatIsNotDispatchable_shouldConflictWithTheExactMessage(boolean active, boolean onDuty) {
        LocalDateTime now = LocalDateTime.now();
        Mockito.when(deliveryRepository.lockById(7L)).thenReturn(delivery(7L, "PICKED_UP"));
        Mockito.when(riderRepository.lockById(3L)).thenReturn(rider(3L, "Alice"));
        Mockito.when(riderRepository.lockById(9L)).thenReturn(new RiderEntity(9L, null, "Dan", null, active, onDuty, 0, now, now));

        ConflictException conflict = Assertions.assertThrows(ConflictException.class, () -> service.reassign(7L, 9L));

        Assertions.assertEquals("RIDER_NOT_DISPATCHABLE", conflict.getCode());
        Assertions.assertEquals("Rider 9 is not active and on duty", conflict.getMessage());
        Mockito.verify(deliveryRepository).lockById(7L);
        Mockito.verifyNoMoreInteractions(deliveryRepository);
        Mockito.verify(riderRepository, Mockito.never()).decrementLoad(Mockito.anyLong(), Mockito.any());
        Mockito.verify(riderRepository, Mockito.never()).incrementLoad(Mockito.anyLong(), Mockito.any());
        Mockito.verifyNoInteractions(outboxService);
    }


    @Test
    void reassign_toAnInactiveOffDutyOrUnknownRider_shouldChangeNothing() {
        LocalDateTime now = LocalDateTime.now();
        Mockito.when(deliveryRepository.lockById(7L)).thenReturn(delivery(7L, "ASSIGNED"));
        Mockito.when(riderRepository.lockById(3L)).thenReturn(rider(3L, "Alice"));
        Mockito.when(riderRepository.lockById(4L)).thenReturn(
                new RiderEntity(4L, null, "Bob", null, true, false, 0, now, now),
                new RiderEntity(4L, null, "Bob", null, false, false, 0, now, now),
                null
        );

        for (int attempt = 0; attempt < 2; attempt++) {
            ConflictException conflict = Assertions.assertThrows(ConflictException.class, () -> service.reassign(7L, 4L));
            Assertions.assertEquals("RIDER_NOT_DISPATCHABLE", conflict.getCode());
        }
        Assertions.assertThrows(ResourceNotFoundException.class, () -> service.reassign(7L, 4L));

        Mockito.verify(deliveryRepository, Mockito.never())
                .compareAndSetRider(Mockito.anyLong(), Mockito.any(), Mockito.anyLong(), Mockito.any());
        Mockito.verify(riderRepository, Mockito.never()).decrementLoad(Mockito.anyLong(), Mockito.any());
        Mockito.verify(riderRepository, Mockito.never()).incrementLoad(Mockito.anyLong(), Mockito.any());
        Mockito.verifyNoInteractions(outboxService);
    }


    @Test
    void reassign_ofAnUnknownDelivery_shouldReturnNotFound() {
        Assertions.assertThrows(ResourceNotFoundException.class, () -> service.reassign(99L, 4L));

        Mockito.verifyNoInteractions(riderRepository, outboxService);
    }


    @Test
    void getDeliveries_forARider_shouldListTheirOpenDeliveries() {
        Mockito.when(deliveryRepository.findByRiderIdAndStatuses(3L, java.util.List.of("ASSIGNED", "PICKED_UP"), 50))
                .thenReturn(java.util.List.of(delivery(7L, "ASSIGNED")));
        Mockito.when(riderRepository.findById(3L)).thenReturn(Optional.of(rider(3L, "Alice")));

        java.util.List<DeliveryDto> deliveries = service.getDeliveries(null, 3L, 50);

        Assertions.assertEquals(1, deliveries.size());
        Assertions.assertEquals("Alice", deliveries.get(0).riderName());
        Mockito.verify(deliveryRepository, Mockito.never()).findRecent(Mockito.anyInt());
    }


    private void openDispatchOrder() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 4, 4, 12, 0);
        Mockito.when(dispatchOrderRepository.lockByOrderId(ORDER_ID))
                .thenReturn(new DispatchOrderEntity(ORDER_ID, null, createdAt, createdAt));
    }


    /**
     * order.cancelled for an order whose delivery is already finished ({@code finished}), or that has none (null): the
     * tombstone is set before the delivery is locked, and the delivery is neither written nor cancelled, no rider is
     * released and no delivery event is published.
     */
    private void assertOrderCancelledOnlySetsTheTombstone(DeliveryEntity finished) {
        OrderEventEnvelope cancelled = orderEvent("order.cancelled", ORDER_ID, 2L, "CANCELLED");
        Mockito.when(processedEventService.markIfUnseen("dispatch-consumer", cancelled)).thenReturn(true);
        openDispatchOrder();
        Mockito.when(deliveryRepository.lockByOrderId(ORDER_ID)).thenReturn(finished);

        service.handle(cancelled);

        org.mockito.InOrder tombstone = Mockito.inOrder(dispatchOrderRepository, deliveryRepository);
        tombstone.verify(dispatchOrderRepository).insertIfAbsent(Mockito.eq(ORDER_ID), Mockito.any());
        tombstone.verify(dispatchOrderRepository).lockByOrderId(ORDER_ID);
        tombstone.verify(dispatchOrderRepository).markCancelled(Mockito.eq(ORDER_ID), Mockito.any());
        tombstone.verify(deliveryRepository).lockByOrderId(ORDER_ID);
        Mockito.verifyNoMoreInteractions(deliveryRepository, dispatchOrderRepository);
        Mockito.verifyNoInteractions(riderRepository, outboxService, metricsService);
    }


    private OrderEventEnvelope acceptedEvent() {
        return orderEvent("order.accepted", ORDER_ID, 2L, "ACCEPTED");
    }


    private RiderEntity rider(Long id, String name) {
        LocalDateTime now = LocalDateTime.now();
        return new RiderEntity(id, null, name, null, true, true, 0, now, now);
    }


    private DeliveryEntity delivery(Long id, String status, Long riderId) {
        DeliveryEntity delivery = delivery(id, status);
        return new DeliveryEntity(
                delivery.id(),
                delivery.orderId(),
                delivery.orderNo(),
                delivery.customerId(),
                delivery.restaurantId(),
                riderId,
                delivery.status(),
                delivery.assignedAt(),
                delivery.pickedUpAt(),
                delivery.deliveredAt(),
                delivery.cancelledAt(),
                delivery.updatedAt()
        );
    }


    /**
     * {@code delivery} with the order number {@link #ORDER_NO}.
     */
    private static DeliveryEntity numbered(DeliveryEntity delivery) {
        return new DeliveryEntity(
                delivery.id(),
                delivery.orderId(),
                ORDER_NO,
                delivery.customerId(),
                delivery.restaurantId(),
                delivery.riderId(),
                delivery.status(),
                delivery.assignedAt(),
                delivery.pickedUpAt(),
                delivery.deliveredAt(),
                delivery.cancelledAt(),
                delivery.updatedAt()
        );
    }


    private DeliveryEntity delivery(Long id, String status) {
        LocalDateTime assignedAt = LocalDateTime.of(2026, 4, 4, 12, 0);
        return new DeliveryEntity(
                id,
                ORDER_ID,
                null,
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
