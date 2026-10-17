package com.laioffer.onlineorder.datasource;


import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.springframework.test.context.DynamicPropertyRegistry;


import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;


/**
 * A PostgreSQL primary and a hot-standby streaming replica of it, both on the official image, set up the way the
 * compose profile "replica" sets them up: the primary runs with {@code postgres/pg_hba.conf} (replication allowed),
 * the replica runs {@code postgres/replica-entrypoint.sh} (pg_basebackup -R, then a hot standby).
 *
 * <p>The replica's server runs under a small supervisor loop instead of as the container's main process, so a test
 * can stop it ({@link #stopReplicaServer()}: connections are refused, the container and its port stay) and start it
 * again. It can also pause WAL replay ({@link #pauseReplay()}) to make the replica fall behind, or freeze the whole
 * container ({@link #freezeReplica()}) to make it hang.
 */
public final class PostgresReplicationContainers implements Startable {

    public static final String USERNAME = "postgres";
    public static final String PASSWORD = "secret";
    public static final String DATABASE = "onlineorder";

    private static final String IMAGE = "postgres:16-alpine";
    private static final String PRIMARY_ALIAS = "pg-primary";
    private static final String HBA_FILE = "/etc/postgresql/pg_hba.conf";
    private static final String REPLICA_ENTRYPOINT = "/usr/local/bin/replica-entrypoint.sh";
    private static final String STOPPED_FLAG = "/tmp/replica-stopped";
    private static final String SUPERVISOR = "while true; do "
            + "if [ -f " + STOPPED_FLAG + " ]; then sleep 0.2; continue; fi; "
            + "/bin/sh " + REPLICA_ENTRYPOINT + " || true; "
            + "sleep 0.2; "
            + "done";

    private final Network network = Network.newNetwork();
    private final PostgreSQLContainer primary;
    private final GenericContainer<?> replica;
    private volatile boolean frozen;


    @SuppressWarnings("resource")
    public PostgresReplicationContainers() {
        primary = new PostgreSQLContainer(IMAGE)
                .withNetwork(network)
                .withNetworkAliases(PRIMARY_ALIAS)
                .withDatabaseName(DATABASE)
                .withUsername(USERNAME)
                .withPassword(PASSWORD)
                .withCopyFileToContainer(MountableFile.forHostPath(Path.of("postgres/pg_hba.conf"), 0644), HBA_FILE)
                .withCommand("postgres", "-c", "fsync=off", "-c", "hba_file=" + HBA_FILE);
        replica = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withNetwork(network)
                .withEnv("PRIMARY_HOST", PRIMARY_ALIAS)
                .withEnv("PGPASSWORD", PASSWORD)
                .withCopyFileToContainer(
                        MountableFile.forHostPath(Path.of("postgres/replica-entrypoint.sh"), 0755), REPLICA_ENTRYPOINT)
                .withCreateContainerCmdModifier(command -> command
                        .withUser("postgres")
                        .withEntrypoint("/bin/sh", "-c", SUPERVISOR))
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept read-only connections.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(120)));
    }


    @Override
    public void start() {
        primary.start();
        replica.start();
    }


    @Override
    public void stop() {
        replica.stop();
        primary.stop();
        network.close();
    }


    /**
     * The primary as {@code spring.datasource.*} and the replica as the only {@code app.datasource.replica.urls}.
     */
    public void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", primary::getJdbcUrl);
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("app.datasource.replica.urls", this::replicaJdbcUrl);
    }


    public String primaryJdbcUrl() {
        return primary.getJdbcUrl();
    }


    public String replicaJdbcUrl() {
        return "jdbc:postgresql://" + replica.getHost() + ":" + replica.getMappedPort(5432) + "/" + DATABASE;
    }


    /**
     * Runs one query on the replica over a connection of its own and returns the first column of the first row.
     */
    public <T> T queryReplica(String sql, Class<T> type) throws SQLException {
        try (Connection connection = DriverManager.getConnection(replicaJdbcUrl(), USERNAME, PASSWORD);
             Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getObject(1, type);
        }
    }


    /**
     * Stops applying WAL: the replica keeps receiving it but its data stays as it is, so it falls behind the primary.
     */
    public void pauseReplay() throws SQLException {
        executeOnReplica("SELECT pg_wal_replay_pause()");
    }


    public void resumeReplay() throws SQLException {
        executeOnReplica("SELECT pg_wal_replay_resume()");
    }


    private void executeOnReplica(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(replicaJdbcUrl(), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }


    /**
     * Shuts the replica's server down (fast mode); connections to it are refused until {@link #startReplicaServer()}.
     */
    public void stopReplicaServer() throws IOException, InterruptedException {
        exec("touch " + STOPPED_FLAG + " && pg_ctl -D /var/lib/postgresql/data -m fast -w stop");
    }


    /**
     * Lets the supervisor start the replica's server again; it reconnects to the primary and catches up.
     */
    public void startReplicaServer() throws IOException, InterruptedException {
        exec("rm -f " + STOPPED_FLAG);
    }


    /**
     * Freezes every process of the replica container: connections stay open but nothing answers (a hung server).
     */
    public synchronized void freezeReplica() {
        DockerClientFactory.instance().client().pauseContainerCmd(replica.getContainerId()).exec();
        frozen = true;
    }


    /**
     * Undoes {@link #freezeReplica()}; a no-op when the replica is not frozen.
     */
    public synchronized void thawReplica() {
        if (frozen) {
            DockerClientFactory.instance().client().unpauseContainerCmd(replica.getContainerId()).exec();
            frozen = false;
        }
    }


    private void exec(String script) throws IOException, InterruptedException {
        Container.ExecResult result = replica.execInContainer("sh", "-c", script);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("'" + script + "' failed: " + result.getStderr() + result.getStdout());
        }
    }
}
