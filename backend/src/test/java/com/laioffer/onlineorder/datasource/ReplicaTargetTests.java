package com.laioffer.onlineorder.datasource;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;


import javax.sql.DataSource;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;


/**
 * A replica's routability as a deadline, on a virtual clock. The simulations drive the real {@link WalPositionHistory}
 * and {@link ReplicaTarget} the way {@link ReplicaLagMonitor} does for one replica (sample the primary, taking the time
 * before the query, then read the replica's replay position) and look at {@link ReplicaTarget#isRoutable()} every
 * millisecond in between, as reads would.
 */
class ReplicaTargetTests {

    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000 * MS;
    private static final Duration MAX_LAG = Duration.ofSeconds(2);

    private final AtomicLong now = new AtomicLong(1_000 * SECOND);
    private final ReplicaTarget replica =
            new ReplicaTarget("replica-1", "jdbc:postgresql://r1/db", Mockito.mock(DataSource.class), MAX_LAG, now::get);


    @Test
    void aReplicaFoundFreshDropsOutOnItsOwnOnceItsDataIsMaxLagOld() {
        Assertions.assertEquals(ReplicaTarget.State.UNKNOWN, replica.state());
        Assertions.assertFalse(replica.isRoutable());
        Assertions.assertTrue(Double.isNaN(replica.lagSeconds()));

        long asOf = now.get();
        now.addAndGet(10 * MS);
        replica.measured(new WalPositionHistory.Freshness(asOf, true));
        Assertions.assertTrue(replica.isRoutable());
        Assertions.assertEquals(ReplicaTarget.State.ROUTABLE, replica.state());
        Assertions.assertEquals(0.01, replica.lagSeconds(), 1e-9);

        // No further check: the deadline alone takes it out.
        now.set(asOf + MAX_LAG.toNanos() - 1);
        Assertions.assertTrue(replica.isRoutable());
        now.set(asOf + MAX_LAG.toNanos());
        Assertions.assertFalse(replica.isRoutable());
        Assertions.assertEquals(ReplicaTarget.State.LAGGING, replica.state());
        Assertions.assertEquals(2.0, replica.lagSeconds(), 1e-9, "the lag gauge grows with the data's age");
        Assertions.assertEquals("replica-1[LAGGING]", replica.toString());

        // The next check renews it.
        replica.measured(new WalPositionHistory.Freshness(now.get() - MS, true));
        Assertions.assertTrue(replica.isRoutable());
    }


    @Test
    void aCheckThatFindsDataMaxLagOldOrUnboundedLeavesItOut() {
        replica.measured(new WalPositionHistory.Freshness(now.get() - MAX_LAG.toNanos(), true));
        Assertions.assertEquals(ReplicaTarget.State.LAGGING, replica.state());

        replica.measured(new WalPositionHistory.Freshness(now.get() - MS, false));
        Assertions.assertEquals(ReplicaTarget.State.LAGGING, replica.state(), "an unbounded age is never routable");
        Assertions.assertEquals(0.001, replica.lagSeconds(), 1e-9, "a lower bound");
    }


    @Test
    void statesWithoutAMeasurementHaveNoLag() {
        replica.measured(new WalPositionHistory.Freshness(now.get(), true));
        replica.notStandby();
        Assertions.assertEquals(ReplicaTarget.State.NOT_STANDBY, replica.state());
        Assertions.assertTrue(Double.isNaN(replica.lagSeconds()));
        replica.primaryPositionUnknown("primary down");
        Assertions.assertEquals(ReplicaTarget.State.PRIMARY_UNKNOWN, replica.state());
        replica.noWalReplayedYet();
        Assertions.assertEquals(ReplicaTarget.State.LAGGING, replica.state());
        Assertions.assertTrue(Double.isNaN(replica.lagSeconds()));
        replica.unreachable("down");
        Assertions.assertEquals(ReplicaTarget.State.UNREACHABLE, replica.state());
        Assertions.assertFalse(replica.isRoutable());
        Assertions.assertEquals("jdbc:postgresql://r1/db", replica.url());
    }


