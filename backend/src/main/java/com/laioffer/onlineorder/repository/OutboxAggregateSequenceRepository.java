package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.OutboxAggregateSequenceEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


/**
 * The per-aggregate outbox sequence counters (see {@code OutboxSequenceAllocator}). Every statement that hands out a
 * sequence locks the aggregate's row until the transaction ends, so two transactions writing events of the same
 * aggregate take turns and commit in sequence order.
 */
public interface OutboxAggregateSequenceRepository extends ListCrudRepository<OutboxAggregateSequenceEntity, Long> {

    /**
     * PostgreSQL: the next sequence of the aggregate in one statement (1 for its first event).
     */
    @Query("""
            INSERT INTO outbox_aggregate_sequences (aggregate_type, aggregate_id, last_sequence)
            VALUES (:aggregateType, :aggregateId, 1)
            ON CONFLICT (aggregate_type, aggregate_id)
            DO UPDATE SET last_sequence = outbox_aggregate_sequences.last_sequence + 1
            RETURNING last_sequence
            """)
    Long upsertNextSequence(String aggregateType, Long aggregateId);


    /**
     * H2: steps the counter of an aggregate that has one.
     *
     * @return 1 when the aggregate had a counter, 0 when it has none yet
     */
    @Modifying
    @Query("""
            UPDATE outbox_aggregate_sequences
            SET last_sequence = last_sequence + 1
            WHERE aggregate_type = :aggregateType
              AND aggregate_id = :aggregateId
            """)
    int incrementSequence(String aggregateType, Long aggregateId);


    /**
     * H2: starts the counter of an aggregate at 1, unless a concurrent transaction started it first. The target-less
     * ON CONFLICT is the form H2 parses; the only unique constraint besides the serial key is
     * uq_outbox_aggregate_sequence.
     *
     * @return 1 when this call started the counter, 0 when it already existed
     */
    @Modifying
    @Query("""
            INSERT INTO outbox_aggregate_sequences (aggregate_type, aggregate_id, last_sequence)
            VALUES (:aggregateType, :aggregateId, 1)
            ON CONFLICT DO NOTHING
            """)
    int insertFirstSequence(String aggregateType, Long aggregateId);


    @Query("""
            SELECT last_sequence
            FROM outbox_aggregate_sequences
            WHERE aggregate_type = :aggregateType
              AND aggregate_id = :aggregateId
            """)
    Long findLastSequence(String aggregateType, Long aggregateId);
}
