package com.laioffer.onlineorder.datasource;


import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.transaction.support.TransactionSynchronizationManager;


import java.sql.SQLTransientConnectionException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Decides, when a transaction executes its first statement, which pool serves it.
 *
 * <p>Only a read-only transaction declared by application code ({@code @Transactional(readOnly = true)} on a service
 * method, or {@link ReadOnlyTransactions}) may go to a replica; everything else, including every statement outside a
 * transaction, uses the primary. Spring Data runs its CRUD finders ({@code findById}, {@code existsById},
 * {@code findAll*}, {@code count}) in a read-only transaction of their own when they are called outside one; those are
 * recognised by their transaction name and stay on the primary, so a stray finder in write-path code can never read a
 * lagging replica. Read replicas are opt-in per service method.
 *
 * <p>A replica-eligible transaction still uses the primary while the session is inside its read-your-writes window, or
 * while no replica is routable (lag above the threshold, unreachable). Among routable replicas reads are spread round
 * robin.
 */
public class ReplicaRouter {

    static final String PRIMARY = "primary";
    static final String REASON_REPLICA = "replica";
    static final String REASON_STICKY = "sticky";
    static final String REASON_NO_REPLICA = "no_replica";
    static final String REASON_REPLICA_FAILED = "replica_failed";

    /**
     * Transaction names of Spring Data's own repository transactions start with the repository implementation class,
     * for example {@code org.springframework.data.jdbc.repository.support.SimpleJdbcRepository.findById}.
     */
    static final String SPRING_DATA_TRANSACTION_PREFIX = "org.springframework.data.";

    static final String READS_METRIC = "onlineorder.db.routing.reads";

    private final List<ReplicaTarget> replicas;
    private final ReadYourWritesTracker readYourWrites;
    private final AtomicInteger nextReplica = new AtomicInteger();
    private volatile Map<String, Counter> readCounters = Map.of();


    public ReplicaRouter(List<ReplicaTarget> replicas, ReadYourWritesTracker readYourWrites) {
        this.replicas = List.copyOf(replicas);
        this.readYourWrites = readYourWrites;
    }


    /**
     * Where the statement about to run belongs. Called by {@link ReadWriteRoutingDataSource} when the transaction (or
     * the statement outside one) asks for its physical connection.
     */
    Route route() {
        if (!isReplicaEligibleTransaction()) {
            return Route.WRITE;
        }
        if (readYourWrites.readsMustUsePrimary()) {
            return Route.STICKY;
        }
        ReplicaTarget replica = nextRoutableReplica();
        return replica == null ? Route.NO_REPLICA : new Route(replica, replica.name(), REASON_REPLICA);
    }


    /**
     * The read was served by the route's target; counts reads only (a write route is not counted).
     */
    void recordRead(Route route) {
        if (route.reason() == null) {
            return;
        }
        Counter counter = readCounters.get(counterKey(route.target(), route.reason()));
        if (counter != null) {
            counter.increment();
        }
    }


    /**
     * A routed read could not get a connection from the replica's pool and falls back to the primary. A pool that is
     * merely exhausted (Hikari's timeout without an underlying connection failure) keeps the replica routable; anything
     * else takes it out of rotation until the lag monitor sees it healthy again.
     *
     * @return the route the read takes instead
     */
    Route replicaFailed(ReplicaTarget replica, Exception failure) {
        boolean poolExhausted = failure instanceof SQLTransientConnectionException && failure.getCause() == null;
        if (!poolExhausted) {
            replica.unreachable("no connection: " + failure);
        }
        return Route.REPLICA_FAILED;
    }


    static boolean isReplicaEligibleTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return false;
        }
        String name = TransactionSynchronizationManager.getCurrentTransactionName();
        return name == null || !name.startsWith(SPRING_DATA_TRANSACTION_PREFIX);
    }


    private ReplicaTarget nextRoutableReplica() {
        int size = replicas.size();
        int start = Math.floorMod(nextReplica.getAndIncrement(), size);
        for (int i = 0; i < size; i++) {
            ReplicaTarget candidate = replicas.get((start + i) % size);
            if (candidate.isRoutable()) {
                return candidate;
            }
        }
        return null;
    }


    /**
     * Registers the read counters, {@code onlineorder.db.routing.reads{target, reason}}: one per replica (reason
     * replica) and the primary's reasons for serving a replica-eligible read.
     */
    void bindTo(MeterRegistry registry) {
        Map<String, Counter> counters = new HashMap<>();
        for (ReplicaTarget replica : replicas) {
            counters.put(counterKey(replica.name(), REASON_REPLICA), counter(registry, replica.name(), REASON_REPLICA));
        }
        for (String reason : List.of(REASON_STICKY, REASON_NO_REPLICA, REASON_REPLICA_FAILED)) {
            counters.put(counterKey(PRIMARY, reason), counter(registry, PRIMARY, reason));
        }
        readCounters = Map.copyOf(counters);
    }


    List<ReplicaTarget> replicas() {
        return replicas;
    }


    private static Counter counter(MeterRegistry registry, String target, String reason) {
        return Counter.builder(READS_METRIC)
                .description("Read-only transactions by the pool that served them and why: replica, or the primary "
                        + "because of the read-your-writes window (sticky), no routable replica (no_replica) or a "
                        + "failed replica connection (replica_failed)")
                .tag("target", target)
                .tag("reason", reason)
                .register(registry);
    }


    private static String counterKey(String target, String reason) {
        return target + '|' + reason;
    }


    /**
     * @param replica the replica to read from, null for the primary
     * @param target  the metric's target tag
     * @param reason  the metric's reason tag; null for a write (not counted)
     */
    record Route(ReplicaTarget replica, String target, String reason) {

        static final Route WRITE = new Route(null, PRIMARY, null);
        static final Route STICKY = new Route(null, PRIMARY, REASON_STICKY);
        static final Route NO_REPLICA = new Route(null, PRIMARY, REASON_NO_REPLICA);
        static final Route REPLICA_FAILED = new Route(null, PRIMARY, REASON_REPLICA_FAILED);
    }
}
