package com.laioffer.onlineorder.cache;


import java.time.Duration;


/**
 * Settings shared by all two-level caches.
 *
 * @param keyNamespace   first part of every Redis key and of the invalidation channel, e.g. {@code onlineorder:cache}
 * @param localTtl       L1 expire-after-write; bounds how long an instance can serve an entry whose invalidation
 *                       message it missed
 * @param remoteTtl      base Redis TTL before jitter
 * @param remoteTtlJitter fraction of {@code remoteTtl} added or removed at random on every Redis write
 * @param remoteCircuitOpenDuration how long reads and write-backs skip Redis after it fails or is slow; zero
 *                       disables the breaker (see {@link RemoteCacheCircuitBreaker})
 * @param remoteSlowCallThreshold a Redis call at least this slow counts as slow; zero disables slow-call detection
 */
public record TwoLevelCacheSettings(
        String keyNamespace,
        Duration localTtl,
        Duration remoteTtl,
        double remoteTtlJitter,
        Duration remoteCircuitOpenDuration,
        Duration remoteSlowCallThreshold
) {


    public String invalidationChannel() {
        return keyNamespace + ":invalidation";
    }
}
