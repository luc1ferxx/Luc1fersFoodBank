package com.laioffer.onlineorder.datasource;


import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import javax.sql.DataSource;


/**
 * One read replica: its pool and what the lag monitor last found out about it. Reads are only routed to it while it is
 * {@link #isRoutable() routable}, that is while the last check found it replaying WAL no more than
 * {@code app.datasource.replica.max-lag} behind the primary. It starts unroutable: until the first check nothing is
 * known about it.
 */
public final class ReplicaTarget {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReplicaTarget.class);

    /**
     * Why a replica gets reads or not, as reported in the logs.
     */
    public enum State {
        /** Not checked yet. */
        UNKNOWN,
        /** Replaying WAL within the lag threshold. */
        ROUTABLE,
        /** Reachable, but too far behind, or its lag cannot be bounded yet. */
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
    private volatile State state = State.UNKNOWN;
    private volatile double lagSeconds = Double.NaN;


    public ReplicaTarget(String name, String url, DataSource dataSource) {
        this.name = name;
        this.url = url;
        this.dataSource = dataSource;
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


    public boolean isRoutable() {
        return state == State.ROUTABLE;
    }


    public State state() {
        return state;
    }


    /**
     * The last measured lag in seconds, a lower bound while {@link State#LAGGING}; NaN when it is unknown (unreachable,
     * not a standby, primary position unknown).
     */
    public double lagSeconds() {
        return lagSeconds;
    }


    /**
     * @param lagSeconds the measured lag, or a lower bound of it when {@code bounded} is false
     * @param bounded    false when the replica is behind every primary position the monitor remembers, so its lag has
     *                   no known upper bound
     */
    void lagMeasured(double lagSeconds, boolean bounded, double maxLagSeconds) {
        this.lagSeconds = lagSeconds;
        if (bounded && lagSeconds <= maxLagSeconds) {
            transition(State.ROUTABLE, "lag " + format(lagSeconds));
        } else {
            transition(State.LAGGING, bounded
                    ? "lag " + format(lagSeconds) + " exceeds " + format(maxLagSeconds)
                    : "at least " + format(lagSeconds) + " behind, behind every remembered primary position");
        }
    }


    void unreachable(String reason) {
        lagSeconds = Double.NaN;
        transition(State.UNREACHABLE, reason);
    }


    void notStandby() {
        lagSeconds = Double.NaN;
        transition(State.NOT_STANDBY, "pg_is_in_recovery() is false; it is not a standby (promoted?)");
    }


    void primaryPositionUnknown(String reason) {
        lagSeconds = Double.NaN;
        transition(State.PRIMARY_UNKNOWN, reason);
    }


    void noWalReplayedYet() {
        lagSeconds = Double.NaN;
        transition(State.LAGGING, "no WAL replayed yet");
    }


    /**
     * Moves to {@code next}. The pool's connections are evicted when the replica becomes unreachable, and again just
     * before it takes reads after an outage: a replica that restarted has cut every connection the pool still holds,
     * and a read must not be handed one of them.
     */
    private void transition(State next, String reason) {
        State previous = state;
        if (previous == next) {
            return;
        }
        if (next == State.ROUTABLE && previous != State.UNKNOWN && previous != State.LAGGING) {
            evictPooledConnections();
        }
        state = next;
        if (next == State.UNREACHABLE) {
            evictPooledConnections();
        }
        if (next == State.ROUTABLE && previous == State.UNKNOWN) {
            LOGGER.info("Read replica {} is routable ({})", name, reason);
        } else if (next == State.ROUTABLE) {
            LOGGER.info("Read replica {} is routable again ({}; was {})", name, reason, previous);
        } else if (previous == State.ROUTABLE || previous == State.UNKNOWN) {
            LOGGER.warn("Read replica {} gets no reads, they go to the primary: {} ({})", name, next, reason);
        } else {
            LOGGER.info("Read replica {} still gets no reads: {} ({})", name, next, reason);
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


    private static String format(double seconds) {
        return String.format(java.util.Locale.ROOT, "%.3fs", seconds);
    }


    @Override
    public String toString() {
        return name + "[" + state + "]";
    }
}
