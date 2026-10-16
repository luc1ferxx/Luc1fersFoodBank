package com.laioffer.onlineorder.fulfilment.dispatch;


import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;


import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;


@ExtendWith(MockitoExtension.class)
class RiderServiceTests {

    private static final long CUSTOMER_ID = 50L;
    private static final long RIDER_ID = 5L;

    @Mock
    private RiderRepository riderRepository;

    @Mock
    private DeliveryRepository deliveryRepository;

    @Mock
    private DispatchService dispatchService;

    private RiderService service;


    @BeforeEach
    void setup() {
        service = new RiderService(riderRepository, deliveryRepository, dispatchService);
    }


    @Test
    void create_shouldBindTheLoginToAnActiveRiderWhoStartsOffDuty() {
        Mockito.when(riderRepository.save(Mockito.any(RiderEntity.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0)));

        RiderEntity rider = service.create(CUSTOMER_ID, "Dana Rider", "(408) 555-0199");

        ArgumentCaptor<RiderEntity> saved = ArgumentCaptor.forClass(RiderEntity.class);
        Mockito.verify(riderRepository).save(saved.capture());
        Assertions.assertNull(saved.getValue().id());
        Assertions.assertEquals(CUSTOMER_ID, saved.getValue().customerId());
        Assertions.assertTrue(saved.getValue().active());
        Assertions.assertFalse(saved.getValue().onDuty());
        Assertions.assertEquals(0, saved.getValue().activeDeliveries());
        Assertions.assertEquals(RIDER_ID, rider.id());
    }


    @Test
    void requireActiveRider_shouldForbidUnboundAndDeactivatedLogins() {
        Mockito.when(riderRepository.findByCustomerId(CUSTOMER_ID)).thenReturn(null, rider(false, false));

        Assertions.assertThrows(AccessDeniedException.class, () -> service.requireActiveRider(CUSTOMER_ID));
        Assertions.assertThrows(AccessDeniedException.class, () -> service.requireActiveRider(CUSTOMER_ID));
        Assertions.assertThrows(AccessDeniedException.class, () -> service.requireActiveRider(null));
    }


    @Test
    void setOnDuty_shouldOnlyWriteAChange() {
        Mockito.when(riderRepository.findByCustomerId(CUSTOMER_ID)).thenReturn(rider(true, false), rider(true, true));
        Mockito.when(riderRepository.updateOnDutyIfActive(Mockito.eq(RIDER_ID), Mockito.eq(true), Mockito.any()))
                .thenReturn(1);
        Mockito.when(riderRepository.findById(RIDER_ID)).thenReturn(Optional.of(rider(true, true)));

        Assertions.assertTrue(service.setOnDuty(CUSTOMER_ID, true).onDuty());
        Assertions.assertTrue(service.setOnDuty(CUSTOMER_ID, true).onDuty());

        Mockito.verify(riderRepository, Mockito.times(1))
                .updateOnDutyIfActive(Mockito.eq(RIDER_ID), Mockito.eq(true), Mockito.any());
    }


    @Test
    void setOnDuty_whenDeactivated_shouldBeForbidden() {
        Mockito.when(riderRepository.findByCustomerId(CUSTOMER_ID)).thenReturn(rider(false, false));

        Assertions.assertThrows(AccessDeniedException.class, () -> service.setOnDuty(CUSTOMER_ID, true));

        Mockito.verify(riderRepository, Mockito.never())
                .updateOnDutyIfActive(Mockito.anyLong(), Mockito.anyBoolean(), Mockito.any());
    }


    @Test
    void setOnDuty_whenDeactivatedAfterTheCheck_shouldBeForbiddenInsteadOfLeavingAnInactiveRiderOnDuty() {
        // The rider read as active, but the guarded write finds the row deactivated in between.
        Mockito.when(riderRepository.findByCustomerId(CUSTOMER_ID)).thenReturn(rider(true, false));
        Mockito.when(riderRepository.updateOnDutyIfActive(Mockito.eq(RIDER_ID), Mockito.eq(true), Mockito.any()))
                .thenReturn(0);

        Assertions.assertThrows(AccessDeniedException.class, () -> service.setOnDuty(CUSTOMER_ID, true));

        Mockito.verify(riderRepository, Mockito.never()).findById(Mockito.anyLong());
    }


    @Test
    void update_shouldReportAnUnknownRider() {
        Assertions.assertThrows(ResourceNotFoundException.class, () -> service.update(99L, false, null));

        Mockito.verify(riderRepository, Mockito.never())
                .updateStatus(Mockito.anyLong(), Mockito.anyBoolean(), Mockito.anyBoolean(), Mockito.any());
    }


