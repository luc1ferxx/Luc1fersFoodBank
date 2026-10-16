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
 */
public record TwoLevelCacheSettings(
        String keyNamespace,
        Duration localTtl,
        Duration remoteTtl,
        double remoteTtlJitter
) {


    public String invalidationChannel() {
        return keyNamespace + ":invalidation";
    }
}
