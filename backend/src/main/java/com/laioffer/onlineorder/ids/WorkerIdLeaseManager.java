package com.laioffer.onlineorder.ids;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;


import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;


/**
 * Worker ids leased from the database (app.ids.worker-id-source=lease), for instances that come and go, such as the
 * pods of a Kubernetes Deployment scaled by an autoscaler.
 *
 * <p>{@code snowflake_worker_leases} (V10_2) has one row per worker id 0-1022. On start the instance claims the lowest
 * row whose lease has expired ({@code FOR UPDATE SKIP LOCKED}, so instances starting together claim different rows),
 * then renews it every renew-interval on a thread of its own; the lease lasts app.ids.lease.ttl of database time from
 * the last renewal. On a graceful stop the row is released at once; a crashed instance's row is free again after the
 * TTL.
 *
 * <p>Fencing: each row also records {@code issued_until_ms}, a bound on the timestamps of the ids issued under it.
 * The holder never issues an id with a later timestamp (the generator's ceiling), and it raises the ceiling only after
 * the database has stored the higher bound. The next holder of the row reads the bound under the row lock and starts
 * after it (the generator's floor). Two holders of one worker id therefore use disjoint timestamp ranges, so their ids
 * cannot collide even when a paused or partitioned instance keeps running past its lease. If renewals fail (the
 * database is down) the holder stops issuing once its clock reaches the ceiling, about one TTL after the last renewal.
 * A renewal that finds the row taken over (another owner) closes the generator and claims another worker id.
 *
 * <p>PostgreSQL only (SKIP LOCKED); the h2 profile runs with a static worker id.
 */
public final class WorkerIdLeaseManager implements DistributedIdGenerator, SmartLifecycle {

    static final String THREAD_NAME = "snowflake-worker-lease";

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerIdLeaseManager.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Duration ttl;
    private final long ttlMillis;
    private final long renewIntervalMillis;
    private final long maxBackwardMillis;
    private final String owner;
    private final LongSupplier wallClock;
    private final SnowflakeIdGenerator.Sleeper sleeper;
    private final SnowflakeIdGenerator.Listener listener;
    private final Runnable onLeaseLost;

    private volatile Lease lease;
    // Guarded by this.
    private ScheduledExecutorService executor;
    // Only touched by the lease thread.
    private int consecutiveFailures;


    public WorkerIdLeaseManager(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            Duration ttl,
            Duration renewInterval,
            Duration maxClockBackward,
            String owner,
            SnowflakeIdGenerator.Listener listener,
            Runnable onLeaseLost
    ) {
        this(jdbcTemplate, transactionManager, ttl, renewInterval, maxClockBackward, owner, listener, onLeaseLost,
                System::currentTimeMillis, SnowflakeIdGenerator.Sleeper.THREAD_SLEEP);
    }


    WorkerIdLeaseManager(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            Duration ttl,
            Duration renewInterval,
            Duration maxClockBackward,
            String owner,
            SnowflakeIdGenerator.Listener listener,
            Runnable onLeaseLost,
            LongSupplier wallClock,
            SnowflakeIdGenerator.Sleeper sleeper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.ttl = ttl;
        this.ttlMillis = Math.max(1L, ttl.toMillis());
        long requestedRenewMillis = Math.max(1L, renewInterval.toMillis());
        // At least three renewal attempts per TTL, so one slow or failed renewal does not stop the generator.
        this.renewIntervalMillis = Math.min(requestedRenewMillis, Math.max(1L, ttlMillis / 3));
        if (this.renewIntervalMillis < requestedRenewMillis) {
            LOGGER.warn("app.ids.lease.renew-interval ({} ms) lowered to a third of the lease TTL ({} ms)",
                    requestedRenewMillis, this.renewIntervalMillis);
        }
        this.maxBackwardMillis = maxClockBackward.toMillis();
        this.owner = owner;
        this.listener = listener == null ? SnowflakeIdGenerator.Listener.NONE : listener;
        this.onLeaseLost = onLeaseLost == null ? () -> {
        } : onLeaseLost;
        this.wallClock = wallClock;
        this.sleeper = sleeper;
    }


