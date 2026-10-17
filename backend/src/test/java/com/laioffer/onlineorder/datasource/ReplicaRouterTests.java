package com.laioffer.onlineorder.datasource;


import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jakarta.servlet.http.Cookie;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;


import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.List;


/**
 * Routing decisions and the routing DataSource, with the transaction state set directly on
 * {@link TransactionSynchronizationManager} as a transaction manager would.
 */
class ReplicaRouterTests {

    /** A max-lag no test outlives: a replica found fresh stays routable. */
    private static final Duration LONG = Duration.ofHours(1);

    private final ReadYourWritesTracker readYourWrites = Mockito.mock(ReadYourWritesTracker.class);
    private final DataSource primary = Mockito.mock(DataSource.class);
    private final DataSource replicaPool1 = Mockito.mock(DataSource.class);
    private final DataSource replicaPool2 = Mockito.mock(DataSource.class);
    private final ReplicaTarget replica1 = new ReplicaTarget("replica-1", "jdbc:postgresql://r1/db", replicaPool1, LONG);
    private final ReplicaTarget replica2 = new ReplicaTarget("replica-2", "jdbc:postgresql://r2/db", replicaPool2, LONG);
    private final MeterRegistry registry = new SimpleMeterRegistry();
    private ReplicaRouter router;
    private ReadWriteRoutingDataSource dataSource;