    /**
     * The reviewer's case: the primary writes continuously, the replica's replay stalls just before a write at T. Before
     * the deadline, the stalled replica kept a routable verdict until the next check, and with slow rounds it served
     * reads until T + 6s. Now every read it serves sees everything committed max-lag before, however long the checks
     * take, and none comes after T + max-lag. (With one thread per replica, a slow round is this replica's own slow
     * answer; another replica's checks no longer delay it at all.)
     */
    @ParameterizedTest(name = "check rounds of {0}ms")
    @ValueSource(longs = {5, 200, 500, 900, 2_000, 5_000})
    void aReplicaWhoseReplayStallsGetsNoReadOnceItIsMaxLagBehind_howeverSlowTheChecks(long roundMillis) {
        long start = now.get();
        long stall = start + 20 * SECOND;
        Simulation simulation = new Simulation(start, 10 * MS, stall, Duration.ofMillis(500), roundMillis * MS);

        long lastRoutable = simulation.run(stall + 15 * SECOND);

        if (roundMillis < MAX_LAG.toMillis() / 2) {
            Assertions.assertTrue(lastRoutable > stall - SECOND, "it took reads until shortly before the stall");
        }
        // A check slower than max-lag can never show data younger than max-lag: such a replica gets no reads at all.
        Assertions.assertTrue(lastRoutable < stall + MAX_LAG.toNanos(),
                "routable until " + (lastRoutable - stall) / 1e6 + "ms after the stall");
    }


    @Test
    void aHealthyReplicaUnderWriteLoadStaysRoutableWithTheDefaults() {
        long start = now.get();
        // 10ms behind, 500ms between checks, 5ms per check: never stalls.
        Simulation simulation = new Simulation(start, 10 * MS, Long.MAX_VALUE, Duration.ofMillis(500), 5 * MS);

        simulation.run(start + 60 * SECOND);

        // From its second check on (the first has no earlier primary position to bound a replica that is behind).
        Assertions.assertEquals(0, simulation.unroutableOnceRoutable, "milliseconds without reads");
    }


    /**
     * One replica checked like the monitor does it, on the virtual clock; isRoutable() is sampled every millisecond and
     * every routed read is verified against the replica's actual data.
     *
     * <p>The primary writes one LSN unit per microsecond, so every millisecond has commits. The replica replays what
     * the primary had {@code replicationDelay} earlier, until {@code stall}, and nothing after.
     */
    private final class Simulation {

        private final WalPositionHistory history = new WalPositionHistory(100_000);
        private final long start;
        private final long replicationDelay;
        private final long stall;
        private final long interval;
        private final long round;
        private long unroutableOnceRoutable;


        private Simulation(long start, long replicationDelay, long stall, Duration interval, long round) {
            this.start = start;
            this.replicationDelay = replicationDelay;
            this.stall = stall;
            this.interval = interval.toNanos();
            this.round = round;
        }


        private long primaryLsn(long time) {
            return (time - start) / 1_000L;
        }


        private long replayLsn(long time) {
            return primaryLsn(Math.min(time, stall) - replicationDelay);
        }


        /**
         * @return the last virtual time the replica was routable
         */
        private long run(long end) {
            long lastRoutable = Long.MIN_VALUE;
            boolean wasRoutable = false;
            long nextCheck = start + SECOND;
            long answerAt = -1L;
            for (long t = start + SECOND; t <= end; t += MS) {
                now.set(t);
                if (answerAt < 0 && t >= nextCheck) {
                    // A check starts: the primary is sampled, the time taken before the query.
                    history.record(t, primaryLsn(t));
                    answerAt = t + round;
                }
                if (answerAt >= 0 && t >= answerAt) {
                    // The replica answers at the end of the round; until then the previous verdict applies.
                    replica.measured(history.freshness(replayLsn(t)));
                    answerAt = -1L;
                    nextCheck = t + interval;
                }
                // A read now: routed by isRoutable(), one per millisecond here.
                if (replica.isRoutable()) {
                    lastRoutable = t;
                    wasRoutable = true;
                    // Every read a replica serves must see everything committed max-lag before.
                    Assertions.assertTrue(replayLsn(t) >= primaryLsn(t - MAX_LAG.toNanos()),
                            "a read at " + (t - start) / 1e6 + "ms saw data older than max-lag");
                } else if (wasRoutable) {
                    unroutableOnceRoutable++;
                }
            }
            return lastRoutable;
        }
    }
}
