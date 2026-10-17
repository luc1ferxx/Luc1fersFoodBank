package com.laioffer.onlineorder.datasource;


import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import javax.sql.DataSource;
import java.time.Duration;
import java.util.Locale;
import java.util.function.LongSupplier;


/**
 * One read replica: its pool and what the lag monitor last found out about it.
 *
 * <p>Reads are only routed to it while it is {@link #isRoutable() routable}: the last check found it a reachable
 * standby, and its data is still less than {@code app.datasource.replica.max-lag} old. The second part is a deadline
 * evaluated at routing time, not a verdict kept until the next check: the check records the time before which the
 * replica has every transaction of the primary ({@link WalPositionHistory.Freshness}), and the replica drops out on its
 * own once that time is {@code max-lag} ago. A replica whose replay stalls, or whose checks are slow or hang, therefore
 * never serves data older than {@code max-lag}, however long the monitor takes. It starts unroutable: until the first
 * check nothing is known about it.
 */
public final class ReplicaTarget {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReplicaTarget.class);

    /**
     * Why a replica gets reads or not, as reported in the logs.
     */
    public enum State {
        /** Not checked yet. */
        UNKNOWN,
        /** A standby whose data is less than max-lag old. */
        ROUTABLE,
        /** Reachable, but its data is max-lag old or older, or how old cannot be bounded. */
        LAGGING,
        /** The monitor could not query it, or a read could not get a connection from it. */
        UNREACHABLE,
        /** Not in recovery: promoted, or not a standby of this primary at all. */
        NOT_STANDBY,
        /** The primary's WAL position could not be read, so no lag can be measured. */
        PRIMARY_UNKNOWN
    }

    private final String name;
    private final String url;
    private final DataSource dataSource;
    private final long maxLagNanos;
    private final LongSupplier nanoClock;
    private volatile Verdict verdict = Verdict.UNKNOWN;


    public ReplicaTarget(String name, String url, DataSource dataSource, Duration maxLag) {
        this(name, url, dataSource, maxLag, System::nanoTime);
    }


    ReplicaTarget(String name, String url, DataSource dataSource, Duration maxLag, LongSupplier nanoClock) {
        this.name = name;
        this.url = url;
        this.dataSource = dataSource;
        this.maxLagNanos = maxLag.toNanos();
        this.nanoClock = nanoClock;
    }


    public String name() {
        return name;
    }


    /**
     * The JDBC URL, for the lag monitor's own connection.
     */
    String url() {
        return url;
    }


    public DataSource dataSource() {
        return dataSource;
    }


    /**
     * True while the last check found the replica healthy and its data is still less than max-lag old now.
     */
    public boolean isRoutable() {
        return isRoutable(verdict);
    }


    private boolean isRoutable(Verdict current) {
        return current.state() == State.ROUTABLE && nanoClock.getAsLong() - current.asOfNanos() < maxLagNanos;
    }


    /**
     * The state now: {@link State#LAGGING} once a replica the last check found routable has gone past its deadline.
     */
    public State state() {
        Verdict current = verdict;
        if (current.state() == State.ROUTABLE && !isRoutable(current)) {
            return State.LAGGING;
        }
        return current.state();
    }


    /**
     * How old the replica's data may be now, in seconds: the time since the primary was last seen at a WAL position the
     * replica has replayed (an upper bound of its lag, never below the true lag; a caught-up replica shows the time
     * since its last check). A lower bound when the replica is behind every remembered primary position; NaN when it is
     * unknown (unreachable, not a standby, primary position unknown, nothing replayed yet).
     */
    public double lagSeconds() {
        Verdict current = verdict;
        if (!current.measured()) {
            return Double.NaN;
        }
        return Math.max(0L, nanoClock.getAsLong() - current.asOfNanos()) / 1_000_000_000.0;
    }


    /**
     * A check measured the replica: a reachable standby that has replayed WAL.
     */
    synchronized void measured(WalPositionHistory.Freshness freshness) {
        long age = Math.max(0L, nanoClock.getAsLong() - freshness.asOfNanos());
        State next;
        String reason;
        if (!freshness.bounded()) {
            next = State.LAGGING;
            reason = "at least " + format(age) + " behind, behind every remembered primary position";
        } else if (age < maxLagNanos) {
            next = State.ROUTABLE;
            reason = "data at most " + format(age) + " old";
        } else {
            next = State.LAGGING;
            reason = "data up to " + format(age) + " old, max-lag " + format(maxLagNanos);
        }
        transition(new Verdict(next, true, freshness.asOfNanos()), reason);
    }


    synchronized void unreachable(String reason) {
        transition(new Verdict(State.UNREACHABLE, false, 0L), reason);
    }


    synchronized void notStandby() {
        transition(new Verdict(State.NOT_STANDBY, false, 0L), "pg_is_in_recovery() is false; it is not a standby (promoted?)");
    }


    synchronized void primaryPositionUnknown(String reason) {
        transition(new Verdict(State.PRIMARY_UNKNOWN, false, 0L), reason);
    }


    synchronized void noWalReplayedYet() {
        transition(new Verdict(State.LAGGING, false, 0L), "no WAL replayed yet");
    }


    /**
     * Moves to {@code next}, logging a change of state. The pool's connections are evicted when the replica becomes
     * unreachable, and again just before it takes reads after an outage: a replica that restarted has cut every
     * connection the pool still holds, and a read must not be handed one of them.
     */
    private void transition(Verdict next, String reason) {
        // The state last decided by a check (or a failed connection), not the deadline-adjusted state(): an expired
        // deadline that the next check renews is no change worth a log line.
        State previous = verdict.state();
        State nextState = next.state();
        if (previous == nextState) {
            verdict = next;
            return;
        }
        if (nextState == State.ROUTABLE && previous != State.UNKNOWN && previous != State.LAGGING) {
            evictPooledConnections();
        }
        verdict = next;
        if (nextState == State.UNREACHABLE) {
            evictPooledConnections();
        }
        if (nextState == State.ROUTABLE && previous == State.UNKNOWN) {
            LOGGER.info("Read replica {} is routable ({})", name, reason);
        } else if (nextState == State.ROUTABLE) {
            LOGGER.info("Read replica {} is routable again ({}; was {})", name, reason, previous);
        } else if (previous == State.ROUTABLE || previous == State.UNKNOWN) {
            LOGGER.warn("Read replica {} gets no reads, they go to the primary: {} ({})", name, nextState, reason);
        } else {
            LOGGER.info("Read replica {} still gets no reads: {} ({})", name, nextState, reason);
        }
    }


    /**
     * Closes the pool's idle connections now and its busy ones when they are returned; the pool opens new ones.
     */
    private void evictPooledConnections() {
        if (dataSource instanceof HikariDataSource pool) {
            HikariPoolMXBean poolBean = pool.getHikariPoolMXBean();
            if (poolBean != null) {
                poolBean.softEvictConnections();
            }
        }
    }


    private static String format(long nanos) {
        return String.format(Locale.ROOT, "%.3fs", nanos / 1_000_000_000.0);
    }


    @Override
    public String toString() {
        return name + "[" + state() + "]";
    }


    /**
     * @param measured  whether {@code asOfNanos} is known
     * @param asOfNanos see {@link WalPositionHistory.Freshness#asOfNanos()}
     */
    private record Verdict(State state, boolean measured, long asOfNanos) {

        static final Verdict UNKNOWN = new Verdict(State.UNKNOWN, false, 0L);
    }
}
