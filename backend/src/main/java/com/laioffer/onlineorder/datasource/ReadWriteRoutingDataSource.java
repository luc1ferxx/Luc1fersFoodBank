package com.laioffer.onlineorder.datasource;


import org.springframework.jdbc.datasource.AbstractDataSource;


import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;


/**
 * Hands out a primary or a replica connection as {@link ReplicaRouter} decides. It sits behind a
 * {@link org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy}: a transaction manager asks for its
 * connection when the transaction begins, before Spring has marked the transaction read-only, so the decision must wait
 * for the first statement, which is when the lazy proxy calls {@link #getConnection()}.
 *
 * <p>A replica whose pool cannot hand out a connection costs the read at most the pool's connection timeout; the read
 * then runs on the primary. No statement has run at that point, so switching is safe.
 *
 * <p>{@link #unwrap} and {@link #isWrapperFor} answer for the primary pool, so Spring Boot's health indicator and pool
 * metadata see the primary (the replicas' pools are instrumented by {@link ReadReplicaMetrics}).
 */
final class ReadWriteRoutingDataSource extends AbstractDataSource {

    private final DataSource primary;
    private final ReplicaRouter router;


    ReadWriteRoutingDataSource(DataSource primary, ReplicaRouter router) {
        this.primary = primary;
        this.router = router;
    }


    @Override
    public Connection getConnection() throws SQLException {
        ReplicaRouter.Route route = router.route();
        ReplicaTarget replica = route.replica();
        if (replica == null) {
            Connection connection = primary.getConnection();
            router.recordRead(route);
            return connection;
        }
        Connection connection;
        try {
            connection = replica.dataSource().getConnection();
        } catch (SQLException | RuntimeException failure) {
            // Hikari reports a pool that cannot start (replica down at its first use) as a RuntimeException.
            ReplicaRouter.Route fallback = router.replicaFailed(replica, failure);
            Connection primaryConnection = primary.getConnection();
            router.recordRead(fallback);
            return primaryConnection;
        }
        router.recordRead(route);
        return connection;
    }


    /**
     * Explicit credentials bypass routing: the replicas have their own login.
     */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return primary.getConnection(username, password);
    }


    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return primary.unwrap(iface);
    }


    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || primary.isWrapperFor(iface);
    }
}
