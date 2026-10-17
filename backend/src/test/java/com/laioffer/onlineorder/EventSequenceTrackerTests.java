package com.laioffer.onlineorder;


import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.ConsumerAggregateSequenceRepository;
import com.laioffer.onlineorder.service.EventSequenceTracker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;


import java.time.LocalDateTime;


import static com.laioffer.onlineorder.fulfilment.FulfilmentTestEvents.orderEvent;


/**
 * The statements behind stale-event detection; StaleEventPostgresIntegrationTests runs them on PostgreSQL.
 */
class EventSequenceTrackerTests {

    private final ConsumerAggregateSequenceRepository repository = Mockito.mock(ConsumerAggregateSequenceRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final EventSequenceTracker tracker =
            new EventSequenceTracker(repository, new ApplicationMetricsService(meterRegistry));


    @Test
    void anEventWithoutASequence_shouldBeAppliedWithoutTouchingThePosition() {
        Assertions.assertTrue(tracker.advance("c", orderEvent("order.paid", 7L, 1L, "PAID")));

        Mockito.verifyNoInteractions(repository);
    }


    @Test
    void aNewerEvent_shouldMoveThePosition() {
        Mockito.when(repository.advanceIfNewer(Mockito.eq("c"), Mockito.eq("ORDER"), Mockito.eq(7L), Mockito.eq(3L),
                Mockito.any(LocalDateTime.class))).thenReturn(1);

        Assertions.assertTrue(tracker.advance("c", orderEvent("order.accepted", 7L, 1L, "ACCEPTED", 3)));

        Mockito.verify(repository, Mockito.never()).insertIfAbsent(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyLong(), Mockito.any());
    }


    @Test
    void theFirstEventOfAnAggregate_shouldInsertThePosition() {
        Mockito.when(repository.insertIfAbsent(Mockito.eq("c"), Mockito.eq("ORDER"), Mockito.eq(7L), Mockito.eq(1L),
                Mockito.any(LocalDateTime.class))).thenReturn(1);

        Assertions.assertTrue(tracker.advance("c", orderEvent("order.paid", 7L, 1L, "PAID", 1)));
    }


    @Test
    void whenAConcurrentConsumerInsertedThePositionFirst_shouldCompareAgainstIt() {
        Mockito.when(repository.advanceIfNewer(Mockito.eq("c"), Mockito.eq("ORDER"), Mockito.eq(7L), Mockito.eq(2L),
                Mockito.any(LocalDateTime.class))).thenReturn(0, 1);

        Assertions.assertTrue(tracker.advance("c", orderEvent("order.accepted", 7L, 1L, "ACCEPTED", 2)));

        Mockito.verify(repository, Mockito.times(2)).advanceIfNewer(
                Mockito.eq("c"), Mockito.eq("ORDER"), Mockito.eq(7L), Mockito.eq(2L), Mockito.any(LocalDateTime.class));
    }


    @Test
    void aStaleEvent_shouldBeSkippedAndCounted() {
        Mockito.when(repository.findLastSequence("c", "ORDER", 7L)).thenReturn(5L);

        Assertions.assertFalse(tracker.advance("c", orderEvent("order.paid", 7L, 1L, "PAID", 2)));

        Assertions.assertEquals(1.0, meterRegistry.get("onlineorder.events.stale_skipped")
                .tag("consumer", "c")
                .tag("event_type", "order.paid")
                .counter()
                .count());
    }
}
