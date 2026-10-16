package com.laioffer.onlineorder.cache;


/**
 * The shared second level (L2) behind a {@link TwoLevelCache}. Keys are the cache-local keys; the store
 * adds its own namespace. Implementations may throw on infrastructure failures: the two-level cache treats
 * a failed read as a miss and a failed write or delete as skipped, and reports both to the cache error
 * handler.
 */
public interface RemoteCacheStore {

    Object get(String key);


    void put(String key, Object value);


    void evict(String key);


    void clear();
}
