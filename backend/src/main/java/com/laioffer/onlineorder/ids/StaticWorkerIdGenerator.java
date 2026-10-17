package com.laioffer.onlineorder.ids;


/**
 * Ids with the configured worker id (app.ids.worker-id, environment APP_IDS_WORKER_ID). Every instance that runs at the
 * same time needs its own value: the default 0 suits a single local instance, the tests, and a StatefulSet that sets
 * APP_IDS_WORKER_ID from its pod index (docs/operations.md, section 8). A Deployment whose pods come and go uses
 * app.ids.worker-id-source=lease instead.
 */
public final class StaticWorkerIdGenerator implements DistributedIdGenerator {

    private final SnowflakeIdGenerator generator;


    public StaticWorkerIdGenerator(SnowflakeIdGenerator generator) {
        this.generator = generator;
    }


    @Override
    public long nextId() {
        return generator.nextId();
    }


    @Override
    public int workerId() {
        return generator.workerId();
    }
}
