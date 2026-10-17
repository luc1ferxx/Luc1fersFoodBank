package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.repository.OutboxAggregateSequenceRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;


/**
 * H2 parses neither ON CONFLICT DO UPDATE nor RETURNING, so the counter is stepped with an UPDATE (which locks the
 * row) and read back; an aggregate's first event inserts it instead.
 */
@Service
@Profile("h2")
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
            // A concurrent transaction started the counter first; step it after that one commits.
            repository.incrementSequence(aggregateType, aggregateId);
        }
        Long sequence = repository.findLastSequence(aggregateType, aggregateId);
        if (sequence == null) {
            throw new IllegalStateException("No outbox sequence for " + aggregateType + " " + aggregateId);
        }
        return sequence;
    }
}
