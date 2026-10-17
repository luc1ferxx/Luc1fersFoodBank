package com.laioffer.onlineorder.repository;


import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;


import java.sql.Timestamp;
import java.time.LocalDateTime;


/**
 * {@link ProcessedEventDedup} for both databases.
 *
 * <p>PostgreSQL: processed_events is partitioned by month on processed_at (V10). A unique constraint of a partitioned
 * table must include the partition key, so no constraint can make (consumer_name, dedup_key) unique across months: a
 * DLT replay processed weeks after the first delivery would land in another partition. Uniqueness is serialized
 * instead, in the caller's transaction (one is started when there is none):
 * <ol>
 *     <li>{@code pg_advisory_xact_lock(namespace, hashtext(consumer_name, dedup_key))}: a second transaction with the
 *     same key waits here until the first one commits or rolls back, just as it would wait on a unique index entry.
 *     A hash collision between two keys only makes them wait for each other.</li>
 *     <li>{@code INSERT ... WHERE NOT EXISTS} over all partitions (each partition's unique index on
 *     (consumer_name, dedup_key) answers the probe). Under READ COMMITTED the statement takes a new snapshot after
 *     the lock was granted, so it sees the row the first transaction committed.</li>
 * </ol>
 * The per-partition unique index still rejects a duplicate within one month at the database level ({@code ON CONFLICT
 * DO NOTHING}, reported as already seen), even for a writer that skipped the lock. REPEATABLE READ or SERIALIZABLE
 * would take the snapshot before the lock wait, so they are refused.
 *
 * <p>H2 (the {@code h2} profile) keeps the plain table and its UNIQUE (consumer_name, dedup_key) constraint, so a
 * target-less {@code ON CONFLICT DO NOTHING} is enough there (H2 in PostgreSQL mode only parses the target-less form).
 */
public class ProcessedEventDedupImpl implements ProcessedEventDedup {

    /**
     * First key of the two-key advisory lock: "proc". The two-key and the one-key (bigint) lock spaces do not overlap.
     */
    static final int ADVISORY_LOCK_NAMESPACE = 0x70726f63;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private volatile Boolean postgres;


    public ProcessedEventDedupImpl(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }


    @Override
    public int insertIfAbsent(
            String consumerName,
            String eventId,
            String dedupKey,
            String eventType,
            String aggregateType,
            Long aggregateId,
            LocalDateTime processedAt
    ) {
        Timestamp processedAtTimestamp = Timestamp.valueOf(processedAt);
        if (!isPostgres()) {
            return jdbcTemplate.update("""
                            INSERT INTO processed_events (
                                consumer_name, event_id, dedup_key, event_type, aggregate_type, aggregate_id, processed_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT DO NOTHING
                            """,
                    consumerName, eventId, dedupKey, eventType, aggregateType, aggregateId, processedAtTimestamp);
        }
        Integer inserted = transactionTemplate.execute(status -> {
            requireReadCommitted();
            jdbcTemplate.query(
                    "SELECT pg_advisory_xact_lock(?, hashtext(? || chr(31) || ?))",
                    (ResultSetExtractor<Object>) resultSet -> null,
                    ADVISORY_LOCK_NAMESPACE,
                    consumerName,
                    dedupKey
            );
            return jdbcTemplate.update("""
                            INSERT INTO processed_events (
                                consumer_name, event_id, dedup_key, event_type, aggregate_type, aggregate_id, processed_at
                            )
                            SELECT CAST(? AS TEXT), CAST(? AS TEXT), CAST(? AS TEXT), CAST(? AS TEXT), CAST(? AS TEXT),
                                   CAST(? AS BIGINT), CAST(? AS TIMESTAMP)
                            WHERE NOT EXISTS (
                                SELECT 1
                                FROM processed_events
                                WHERE consumer_name = ?
                                  AND dedup_key = ?
                            )
                            ON CONFLICT DO NOTHING
                            """,
                    consumerName, eventId, dedupKey, eventType, aggregateType, aggregateId, processedAtTimestamp,
                    consumerName, dedupKey);
        });
        return inserted == null ? 0 : inserted;
    }


    private static void requireReadCommitted() {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (isolation != null
                && isolation != TransactionDefinition.ISOLATION_DEFAULT
                && isolation != TransactionDefinition.ISOLATION_READ_COMMITTED
                && isolation != TransactionDefinition.ISOLATION_READ_UNCOMMITTED) {
            throw new IllegalStateException(
                    "processed_events deduplication needs READ COMMITTED; the transaction uses isolation " + isolation
            );
        }
    }


    private boolean isPostgres() {
        Boolean known = postgres;
        if (known == null) {
            known = Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection ->
                    "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())));
            postgres = known;
        }
        return known;
    }
}
