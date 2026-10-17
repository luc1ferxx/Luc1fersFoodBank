package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.ConsumerAggregateSequenceEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;


public interface ConsumerAggregateSequenceRepository extends ListCrudRepository<ConsumerAggregateSequenceEntity, Long> {

    /**
     * Records the first event a consumer applies for an aggregate. Target-less ON CONFLICT (the form H2 parses); the
     * only unique constraint besides the serial key is uq_consumer_aggregate_sequence.
     *
     * @return 1 when inserted, 0 when the consumer already has a row for the aggregate
     */
    @Modifying
    @Query("""
            INSERT INTO consumer_aggregate_sequences (
                consumer_name,
                aggregate_type,
                aggregate_id,
                last_sequence,
                updated_at
            ) VALUES (
                :consumerName,
                :aggregateType,
                :aggregateId,
                :sequence,
                :updatedAt
            )
            ON CONFLICT DO NOTHING
            """)
    int insertIfAbsent(
            String consumerName,
            String aggregateType,
            Long aggregateId,
            Long sequence,
            LocalDateTime updatedAt
    );


    /**
     * Moves the consumer's position for the aggregate to {@code sequence} if that is newer.
     *
     * @return 1 when moved, 0 when the consumer already applied this or a later event of the aggregate
     */
    @Modifying
    @Query("""
            UPDATE consumer_aggregate_sequences
            SET last_sequence = :sequence,
                updated_at = :updatedAt
            WHERE consumer_name = :consumerName
              AND aggregate_type = :aggregateType
              AND aggregate_id = :aggregateId
              AND last_sequence < :sequence
            """)
    int advanceIfNewer(
            String consumerName,
            String aggregateType,
            Long aggregateId,
            Long sequence,
            LocalDateTime updatedAt
    );


    @Query("""
            SELECT last_sequence
            FROM consumer_aggregate_sequences
            WHERE consumer_name = :consumerName
              AND aggregate_type = :aggregateType
              AND aggregate_id = :aggregateId
            """)
    Long findLastSequence(String consumerName, String aggregateType, Long aggregateId);


    @Modifying
    @Query("""
            DELETE FROM consumer_aggregate_sequences
            WHERE id IN (
                SELECT id
                FROM consumer_aggregate_sequences
                WHERE updated_at < :updatedBefore
                ORDER BY updated_at, id
                LIMIT :batchSize
            )
            """)
    int deleteByUpdatedAtBefore(LocalDateTime updatedBefore, int batchSize);
}
