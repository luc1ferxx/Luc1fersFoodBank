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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


/**
 * Checks every replica every {@code app.datasource.replica.lag-check-interval} and records how old its data may be
 * (see {@link ReplicaTarget}, whose routing deadline bounds a routed read's staleness by {@code max-lag} whatever this
 * monitor does). Each replica is checked on a thread of its own ("db-replica-lag-monitor-replica-N"), like the deadline
 * poller and not a TaskScheduler bean, so a slow or hung replica delays only its own checks.
 *
 * <p>A check reads the primary's WAL position ({@code pg_current_wal_lsn()}), taking the time just before, then the
 * replica's {@code pg_is_in_recovery()} and {@code pg_last_wal_replay_lsn()}; {@link WalPositionHistory} turns the
 * replay position into the time before which the replica has every committed transaction. The primary is sampled
 * before every replica check, over one connection shared by the replicas' threads.
 *
 * <p>The monitor has its own connections (one to the primary, one per replica), outside the pools: a saturated primary
 * pool must not stop the measurement and push every read onto that same primary, and a hung replica must not hold a
 * pooled connection. Their connect and socket timeouts are {@code lag-check-timeout}, so a hung server fails the check
 * within that time. If the primary's position cannot
 * be read, no lag can be measured and the replica is taken out of rotation until it can (reads then go to the primary,
 * which is down or unreachable from here as well).
 */
public class ReplicaLagMonitor implements SmartLifecycle {

    static final String THREAD_NAME = "db-replica-lag-monitor";
    static final String APPLICATION_NAME = "onlineorder-replica-monitor";

    private static final Logger LOGGER = LoggerFactory.getLogger(ReplicaLagMonitor.class);
    private static final String PRIMARY_POSITION_SQL = "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')::bigint";
    private static final String REPLICA_POSITION_SQL =
            "SELECT pg_is_in_recovery(), pg_wal_lsn_diff(pg_last_wal_replay_lsn(), '0/0')::bigint";
    /** About an hour of a busy primary sampled twice a second per replica; an idle primary adds no samples. */
    private static final int HISTORY_CAPACITY = 7200;

    private final ConnectionSettings primary;
    private final List<MonitoredReplica> replicas;
    private final Duration interval;
    private final WalPositionHistory history = new WalPositionHistory(HISTORY_CAPACITY);
    /** Guards the primary's connection and keeps the samples in time order. */
    private final Object primaryLock = new Object();
    // The primary's connection is used under primaryLock; each replica's only by that replica's thread (or checkNow()
    // before start()), and all of them by stop() once those threads have ended.
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private List<ScheduledExecutorService> executors;


    ReplicaLagMonitor(ConnectionSettings primary, List<MonitoredReplica> replicas, Duration interval) {
        this.primary = primary;
        this.replicas = List.copyOf(replicas);
        this.interval = interval;
    }


    @Override
    public synchronized void start() {
        if (executors != null) {
            return;
        }
        long intervalMillis = Math.max(1L, interval.toMillis());
        List<ScheduledExecutorService> started = new ArrayList<>();
        for (MonitoredReplica replica : replicas) {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, THREAD_NAME + "-" + replica.target().name());
                thread.setDaemon(true);
                return thread;
            });
            executor.scheduleWithFixedDelay(() -> check(replica), 0L, intervalMillis, TimeUnit.MILLISECONDS);
            started.add(executor);
        }
        executors = List.copyOf(started);
    }


    /**
     * One check of every replica, one after the other on the calling thread (tests).
     */
    void checkNow() {
        replicas.forEach(this::check);
    }


    /**
     * One check of one replica. Never throws: an exception would cancel all later runs of a ScheduledExecutorService
     * task.
     */
    private void check(MonitoredReplica replica) {
        try {
            checkReplica(replica);
        } catch (RuntimeException ex) {
            LOGGER.warn("Replica lag check of {} failed", replica.target().name(), ex);
        }
    }


    private void checkReplica(MonitoredReplica monitored) {
        ReplicaTarget replica = monitored.target();
        try {
            samplePrimaryPosition();
        } catch (SQLException ex) {
            replica.primaryPositionUnknown("primary WAL position unavailable: " + ex.getMessage());
            return;
        }
        ConnectionSettings settings = monitored.settings();
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
        replica.measured(history.freshness(replayLsn));
    }


    /**
     * Records where the primary is now. The time is taken before the query, so every transaction committed before it
     * has its commit record at or below the position returned.
     */
    private void samplePrimaryPosition() throws SQLException {
        synchronized (primaryLock) {
            long sampledAt = System.nanoTime();
            try (Statement statement = connection(primary).createStatement();
                 ResultSet result = statement.executeQuery(PRIMARY_POSITION_SQL)) {
                requireRow(result);
                history.record(sampledAt, result.getLong(1));
            } catch (SQLException ex) {
                closeConnection(primary.key());
                throw ex;
            }
        }
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
     * Stops the checks and closes the monitor's connections once the monitor threads have ended.
     */
    @Override
    public synchronized void stop() {
        if (executors != null) {
            executors.forEach(ScheduledExecutorService::shutdownNow);
            try {
                for (ScheduledExecutorService executor : executors) {
                    if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                        LOGGER.warn("Replica lag monitor did not stop within 10s");
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                executors = null;
            }
        }
        for (String key : List.copyOf(connections.keySet())) {
            closeConnection(key);
        }
    }


    @Override
    public synchronized boolean isRunning() {
        return executors != null;
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