    @Override
    public long nextId() {
        Lease current = lease;
        if (current == null) {
            throw new IdGeneratorUnavailableException("No Snowflake worker id lease is held");
        }
        return current.generator().nextId();
    }


    @Override
    public int workerId() {
        Lease current = lease;
        return current == null ? -1 : current.workerId();
    }


    String owner() {
        return owner;
    }


    /**
     * Claims a worker id; fails the start (and the application context) when none is free.
     */
    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        lease = acquire();
        LOGGER.info("Leased Snowflake worker id {} as {}", lease.workerId(), owner);
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::maintain, renewIntervalMillis, renewIntervalMillis, TimeUnit.MILLISECONDS);
    }


    /**
     * Stops renewing and releases the worker id, after the web server has stopped taking requests (see
     * {@link #getPhase()}).
     */
    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    LOGGER.warn("Snowflake worker lease thread did not stop within 10s");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
        Lease current = lease;
        lease = null;
        if (current != null) {
            try {
                release(current);
                LOGGER.info("Released Snowflake worker id {}", current.workerId());
            } catch (RuntimeException ex) {
                LOGGER.warn("Could not release Snowflake worker id {}; it frees up after the lease TTL",
                        current.workerId(), ex);
            }
        }
    }


    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }


    /**
     * Before the web server starts (DEFAULT_PHASE - 2048) and the Kafka listeners, and stopped after them, so no
     * request or consumer runs without a worker id.
     */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 4096;
    }


    /**
     * One run of the lease thread: renews the lease, or claims a new one after it was lost. Never throws, so the
     * schedule keeps running.
     */
    void maintain() {
        try {
            Lease current = lease;
            if (current == null) {
                lease = acquire();
                LOGGER.info("Leased Snowflake worker id {} as {}", lease.workerId(), owner);
            } else if (!renew(current)) {
                current.generator().close();
                lease = null;
                onLeaseLost.run();
                LOGGER.warn("Snowflake worker id {} was taken over after its lease expired; claiming another",
                        current.workerId());
                lease = acquire();
                LOGGER.info("Leased Snowflake worker id {} as {}", lease.workerId(), owner);
            }
            if (consecutiveFailures > 0) {
                LOGGER.info("Snowflake worker lease renewal recovered after {} failure(s)", consecutiveFailures);
                consecutiveFailures = 0;
            }
        } catch (Throwable ex) {
            consecutiveFailures++;
            if (consecutiveFailures == 1) {
                LOGGER.warn("Snowflake worker lease renewal failed; retrying every {} ms", renewIntervalMillis, ex);
            } else {
                LOGGER.warn("Snowflake worker lease renewal failed {} times in a row: {}", consecutiveFailures,
                        ex.toString());
            }
        }
    }


    private Lease acquire() {
        long wallNow = wallClock.getAsLong();
        Claim claim = transactionTemplate.execute(status -> {
            OffsetDateTime databaseNow = databaseNow();
            List<Claim> free = jdbcTemplate.query("""
                            SELECT worker_id, issued_until_ms
                            FROM snowflake_worker_leases
                            WHERE lease_until IS NULL OR lease_until < ?
                            ORDER BY worker_id
                            LIMIT 1
                            FOR UPDATE SKIP LOCKED
                            """,
                    (row, rowNumber) -> new Claim(row.getInt(1), row.getLong(2), 0L),
                    databaseNow
            );
            if (free.isEmpty()) {
                return null;
            }
            Claim freeRow = free.get(0);
            long ceiling = Math.max(freeRow.floor(), wallNow) + ttlMillis;
            jdbcTemplate.update("""
                            UPDATE snowflake_worker_leases
                            SET owner = ?, lease_until = ?, issued_until_ms = ?, acquired_at = ?, renewed_at = ?
                            WHERE worker_id = ?
                            """,
                    owner,
                    databaseNow.plus(ttl),
                    ceiling,
                    databaseNow,
                    databaseNow,
                    freeRow.workerId()
            );
            return new Claim(freeRow.workerId(), freeRow.floor(), ceiling);
        });
        if (claim == null) {
            throw new IdGeneratorUnavailableException("Every Snowflake worker id (0-"
                    + SnowflakeIdGenerator.MAX_ASSIGNABLE_WORKER_ID + ") is leased");
        }
        // The previous holder may have used timestamps up to the floor. A clock that is behind it by less than one
        // TTL is waited out here; a larger gap means a badly skewed clock, and the worker id is handed back.
        long behind = claim.floor() - wallClock.getAsLong();
        if (behind >= 0) {
            if (behind > ttlMillis) {
                releaseUnused(claim);
                throw new IdGeneratorUnavailableException("The clock is " + behind
                        + " ms behind the last id of Snowflake worker " + claim.workerId());
            }
            sleep(behind + 1);
        }
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(
                claim.workerId(),
                claim.floor(),
                claim.ceiling(),
                maxBackwardMillis,
                wallClock,
                sleeper,
                () -> ThreadLocalRandom.current().nextInt(SnowflakeIdGenerator.SEQUENCE_START_BOUND),
                listener
        );
        return new Lease(claim.workerId(), generator);
    }


    /**
     * Extends the lease and raises the generator's ceiling once the database holds the new bound.
     *
     * @return false when another owner holds the row now
     */
    private boolean renew(Lease current) {
        SnowflakeIdGenerator generator = current.generator();
        long wallNow = wallClock.getAsLong();
        long ceiling = Math.max(generator.ceilingMillis(), Math.max(generator.lastTimestamp(), wallNow) + ttlMillis);
        Integer updated = transactionTemplate.execute(status -> {
            OffsetDateTime databaseNow = databaseNow();
            // Only the owner matters: a lease that expired but was not taken over can still be renewed, since nobody
            // else issued ids with this worker id in the meantime.
            return jdbcTemplate.update("""
                            UPDATE snowflake_worker_leases
                            SET lease_until = ?, issued_until_ms = ?, renewed_at = ?
                            WHERE worker_id = ?
                              AND owner = ?
                            """,
                    databaseNow.plus(ttl),
                    ceiling,
                    databaseNow,
                    current.workerId(),
                    owner
            );
        });
        if (updated == null || updated != 1) {
            return false;
        }
        generator.raiseCeiling(ceiling);
        return true;
    }


    private void release(Lease current) {
        // After close() no id is issued any more, so the bound can drop to the last timestamp actually used and the
        // next holder does not wait for the rest of this lease.
        long lastIssued = current.generator().close();
        jdbcTemplate.update("""
                        UPDATE snowflake_worker_leases
                        SET owner = NULL, lease_until = NULL, issued_until_ms = ?
                        WHERE worker_id = ?
                          AND owner = ?
                        """,
                lastIssued,
                current.workerId(),
                owner
        );
    }


    private void releaseUnused(Claim claim) {
        jdbcTemplate.update("""
                        UPDATE snowflake_worker_leases
                        SET owner = NULL, lease_until = NULL, issued_until_ms = ?
                        WHERE worker_id = ?
                          AND owner = ?
                        """,
                claim.floor(),
                claim.workerId(),
                owner
        );
    }


    private OffsetDateTime databaseNow() {
        return jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP", OffsetDateTime.class);
    }


    private void sleep(long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IdGeneratorUnavailableException("Interrupted while waiting for the clock", ex);
        }
    }


    private record Lease(int workerId, SnowflakeIdGenerator generator) {
    }


    /**
     * @param floor   the bound the previous holder recorded: ids under this claim start after it
     * @param ceiling the bound this holder recorded
     */
    private record Claim(int workerId, long floor, long ceiling) {
    }
}
