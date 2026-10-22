package com.laioffer.onlineorder.push;


import org.junit.jupiter.api.Assertions;


import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;


/**
 * A push stream's connection that records the frames written to it, for tests that open streams without HTTP. Waiting
 * for a frame blocks on a queue the writer fills, never on a sleep.
 */
public final class RecordingChannel implements PushStream.Channel {

    private final List<String> frames = new CopyOnWriteArrayList<>();
    private final BlockingQueue<String> unread = new LinkedBlockingQueue<>();
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile boolean failing;


    /**
     * Registers a stream of {@code userId} for {@code recipients} that is never re-checked.
     */
    public static RecordingChannel open(PushStreamRegistry registry, long userId, PushRecipient... recipients) {
        RecordingChannel channel = new RecordingChannel();
        PushStream stream = new PushStream(userId, Set.of(recipients), PushStream.AccessCheck.ALWAYS, channel);
        Assertions.assertEquals(PushStreamRegistry.Registration.REGISTERED, registry.register(stream));
        return channel;
    }


    @Override
    public void send(String frame) throws IOException {
        if (failing) {
            throw new IOException("Broken pipe");
        }
        frames.add(frame);
        unread.add(frame);
    }


    @Override
    public void complete() {
        completed.countDown();
    }


    /**
     * Makes every later write fail, like a client that went away.
     */
    public void failWrites() {
        failing = true;
    }


    public List<String> frames() {
        return List.copyOf(frames);
    }


    public boolean isCompleted() {
        return completed.getCount() == 0;
    }


    /**
     * Waits for the next frame that matches, consuming the frames before it.
     */
    public String awaitFrame(Predicate<String> matching, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                long left = deadline - System.nanoTime();
                String frame = left <= 0 ? null : unread.poll(left, TimeUnit.NANOSECONDS);
                if (frame == null) {
                    return Assertions.fail("No matching frame within " + timeout + "; written so far: " + frames);
                }
                if (matching.test(frame)) {
                    return frame;
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }


    /**
     * Waits for the signal of {@code topic}.
     */
    public String awaitSignal(PushTopic topic, Duration timeout) {
        return awaitFrame(SseFrames.signal(topic)::equals, timeout);
    }


    /**
     * The signals of {@code topic} written so far.
     */
    public long signals(PushTopic topic) {
        return frames.stream().filter(SseFrames.signal(topic)::equals).count();
    }
}
