package com.laioffer.onlineorder;


import com.laioffer.onlineorder.repository.OutboxAggregateSequenceRepository;
import com.laioffer.onlineorder.service.H2OutboxSequenceAllocator;
import com.laioffer.onlineorder.service.PostgresOutboxSequenceAllocator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;


/**
 * The statements each allocator runs; OutboxAggregateOrderingPostgresTests runs the PostgreSQL one under concurrency and
 * the h2 tests the H2 one through checkouts.
 */
class OutboxSequenceAllocatorTests {

    private final OutboxAggregateSequenceRepository repository = Mockito.mock(OutboxAggregateSequenceRepository.class);


    @Test
    void postgres_shouldTakeTheSequenceTheUpsertReturns() {
        Mockito.when(repository.upsertNextSequence("ORDER", 7L)).thenReturn(4L);

        Assertions.assertEquals(4L, new PostgresOutboxSequenceAllocator(repository).next("ORDER", 7L));
    }


    @Test
    void postgres_whenTheUpsertReturnsNothing_shouldFail() {
        Mockito.when(repository.upsertNextSequence("ORDER", 7L)).thenReturn(null);

        Assertions.assertThrows(
                IllegalStateException.class, () -> new PostgresOutboxSequenceAllocator(repository).next("ORDER", 7L));
    }


    @Test
    void h2_forAnAggregateWithACounter_shouldStepItAndReadItBack() {
        Mockito.when(repository.incrementSequence("ORDER", 7L)).thenReturn(1);
        Mockito.when(repository.findLastSequence("ORDER", 7L)).thenReturn(5L);

        Assertions.assertEquals(5L, new H2OutboxSequenceAllocator(repository).next("ORDER", 7L));
        Mockito.verify(repository, Mockito.never()).insertFirstSequence(Mockito.anyString(), Mockito.anyLong());
    }


    @Test
    void h2_forTheFirstEventOfAnAggregate_shouldStartTheCounterAtOne() {
        Mockito.when(repository.insertFirstSequence("DELIVERY", 3L)).thenReturn(1);

        Assertions.assertEquals(1L, new H2OutboxSequenceAllocator(repository).next("DELIVERY", 3L));
        Mockito.verify(repository, Mockito.never()).findLastSequence(Mockito.anyString(), Mockito.anyLong());
    }


    @Test
    void h2_whenAConcurrentTransactionStartedTheCounterFirst_shouldStepItAfterwards() {
        Mockito.when(repository.incrementSequence("DELIVERY", 3L)).thenReturn(0, 1);
        Mockito.when(repository.insertFirstSequence("DELIVERY", 3L)).thenReturn(0);
        Mockito.when(repository.findLastSequence("DELIVERY", 3L)).thenReturn(2L);

        Assertions.assertEquals(2L, new H2OutboxSequenceAllocator(repository).next("DELIVERY", 3L));
        InOrder order = Mockito.inOrder(repository);
        order.verify(repository).incrementSequence("DELIVERY", 3L);
        order.verify(repository).insertFirstSequence("DELIVERY", 3L);
        order.verify(repository).incrementSequence("DELIVERY", 3L);
        order.verify(repository).findLastSequence("DELIVERY", 3L);
    }


    @Test
    void h2_whenTheCounterCannotBeReadBack_shouldFail() {
        Mockito.when(repository.incrementSequence("ORDER", 7L)).thenReturn(1);
        Mockito.when(repository.findLastSequence("ORDER", 7L)).thenReturn(null);

        Assertions.assertThrows(
                IllegalStateException.class, () -> new H2OutboxSequenceAllocator(repository).next("ORDER", 7L));
    }
}
