package com.laioffer.onlineorder.cache;


/**
 * Tells the other application instances to drop an entry (or a whole cache) from their local L1 copy.
 */
public interface CacheInvalidationPublisher {

    void publishEvict(String cacheName, String key);


    void publishClear(String cacheName);
}
