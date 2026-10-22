package com.laioffer.onlineorder.push;


/**
 * Carries a committed signal to the streams of its recipient: to every instance through Redis pub/sub
 * ({@link RedisPushFanout}), or to this instance's streams only ({@link LocalPushFanout}). It hands the work to a
 * thread of its own and returns at once, so a writer never waits on Redis or on a client's connection.
 */
public interface PushFanout {

    /**
     * Never throws; a signal that cannot be handed on is counted as a publish failure and dropped.
     */
    void send(PushRecipient recipient, PushTopic topic);
}
