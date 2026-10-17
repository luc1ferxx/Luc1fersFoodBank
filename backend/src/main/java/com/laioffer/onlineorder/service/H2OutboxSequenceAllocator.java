package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.repository.OutboxAggregateSequenceRepository;


/**
 * H2 parses neither ON CONFLICT DO UPDATE nor RETURNING, so the counter is stepped with an UPDATE (which locks the
 * row) and read back; an aggregate's first event inserts it instead. Two transactions that start the same counter
 * take turns like on PostgreSQL: H2 makes the second INSERT wait on the unique constraint until the first transaction
 * ends (up to its LOCK_TIMEOUT), then it inserts (the first rolled back) or finds the row and steps it
 * (OutboxSequenceAllocatorH2Tests). Chosen by {@link OutboxSequenceAllocatorConfig} when the database is H2.
 */
public class H2OutboxSequenceAllocator implements OutboxSequenceAllocator {

    private final OutboxAggregateSequenceRepository repository;


    public H2OutboxSequenceAllocator(OutboxAggregateSequenceRepository repository) {
        this.repository = repository;
    }


    @Override
    public long next(String aggregateType, Long aggregateId) {
        if (repository.incrementSequence(aggregateType, aggregateId) == 0) {
            if (repository.insertFirstSequence(aggregateType, aggregateId) == 1) {
                return 1L;
            }
            // A concurrent transaction started the counter first and has committed it (the INSERT waited for it).
            if (repository.incrementSequence(aggregateType, aggregateId) == 0) {
                throw new IllegalStateException(
                        "The outbox sequence of " + aggregateType + " " + aggregateId + " exists but cannot be stepped"
                );
            }
        }
        Long sequence = repository.findLastSequence(aggregateType, aggregateId);
        if (sequence == null) {
            throw new IllegalStateException("No outbox sequence for " + aggregateType + " " + aggregateId);
        }
        return sequence;
    }
}
