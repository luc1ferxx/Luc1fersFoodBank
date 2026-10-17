package com.laioffer.onlineorder.datasource;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;


import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


/**
 * Measures every replica's lag every {@code app.datasource.replica.lag-check-interval} and marks it routable or not
 * (see {@link ReplicaTarget}). Runs on a thread of its own ("db-replica-lag-monitor"), like the deadline poller, and
 * is not a TaskScheduler bean.
 *
 * <p>Each check reads the primary's WAL position ({@code pg_current_wal_lsn()}), then every replica's
 * {@code pg_is_in_recovery()} and {@code pg_last_wal_replay_lsn()}; {@link WalPositionHistory} turns the replay position
 * into seconds. A replica is routable while it is a standby, reachable and at most {@code max-lag} behind.
 *
 * <p>The monitor has its own connections (one to the primary, one per replica), outside the pools: a saturated primary
 * pool must not stop the measurement and push every read onto that same primary, and a hung replica must not hold a
 * pooled connection. Their connect and socket timeouts are {@code lag-check-timeout}, so a hung server fails the check
 * within that time. If the primary's position cannot be read, no lag can be measured and every replica is taken out of
 * rotation until it can (reads then go to the primary, which is down or unreachable from here as well).
 */
public class ReplicaLagMonitor implements SmartLifecycle {

    static final String THREAD_NAME = "db-replica-lag-monitor";
    static final String APPLICATION_NAME = "onlineorder-replica-monitor";

    private static final Logger LOGGER = LoggerFactory.getLogger(ReplicaLagMonitor.class);
    private static final String PRIMARY_POSITION_SQL = "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')::bigint";
    private static final String REPLICA_POSITION_SQL =
            "SELECT pg_is_in_recovery(), pg_wal_lsn_diff(pg_last_wal_replay_lsn(), '0/0')::bigint";
    /** About an hour of a busy primary at a 1s interval; an idle primary adds no samples. */
    private static final int HISTORY_CAPACITY = 3600;

    private final ConnectionSettings primary;
    private final List<MonitoredReplica> replicas;
    private final Duration interval;
    private final double maxLagSeconds;
    private final WalPositionHistory history = new WalPositionHistory(HISTORY_CAPACITY);
    // Only touched by the monitor thread, and by stop() after that thread has ended.
    private final Map<String, Connection> connections = new HashMap<>();
    private ScheduledExecutorService executor;


    ReplicaLagMonitor(
            ConnectionSettings primary,
            List<MonitoredReplica> replicas,
            Duration interval,
            Duration maxLag
    ) {
        this.primary = primary;
        this.replicas = List.copyOf(replicas);
        this.interval = interval;
        this.maxLagSeconds = maxLag.toNanos() / 1_000_000_000.0;
    }


    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = Math.max(1L, interval.toMillis());
        executor.scheduleWithFixedDelay(this::checkNow, 0L, intervalMillis, TimeUnit.MILLISECONDS);
    }


    /**
     * One round of checks. Never throws: an exception would cancel all later runs of a ScheduledExecutorService task.
     */
    void checkNow() {
        try {
            check();
        } catch (RuntimeException ex) {
            LOGGER.warn("Replica lag check failed", ex);
        }
    }


    private void check() {
        Long primaryLsn = queryPrimaryPosition();
        if (primaryLsn == null) {
            return;
        }
        history.record(System.nanoTime(), primaryLsn);
        for (MonitoredReplica replica : replicas) {
            checkReplica(replica.target(), replica.settings());
        }
    }


    private Long queryPrimaryPosition() {
        try (Statement statement = connection(primary).createStatement();
             ResultSet result = statement.executeQuery(PRIMARY_POSITION_SQL)) {
            requireRow(result);
            return result.getLong(1);
        } catch (SQLException ex) {
            closeConnection(primary.key());
            String reason = "primary WAL position unavailable: " + ex.getMessage();
            for (MonitoredReplica replica : replicas) {
                replica.target().primaryPositionUnknown(reason);
            }
            return null;
        }
    }


    private void checkReplica(ReplicaTarget replica, ConnectionSettings settings) {
        boolean inRecovery;
        long replayLsn;
        boolean replayed;
        try (Statement statement = connection(settings).createStatement();
             ResultSet result = statement.executeQuery(REPLICA_POSITION_SQL)) {
            requireRow(result);
            inRecovery = result.getBoolean(1);
            replayLsn = result.getLong(2);
            replayed = !result.wasNull();
        } catch (SQLException ex) {
            closeConnection(settings.key());
            replica.unreachable("lag check failed: " + ex.getMessage());
            return;
        }
        if (!inRecovery) {
            replica.notStandby();
            return;
        }
        if (!replayed) {
            replica.noWalReplayedYet();
            return;
        }
        WalPositionHistory.Lag lag = history.lag(replayLsn, System.nanoTime());
        replica.lagMeasured(lag.seconds(), lag.bounded(), maxLagSeconds);
    }


    private Connection connection(ConnectionSettings settings) throws SQLException {
        Connection connection = connections.get(settings.key());
        if (connection == null) {
            connection = DriverManager.getConnection(settings.url(), settings.properties());
            connections.put(settings.key(), connection);
        }
        return connection;
    }


    private static void requireRow(ResultSet result) throws SQLException {
        if (!result.next()) {
            throw new SQLException("The position query returned no row");
        }
    }


    private void closeConnection(String key) {
        Connection connection = connections.remove(key);
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ex) {
            LOGGER.debug("Could not close the lag monitor's connection to {}", key, ex);
        }
    }


    /**
     * Stops the checks and closes the monitor's connections once the monitor thread has ended.
     */
    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    LOGGER.warn("Replica lag monitor did not stop within 10s");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                executor = null;
            }
        }
        for (String key : List.copyOf(connections.keySet())) {
            closeConnection(key);
        }
    }


    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }


    /**
     * A replica and the settings of the monitor's connection to it.
     */
    record MonitoredReplica(ReplicaTarget target, ConnectionSettings settings) {
    }


    /**
     * A monitor connection: the JDBC URL and driver properties (login, timeouts, application_name).
     *
     * @param key a name for logs and the connection cache, such as "primary" or "replica-1"
     */
    record ConnectionSettings(String key, String url, Properties properties) {

        static ConnectionSettings of(String key, String url, String username, String password, Duration timeout) {
            String seconds = Long.toString(Math.max(1L, (timeout.toMillis() + 999L) / 1000L));
            Properties properties = new Properties();
            if (username != null) {
                properties.setProperty("user", username);
            }
            if (password != null) {
                properties.setProperty("password", password);
            }
            properties.setProperty("connectTimeout", seconds);
            properties.setProperty("loginTimeout", seconds);
            properties.setProperty("socketTimeout", seconds);
            properties.setProperty("ApplicationName", APPLICATION_NAME);
            return new ConnectionSettings(key, url, properties);
        }


        @Override
        public String toString() {
            return "ConnectionSettings[" + key + ", " + url + "]";
        }
    }
}
