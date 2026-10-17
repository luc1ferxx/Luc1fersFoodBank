package com.laioffer.onlineorder.datasource;


import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;


import java.time.Duration;
import java.util.List;


/**
 * Read/write splitting ({@code app.datasource.replica.*}, see docs/operations.md "Read replicas"). Without
 * {@link #urls} nothing of it exists and every statement uses the single primary pool, as before.
 *
 * @param urls             JDBC URLs of PostgreSQL streaming replicas (hot standbys) of the primary
 * @param username         replica login; blank means the primary's ({@code spring.datasource.username})
 * @param password         replica password; blank means the primary's
 * @param pool             one Hikari pool per replica
 * @param stickyWindow     after a session commits a write, its reads go to the primary for this long (read-your-writes)
 * @param maxLag           a replica further behind the primary than this gets no reads until it catches up
 * @param lagCheckInterval how often the lag monitor measures every replica
 * @param lagCheckTimeout  connect and socket timeout of the monitor's own connections (whole seconds, at least 1)
 */
@ConfigurationProperties("app.datasource.replica")
public record ReplicaRoutingProperties(
        @DefaultValue List<String> urls,
        String username,
        String password,
        @DefaultValue Pool pool,
        @DefaultValue("5s") Duration stickyWindow,
        @DefaultValue("2s") Duration maxLag,
        @DefaultValue("1s") Duration lagCheckInterval,
        @DefaultValue("1s") Duration lagCheckTimeout
) {

    /**
     * @param maximumSize       fixed pool size per replica and instance
     * @param connectionTimeout how long a read waits for a replica connection before it falls back to the primary
     * @param socketTimeout     upper bound for one network read on a replica connection, so a hung replica cannot hold
     *                          a request thread forever; keep it above the pool's statement_timeout
     */
    public record Pool(
            @DefaultValue("20") int maximumSize,
            @DefaultValue("1s") Duration connectionTimeout,
            @DefaultValue("15s") Duration socketTimeout
    ) {
    }


    /**
     * The configured URLs without blank entries (an unset {@code DATABASE_REPLICA_URLS} binds as one empty string).
     */
    public List<String> configuredUrls() {
        return urls == null
                ? List.of()
                : urls.stream().filter(url -> url != null && !url.isBlank()).map(String::trim).toList();
    }


    /**
     * The most a routed read can lag behind the primary: a replica is measured every {@link #lagCheckInterval} and
     * dropped once it is more than {@link #maxLag} behind, so between two checks it can fall behind by one more
     * interval.
     */
    public Duration maxStaleness() {
        return maxLag.plus(lagCheckInterval);
    }


    @Override
    public String toString() {
        return "ReplicaRoutingProperties[urls=" + configuredUrls()
                + ", username=" + username
                + ", password=" + (password == null || password.isEmpty() ? "<unset>" : "******")
                + ", pool=" + pool
                + ", stickyWindow=" + stickyWindow
                + ", maxLag=" + maxLag
                + ", lagCheckInterval=" + lagCheckInterval
                + ", lagCheckTimeout=" + lagCheckTimeout + "]";
    }
}
