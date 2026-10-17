package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.repository.OutboxAggregateSequenceRepository;


/**
 * One upsert per event (INSERT ... ON CONFLICT DO UPDATE ... RETURNING), which takes the counter row lock. Chosen by
 * {@link OutboxSequenceAllocatorConfig} when the database is PostgreSQL.
 */
public class PostgresOutboxSequenceAllocator implements OutboxSequenceAllocator {

    private final OutboxAggregateSequenceRepository repository;


    public PostgresOutboxSequenceAllocator(OutboxAggregateSequenceRepository repository) {
        this.repository = repository;
    }


    @Override
    public long next(String aggregateType, Long aggregateId) {
        Long sequence = repository.upsertNextSequence(aggregateType, aggregateId);
        if (sequence == null) {
            throw new IllegalStateException("No outbox sequence for " + aggregateType + " " + aggregateId);
        }
        return sequence;
    }
}