    @Test
    void update_deactivating_shouldAlsoTakeTheRiderOffDuty() {
        Mockito.when(riderRepository.lockById(RIDER_ID)).thenReturn(rider(true, true));

        RiderEntity updated = service.update(RIDER_ID, false, null);

        Assertions.assertFalse(updated.active());
        Assertions.assertFalse(updated.onDuty());
        Mockito.verify(riderRepository).updateStatus(Mockito.eq(RIDER_ID), Mockito.eq(false), Mockito.eq(false), Mockito.any());
    }


    @Test
    void update_reactivatingAlone_shouldLeaveTheRiderOffDuty() {
        Mockito.when(riderRepository.lockById(RIDER_ID)).thenReturn(rider(false, false));

        RiderEntity updated = service.update(RIDER_ID, true, null);

        Assertions.assertTrue(updated.active());
        Assertions.assertFalse(updated.onDuty());
        Mockito.verify(riderRepository).updateStatus(Mockito.eq(RIDER_ID), Mockito.eq(true), Mockito.eq(false), Mockito.any());
    }


    @Test
    void update_onDuty_shouldPutAnActiveRiderOnDutyAndReactivateWithBothFlags() {
        Mockito.when(riderRepository.lockById(RIDER_ID)).thenReturn(rider(true, false), rider(false, false));

        Assertions.assertTrue(service.update(RIDER_ID, null, true).onDuty());
        RiderEntity reactivated = service.update(RIDER_ID, true, true);

        Assertions.assertTrue(reactivated.active());
        Assertions.assertTrue(reactivated.onDuty());
        Mockito.verify(riderRepository, Mockito.times(2))
                .updateStatus(Mockito.eq(RIDER_ID), Mockito.eq(true), Mockito.eq(true), Mockito.any());
    }


    @Test
    void update_shouldNeverLeaveAnInactiveRiderOnDuty() {
        Mockito.when(riderRepository.lockById(RIDER_ID)).thenReturn(rider(false, false), rider(true, true));

        ConflictException inactive = Assertions.assertThrows(
                ConflictException.class, () -> service.update(RIDER_ID, null, true));
        ConflictException contradictory = Assertions.assertThrows(
                ConflictException.class, () -> service.update(RIDER_ID, false, true));

        Assertions.assertEquals("RIDER_INACTIVE", inactive.getCode());
        Assertions.assertEquals("RIDER_INACTIVE", contradictory.getCode());
        Mockito.verify(riderRepository, Mockito.never())
                .updateStatus(Mockito.anyLong(), Mockito.anyBoolean(), Mockito.anyBoolean(), Mockito.any());
    }


    @Test
    void update_withoutAChange_shouldNotWrite() {
        Mockito.when(riderRepository.lockById(RIDER_ID)).thenReturn(rider(true, true));

        Assertions.assertTrue(service.update(RIDER_ID, true, true).onDuty());

        Mockito.verify(riderRepository, Mockito.never())
                .updateStatus(Mockito.anyLong(), Mockito.anyBoolean(), Mockito.anyBoolean(), Mockito.any());
    }


    @Test
    void getDeliveries_shouldOnlyQueryTheRidersOwnDeliveriesWithABoundedLimit() {
        Mockito.when(riderRepository.findByCustomerId(CUSTOMER_ID)).thenReturn(rider(true, true));

        service.getDeliveries(CUSTOMER_ID, EnumSet.of(DeliveryStatus.ASSIGNED, DeliveryStatus.PICKED_UP), 1000);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> statuses = ArgumentCaptor.forClass(Collection.class);
        Mockito.verify(deliveryRepository).findByRiderIdAndStatuses(Mockito.eq(RIDER_ID), statuses.capture(), Mockito.eq(100));
        Assertions.assertEquals(List.of("ASSIGNED", "PICKED_UP"), List.copyOf(statuses.getValue()));
    }


    @Test
    void riderActions_shouldGoThroughTheDispatchStateMachineWithTheRidersOwnId() {
        Mockito.when(riderRepository.findByCustomerId(CUSTOMER_ID)).thenReturn(rider(true, false));

        service.markPickedUp(CUSTOMER_ID, 7L);
        service.markDelivered(CUSTOMER_ID, 7L);

        Mockito.verify(dispatchService).advanceForRider(RIDER_ID, 7L, DeliveryStatus.PICKED_UP);
        Mockito.verify(dispatchService).advanceForRider(RIDER_ID, 7L, DeliveryStatus.DELIVERED);
    }


    private static RiderEntity rider(boolean active, boolean onDuty) {
        LocalDateTime now = LocalDateTime.now();
        return new RiderEntity(RIDER_ID, CUSTOMER_ID, "Dana Rider", null, active, onDuty, 0, now, now);
    }


    private static RiderEntity withId(RiderEntity rider) {
        return new RiderEntity(RIDER_ID, rider.customerId(), rider.name(), rider.phone(), rider.active(),
                rider.onDuty(), rider.activeDeliveries(), rider.createdAt(), rider.updatedAt());
    }
}
