package com.laioffer.onlineorder.ids;


import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;


import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Worker id leases (V10_2) with several "instances" (managers) on one PostgreSQL database and a fake clock per
 * instance, so clock skew and expired leases can be staged.
 */
@Testcontainers(disabledWithoutDocker = true)
class WorkerIdLeasePostgresTests {

    // 2026-10-16T00:00:00Z
    private static final long T = 1_792_022_400_000L;
    private static final Duration TTL = Duration.ofSeconds(60);

    @Container
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("onlineorder")
            .withUsername("postgres")
            .withPassword("secret");

    private static JdbcTemplate jdbcTemplate;
    private static DataSourceTransactionManager transactionManager;

    private final List<WorkerIdLeaseManager> started = new ArrayList<>();


    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
    }


    @BeforeEach
    void freeEveryWorkerId() {
        jdbcTemplate.update("""
                UPDATE snowflake_worker_leases
                SET owner = NULL, lease_until = NULL, issued_until_ms = 0, acquired_at = NULL, renewed_at = NULL
                """);
    }


    @AfterEach
    void stopManagers() {
        started.forEach(WorkerIdLeaseManager::stop);
    }


    @Test
    void start_shouldGiveEveryInstanceItsOwnWorkerIdAndUniqueIds() {
        AtomicLong clock = new AtomicLong(T);
        WorkerIdLeaseManager first = start("pod-a", clock);
        WorkerIdLeaseManager second = start("pod-b", clock);

        Assertions.assertEquals(0, first.workerId());
        Assertions.assertEquals(1, second.workerId());
        Assertions.assertEquals(List.of("0|pod-a|" + (T + TTL.toMillis()), "1|pod-b|" + (T + TTL.toMillis())),
                jdbcTemplate.queryForList("""
                        SELECT worker_id || '|' || owner || '|' || issued_until_ms
                        FROM snowflake_worker_leases WHERE owner IS NOT NULL ORDER BY worker_id
                        """, String.class));
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            ids.add(first.nextId());
            ids.add(second.nextId());
        }
        Assertions.assertEquals(1000, ids.size());
        Assertions.assertTrue(first.isRunning());
        Assertions.assertTrue(first.getPhase() < SmartLifecycle.DEFAULT_PHASE - 2048, "starts before the web server");
    }


    @Test
    void stop_shouldReleaseTheWorkerIdAndTheNextHolderStartAfterItsLastId() {
        AtomicLong firstClock = new AtomicLong(T);
        WorkerIdLeaseManager first = start("pod-a", firstClock);
        long lastOfFirst = 0;
        for (int i = 0; i < 5; i++) {
            firstClock.incrementAndGet();
            lastOfFirst = first.nextId();
        }
        first.stop();
        Assertions.assertFalse(first.isRunning());
        Assertions.assertEquals(-1, first.workerId());
        Assertions.assertThrows(IdGeneratorUnavailableException.class, first::nextId);
        Assertions.assertEquals(List.of("null|" + (T + 5)), jdbcTemplate.queryForList(
                "SELECT COALESCE(owner, 'null') || '|' || issued_until_ms FROM snowflake_worker_leases WHERE worker_id = 0",
                String.class), "released, and the bound drops to the last id actually issued");

        // The next pod's clock is 3 ms behind the first one's: it waits instead of reusing a timestamp.
        AtomicLong secondClock = new AtomicLong(T + 2);
        List<Long> sleeps = new ArrayList<>();
        WorkerIdLeaseManager second = start("pod-b", secondClock, sleeps);
        long firstOfSecond = second.nextId();

        Assertions.assertEquals(0, second.workerId());
        Assertions.assertEquals(List.of(4L), sleeps);
        Assertions.assertTrue(SnowflakeIdGenerator.timestampMillisOf(firstOfSecond)
                > SnowflakeIdGenerator.timestampMillisOf(lastOfFirst));
    }


    @Test
    void nextId_shouldStopAtTheCeilingUntilARenewalRaisesIt() {
        AtomicLong clock = new AtomicLong(T);
        WorkerIdLeaseManager manager = start("pod-a", clock);
        manager.nextId();

        // No renewal for a whole TTL (the database was unreachable): the lease may be gone, so no more ids.
        clock.set(T + TTL.toMillis() + 1);
        Assertions.assertThrows(IdGeneratorUnavailableException.class, manager::nextId);

        manager.maintain();
        Assertions.assertEquals(List.of(String.valueOf(T + 2 * TTL.toMillis() + 1)), jdbcTemplate.queryForList(
                "SELECT issued_until_ms::text FROM snowflake_worker_leases WHERE worker_id = 0", String.class));
        Assertions.assertEquals(T + TTL.toMillis() + 1, SnowflakeIdGenerator.timestampMillisOf(manager.nextId()));
    }


    @Test
    void takeover_shouldKeepTheTwoHoldersInDisjointTimestampRanges() {
        AtomicLong oldClock = new AtomicLong(T);
        AtomicInteger lost = new AtomicInteger();
        WorkerIdLeaseManager paused = start("pod-paused", oldClock, new ArrayList<>(), lost::incrementAndGet);
        long oldId = paused.nextId();
        // The pod stalls past its lease; the database lets another pod take the worker id.
        jdbcTemplate.update("""
                UPDATE snowflake_worker_leases SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                WHERE worker_id = 0
                """);

        // The new pod's clock is a second behind: it waits until it is past the old holder's ceiling.
        AtomicLong newClock = new AtomicLong(T + 1_000);
        List<Long> sleeps = new ArrayList<>();
        WorkerIdLeaseManager successor = start("pod-new", newClock, sleeps);
        Assertions.assertEquals(0, successor.workerId());
        Assertions.assertEquals(List.of(TTL.toMillis() - 1_000 + 1), sleeps);
        long newId = successor.nextId();
        Assertions.assertTrue(SnowflakeIdGenerator.timestampMillisOf(newId) > T + TTL.toMillis());

        // The stalled pod can still issue up to its ceiling, which lies before every id of the new holder.
        oldClock.set(T + 10);
        long lateId = paused.nextId();
        Assertions.assertTrue(lateId < newId);

        // Its next renewal finds the row taken over: it gives up worker id 0 and leases another one.
        paused.maintain();
        Assertions.assertEquals(1, lost.get());
        Assertions.assertEquals(1, paused.workerId());
        Assertions.assertEquals(1, SnowflakeIdGenerator.workerIdOf(paused.nextId()));
        Assertions.assertNotEquals(oldId, newId);
    }


    @Test
    void start_whenEveryWorkerIdIsLeased_shouldFail() {
        jdbcTemplate.update("""
                UPDATE snowflake_worker_leases SET owner = 'elsewhere', lease_until = CURRENT_TIMESTAMP + INTERVAL '1 hour'
                """);
        WorkerIdLeaseManager manager = manager("pod-a", new AtomicLong(T), new ArrayList<>(), null);

        Assertions.assertThrows(IdGeneratorUnavailableException.class, manager::start);
        Assertions.assertFalse(manager.isRunning());
        Assertions.assertEquals(-1, manager.workerId());
        Assertions.assertThrows(IdGeneratorUnavailableException.class, manager::nextId);
    }


    @Test
    void start_whenTheClockIsFarBehindThePreviousHolder_shouldHandTheWorkerIdBack() {
        // Worker id 0's last holder used timestamps ten TTLs ahead of this pod's clock.
        jdbcTemplate.update("UPDATE snowflake_worker_leases SET issued_until_ms = ? WHERE worker_id = 0",
                T + 10 * TTL.toMillis());
        jdbcTemplate.update("""
                UPDATE snowflake_worker_leases SET owner = 'elsewhere', lease_until = CURRENT_TIMESTAMP + INTERVAL '1 hour'
                WHERE worker_id > 0
                """);
        WorkerIdLeaseManager manager = manager("pod-skewed", new AtomicLong(T), new ArrayList<>(), null);

        Assertions.assertThrows(IdGeneratorUnavailableException.class, manager::start);
        Assertions.assertEquals(List.of("null|" + (T + 10 * TTL.toMillis())), jdbcTemplate.queryForList(
                "SELECT COALESCE(owner, 'null') || '|' || issued_until_ms FROM snowflake_worker_leases WHERE worker_id = 0",
                String.class));
    }


    @Test
    void maintain_withoutALease_shouldLeaseOneAndNeverThrow() {
        WorkerIdLeaseManager manager = manager("pod-late", new AtomicLong(T), new ArrayList<>(), null);
        started.add(manager);
        jdbcTemplate.update("""
                UPDATE snowflake_worker_leases SET owner = 'elsewhere', lease_until = CURRENT_TIMESTAMP + INTERVAL '1 hour'
                """);

        Assertions.assertDoesNotThrow(manager::maintain);
        Assertions.assertEquals(-1, manager.workerId());

        jdbcTemplate.update("UPDATE snowflake_worker_leases SET owner = NULL, lease_until = NULL WHERE worker_id = 7");
        manager.maintain();
        Assertions.assertEquals(7, manager.workerId());
    }


    private WorkerIdLeaseManager start(String owner, AtomicLong clock) {
        return start(owner, clock, new ArrayList<>());
    }


    private WorkerIdLeaseManager start(String owner, AtomicLong clock, List<Long> sleeps) {
        return start(owner, clock, sleeps, null);
    }


    private WorkerIdLeaseManager start(String owner, AtomicLong clock, List<Long> sleeps, Runnable onLeaseLost) {
        WorkerIdLeaseManager manager = manager(owner, clock, sleeps, onLeaseLost);
        manager.start();
        started.add(manager);
        return manager;
    }


    private WorkerIdLeaseManager manager(String owner, AtomicLong clock, List<Long> sleeps, Runnable onLeaseLost) {
        return new WorkerIdLeaseManager(
                jdbcTemplate,
                transactionManager,
                TTL,
                // The renewal thread must not run during the test: the tests call maintain() themselves.
                Duration.ofHours(1),
                Duration.ofMillis(50),
                owner,
                null,
                onLeaseLost,
                clock::get,
                millis -> {
                    sleeps.add(millis);
                    clock.addAndGet(millis);
                }
        );
    }
}