    @BeforeEach
    void setUp() {
        router = new ReplicaRouter(List.of(replica1, replica2), readYourWrites);
        router.bindTo(registry);
        dataSource = new ReadWriteRoutingDataSource(primary, router);
        replica1.measured(fresh());
        replica2.measured(fresh());
    }


    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionName(null);
        RequestContextHolder.resetRequestAttributes();
    }


    @Test
    void statementsOutsideATransactionAndWriteTransactionsUseThePrimaryAndAreNotCountedAsReads() {
        Assertions.assertSame(ReplicaRouter.Route.WRITE, router.route());

        inTransaction(false, "com.example.OrderService.checkout");
        Assertions.assertSame(ReplicaRouter.Route.WRITE, router.route());
        router.recordRead(ReplicaRouter.Route.WRITE);
        Assertions.assertEquals(0.0, totalReads());

        // Read-only flag without an actual transaction (propagation SUPPORTS): no transaction, no replica.
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        Assertions.assertSame(ReplicaRouter.Route.WRITE, router.route());
    }


    @Test
    void readOnlyTransactionsOfTheApplicationAreSpreadOverTheRoutableReplicas() throws SQLException {
        inTransaction(true, "com.laioffer.onlineorder.service.OrderService.getOrdersByCustomerId");
        Connection first = Mockito.mock(Connection.class);
        Connection second = Mockito.mock(Connection.class);
        Mockito.when(replicaPool1.getConnection()).thenReturn(first);
        Mockito.when(replicaPool2.getConnection()).thenReturn(second);

        List<Connection> connections = List.of(
                dataSource.getConnection(), dataSource.getConnection(), dataSource.getConnection(), dataSource.getConnection());

        Assertions.assertEquals(2, connections.stream().filter(connection -> connection == first).count());
        Assertions.assertEquals(2, connections.stream().filter(connection -> connection == second).count());
        Assertions.assertEquals(2.0, reads("replica-1", ReplicaRouter.REASON_REPLICA));
        Assertions.assertEquals(2.0, reads("replica-2", ReplicaRouter.REASON_REPLICA));
        Mockito.verifyNoInteractions(primary);
    }


    @Test
    void aProgrammaticReadOnlyTransactionWithoutANameIsTheApplicationsToo() {
        inTransaction(true, null);
        Assertions.assertNotNull(router.route().replica());
    }


    @Test
    void springDatasOwnReadOnlyTransactionsStayOnThePrimary() {
        inTransaction(true, "org.springframework.data.jdbc.repository.support.SimpleJdbcRepository.findById");
        Assertions.assertSame(ReplicaRouter.Route.WRITE, router.route());
    }


    @Test
    void theReadYourWritesWindowKeepsReadsOnThePrimary() throws SQLException {
        inTransaction(true, "com.example.Service.read");
        Mockito.when(readYourWrites.readsMustUsePrimary()).thenReturn(true);
        Connection connection = Mockito.mock(Connection.class);
        Mockito.when(primary.getConnection()).thenReturn(connection);

        Assertions.assertSame(connection, dataSource.getConnection());
        Assertions.assertEquals(1.0, reads(ReplicaRouter.PRIMARY, ReplicaRouter.REASON_STICKY));
        Mockito.verifyNoInteractions(replicaPool1, replicaPool2);
    }


    @Test
    void readsGoToThePrimaryWhileNoReplicaIsRoutable_andSkipAnUnroutableOne() throws SQLException {
        inTransaction(true, "com.example.Service.read");
        replica1.measured(new WalPositionHistory.Freshness(System.nanoTime(), false));
        Connection replicaConnection = Mockito.mock(Connection.class);
        Mockito.when(replicaPool2.getConnection()).thenReturn(replicaConnection);

        Assertions.assertSame(replicaConnection, dataSource.getConnection());
        Assertions.assertSame(replicaConnection, dataSource.getConnection());

        replica2.unreachable("down");
        Connection primaryConnection = Mockito.mock(Connection.class);
        Mockito.when(primary.getConnection()).thenReturn(primaryConnection);
        Assertions.assertSame(primaryConnection, dataSource.getConnection());
        Assertions.assertEquals(1.0, reads(ReplicaRouter.PRIMARY, ReplicaRouter.REASON_NO_REPLICA));
        Mockito.verifyNoInteractions(replicaPool1);
    }


    @Test
    void aReplicaThatCannotHandOutAConnectionFallsBackToThePrimaryAndLeavesTheRotation() throws SQLException {
        inTransaction(true, "com.example.Service.read");
        replica2.unreachable("down");
        SQLException refused = new SQLTransientConnectionException(
                "onlineorder-db-replica-1 - Connection is not available", new SQLException("Connection refused"));
        Mockito.when(replicaPool1.getConnection()).thenThrow(refused);
        Connection primaryConnection = Mockito.mock(Connection.class);
        Mockito.when(primary.getConnection()).thenReturn(primaryConnection);

        Assertions.assertSame(primaryConnection, dataSource.getConnection());

        Assertions.assertEquals(ReplicaTarget.State.UNREACHABLE, replica1.state());
        Assertions.assertEquals(1.0, reads(ReplicaRouter.PRIMARY, ReplicaRouter.REASON_REPLICA_FAILED));
        Assertions.assertEquals(0.0, reads("replica-1", ReplicaRouter.REASON_REPLICA));
    }


    @Test
    void anExhaustedReplicaPoolFallsBackForThisReadOnly() throws SQLException {
        inTransaction(true, "com.example.Service.read");
        replica2.unreachable("down");
        Mockito.when(replicaPool1.getConnection()).thenThrow(new SQLTransientConnectionException(
                "onlineorder-db-replica-1 - Connection is not available, request timed out after 1000ms"));
        Connection primaryConnection = Mockito.mock(Connection.class);
        Mockito.when(primary.getConnection()).thenReturn(primaryConnection);

        Assertions.assertSame(primaryConnection, dataSource.getConnection());

        Assertions.assertTrue(replica1.isRoutable(), "a busy replica stays in rotation");
        Assertions.assertEquals(1.0, reads(ReplicaRouter.PRIMARY, ReplicaRouter.REASON_REPLICA_FAILED));
    }


    @Test
    void aPoolThatCannotStartFallsBackToo() throws SQLException {
        inTransaction(true, "com.example.Service.read");
        replica2.unreachable("down");
        Mockito.when(replicaPool1.getConnection()).thenThrow(new IllegalStateException("pool initialization failed"));
        Mockito.when(primary.getConnection()).thenReturn(Mockito.mock(Connection.class));

        Assertions.assertNotNull(dataSource.getConnection());
        Assertions.assertEquals(ReplicaTarget.State.UNREACHABLE, replica1.state());
    }


    @Test
    void readsAreNotCountedBeforeTheMetricsAreBound() throws SQLException {
        ReplicaRouter unbound = new ReplicaRouter(List.of(replica1), readYourWrites);
        inTransaction(true, "com.example.Service.read");
        Mockito.when(replicaPool1.getConnection()).thenReturn(Mockito.mock(Connection.class));

        Assertions.assertNotNull(new ReadWriteRoutingDataSource(primary, unbound).getConnection());
        Assertions.assertEquals(0.0, reads("replica-1", ReplicaRouter.REASON_REPLICA));
    }


    @Test
    void aRequestKeepsReadingTheReplicaItStartedOn() {
        inTransaction(true, "com.example.Service.read");
        // Without a request: round robin.
        Assertions.assertNotSame(router.route().replica(), router.route().replica());

        inRequest(new MockHttpServletRequest());
        ReplicaTarget first = router.route().replica();
        Assertions.assertNotNull(first);
        for (int i = 0; i < 5; i++) {
            Assertions.assertSame(first, router.route().replica(), "the request's later reads");
        }

        // The next request may get the other replica.
        inRequest(new MockHttpServletRequest());
        Assertions.assertNotSame(first, router.route().replica());
    }


    @Test
    void aRequestWhoseReplicaDropsOutReadsThePrimaryNotAnotherReplica() {
        inTransaction(true, "com.example.Service.read");
        inRequest(new MockHttpServletRequest());
        ReplicaTarget first = router.route().replica();
        first.measured(new WalPositionHistory.Freshness(System.nanoTime(), false));

        // The other replica is routable, but it may be further behind than the one the request already read.
        Assertions.assertSame(ReplicaRouter.Route.NO_REPLICA, router.route());
        first.measured(fresh());
        Assertions.assertSame(ReplicaRouter.Route.NO_REPLICA, router.route(), "and stays there");
    }


    @Test
    void aRequestThatHadToReadThePrimaryStaysThere() throws SQLException {
        inTransaction(true, "com.example.Service.read");
        inRequest(new MockHttpServletRequest());
        replica1.unreachable("down");
        replica2.unreachable("down");
        Mockito.when(primary.getConnection()).thenReturn(Mockito.mock(Connection.class));
        Assertions.assertSame(ReplicaRouter.Route.NO_REPLICA, router.route());

        replica1.measured(fresh());
        Assertions.assertSame(ReplicaRouter.Route.NO_REPLICA, router.route());

        // A replica connection failure also sends the rest of its request to the primary.
        inRequest(new MockHttpServletRequest());
        Mockito.when(replicaPool1.getConnection()).thenThrow(new SQLTransientConnectionException("busy"));
        Assertions.assertNotNull(dataSource.getConnection());
        Assertions.assertTrue(replica1.isRoutable(), "an exhausted pool stays in rotation");
        Assertions.assertSame(ReplicaRouter.Route.NO_REPLICA, router.route());
    }


    @Test
    void aSessionKeepsItsReplicaAcrossRequests_andMovesOnlyWhenItDropsOut() {
        inTransaction(true, "com.example.Service.read");
        ReplicaTarget sessions = null;
        for (int request = 0; request < 4; request++) {
            inRequest(withCookie("SESSION", "c2Vzc2lvbi0x"));
            ReplicaTarget replica = router.route().replica();
            Assertions.assertNotNull(replica);
            if (sessions != null) {
                Assertions.assertSame(sessions, replica, "request " + request + " of the session");
            }
            sessions = replica;
        }
        ReplicaTarget other = sessions == replica1 ? replica2 : replica1;
        // Some other session lands on the other replica (both are used).
        boolean otherUsed = false;
        for (int i = 0; i < 50 && !otherUsed; i++) {
            inRequest(withCookie("SESSION", "session-" + i));
            otherUsed = router.route().replica() == other;
        }
        Assertions.assertTrue(otherUsed);

        sessions.unreachable("down");
        inRequest(withCookie("SESSION", "c2Vzc2lvbi0x"));
        Assertions.assertSame(other, router.route().replica());

        // A cookie of another name is no session.
        ReplicaRouter custom = new ReplicaRouter(List.of(replica1, replica2), readYourWrites, java.util.Set.of("SID"));
        other.measured(fresh());
        sessions.measured(fresh());
        inRequest(withCookie("SID", "c2Vzc2lvbi0x"));
        ReplicaTarget bySid = custom.route().replica();
        inRequest(withCookie("SID", "c2Vzc2lvbi0x"));
        Assertions.assertSame(bySid, custom.route().replica());
    }


    @Test
    void theReadYourWritesWindowComesBeforeTheRequestsReplica() {
        inTransaction(true, "com.example.Service.read");
        inRequest(new MockHttpServletRequest());
        Assertions.assertNotNull(router.route().replica());

        Mockito.when(readYourWrites.readsMustUsePrimary()).thenReturn(true);
        Assertions.assertSame(ReplicaRouter.Route.STICKY, router.route());

        // A request that read the primary inside the window stays there once the window ends.
        Mockito.when(readYourWrites.readsMustUsePrimary()).thenReturn(false);
        Assertions.assertSame(ReplicaRouter.Route.NO_REPLICA, router.route());
    }


    @Test
    void explicitCredentialsAndUnwrappingGoToThePrimary() throws SQLException {
        Connection connection = Mockito.mock(Connection.class);
        Mockito.when(primary.getConnection("user", "secret")).thenReturn(connection);
        Mockito.when(primary.unwrap(String.class)).thenReturn("primary");
        Mockito.when(primary.isWrapperFor(String.class)).thenReturn(true);

        Assertions.assertSame(connection, dataSource.getConnection("user", "secret"));
        Assertions.assertSame(dataSource, dataSource.unwrap(ReadWriteRoutingDataSource.class));
        Assertions.assertTrue(dataSource.isWrapperFor(ReadWriteRoutingDataSource.class));
        Assertions.assertEquals("primary", dataSource.unwrap(String.class));
        Assertions.assertTrue(dataSource.isWrapperFor(String.class));
    }


    private static void inRequest(MockHttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }


    private static MockHttpServletRequest withCookie(String name, String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(name, value));
        return request;
    }


    private static WalPositionHistory.Freshness fresh() {
        return new WalPositionHistory.Freshness(System.nanoTime(), true);
    }


    private static void inTransaction(boolean readOnly, String name) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
        TransactionSynchronizationManager.setCurrentTransactionName(name);
    }


    private double reads(String target, String reason) {
        var counter = registry.find(ReplicaRouter.READS_METRIC).tag("target", target).tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }


    private double totalReads() {
        return registry.find(ReplicaRouter.READS_METRIC).counters().stream().mapToDouble(c -> c.count()).sum();
    }
}
