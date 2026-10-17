package com.laioffer.onlineorder.ids;


/**
 * Issues 64-bit Snowflake ids ({@link SnowflakeIdGenerator}) that are unique across every instance of the
 * application: each instance runs with its own worker id, configured (app.ids.worker-id-source=static) or leased from
 * the database (lease). Used for public order numbers today, and meant as the id source once orders are split across
 * databases (docs/adr/0001-order-sharding.md).
 */
public interface DistributedIdGenerator {

    /**
     * @throws IdGeneratorUnavailableException when no id can be issued right now (answered 503)
     */
    long nextId();


    /**
     * @return the worker id ids are issued with, or -1 while none is held
     */
    int workerId();
}
