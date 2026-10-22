package com.laioffer.onlineorder.push;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


/**
 * The open streams of this instance, by recipient. Registering checks both limits under one lock, so concurrent
 * connects of one login never pass {@code maxStreamsPerUser} together; delivering reads the concurrent index without
 * the lock. The limits are per instance: a login whose streams land on several instances may hold that many on each.
 */
public class PushStreamRegistry {

    /**
     * The answer to {@link #register}.
     */
    public enum Registration {
        REGISTERED,
        USER_LIMIT,
        INSTANCE_LIMIT,
        CLOSED
    }


    private final int maxStreams;
    private final int maxStreamsPerUser;
    private final PushMetrics metrics;
    private final Map<PushRecipient, Set<PushStream>> byRecipient = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    // Guarded by lock.
    private final Map<Long, Integer> perUser = new HashMap<>();
    private final Set<PushStream> streams = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;


    public PushStreamRegistry(int maxStreams, int maxStreamsPerUser, PushMetrics metrics) {
        this.maxStreams = maxStreams;
        this.maxStreamsPerUser = maxStreamsPerUser;
        this.metrics = metrics;
    }


    /**
     * Adds the stream unless a limit is reached or the instance is shutting down. A registered stream leaves the
     * registry when it closes, whatever closes it.
     */
    public Registration register(PushStream stream) {
        synchronized (lock) {
            if (closed) {
                return Registration.CLOSED;
            }
            int userStreams = perUser.getOrDefault(stream.userId(), 0);
            if (userStreams >= maxStreamsPerUser) {
                return Registration.USER_LIMIT;
            }
            if (streams.size() >= maxStreams) {
                return Registration.INSTANCE_LIMIT;
            }
            perUser.put(stream.userId(), userStreams + 1);
            streams.add(stream);
            for (PushRecipient recipient : stream.recipients()) {
                byRecipient.computeIfAbsent(recipient, ignored -> ConcurrentHashMap.newKeySet()).add(stream);
            }
        }
        stream.onClose(() -> unregister(stream));
        if (stream.isClosed()) {
            // Closed while it was being registered (the client left at once): the hook came too late to run.
            unregister(stream);
        }
        return Registration.REGISTERED;
    }


    /**
     * Writes the signal to every open stream of the recipient.
     *
     * @return the streams it was written to
     */
    public int deliver(PushRecipient recipient, PushTopic topic) {
        Set<PushStream> targets = byRecipient.get(recipient);
        if (targets == null || targets.isEmpty()) {
            return 0;
        }
        String frame = SseFrames.signal(topic);
        int written = 0;
        for (PushStream stream : targets) {
            if (stream.send(frame)) {
                metrics.delivered(topic);
                written++;
            }
        }
        return written;
    }


    /**
     * A snapshot of the open streams.
     */
    public List<PushStream> streams() {
        return new ArrayList<>(streams);
    }


    public int size() {
        return streams.size();
    }


    /**
     * Takes streams again after {@link #close}.
     */
    public void open() {
        synchronized (lock) {
            closed = false;
        }
    }


    /**
     * Refuses every later registration and completes the open streams, so their browsers reconnect to another
     * instance instead of holding this one's shutdown.
     */
    public void close() {
        synchronized (lock) {
            closed = true;
        }
        for (PushStream stream : streams()) {
            stream.complete(null);
        }
    }


    void unregister(PushStream stream) {
        synchronized (lock) {
            if (!streams.remove(stream)) {
                return;
            }
            perUser.computeIfPresent(stream.userId(), (user, count) -> count <= 1 ? null : count - 1);
            for (PushRecipient recipient : stream.recipients()) {
                byRecipient.computeIfPresent(recipient, (key, set) -> {
                    set.remove(stream);
                    return set.isEmpty() ? null : set;
                });
            }
        }
    }
}
