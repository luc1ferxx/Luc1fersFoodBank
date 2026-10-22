package com.laioffer.onlineorder.push;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * One open stream: the login it belongs to, the recipients it subscribed to at connect, the check that the login may
 * still hold it, and the connection it writes to. A stream that failed a write, was completed or was refused is closed
 * once and stays closed; its {@link #onClose} hook takes it out of the registry.
 */
public final class PushStream {

    private static final Logger LOGGER = LoggerFactory.getLogger(PushStream.class);

    private final long userId;
    private final Set<PushRecipient> recipients;
    private final AccessCheck accessCheck;
    private final Channel channel;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long openedAtNanos = System.nanoTime();
    private volatile Runnable onClose = () -> {
    };


    /**
     * @param userId      the customer id of the login, which the per-user limit counts
     * @param recipients  the recipients whose signals this stream receives
     * @param accessCheck re-checked on every keepalive; {@link AccessCheck#ALWAYS} for a stream without staff
     *                    recipients
     * @param channel     the connection
     */
    public PushStream(long userId, Set<PushRecipient> recipients, AccessCheck accessCheck, Channel channel) {
        this.userId = userId;
        this.recipients = Set.copyOf(recipients);
        this.accessCheck = accessCheck;
        this.channel = channel;
    }


    public long userId() {
        return userId;
    }


    public Set<PushRecipient> recipients() {
        return recipients;
    }


    AccessCheck accessCheck() {
        return accessCheck;
    }


    /**
     * When the stream was created, in {@link System#nanoTime()}.
     */
    long openedAtNanos() {
        return openedAtNanos;
    }


    boolean isClosed() {
        return closed.get();
    }


    void onClose(Runnable hook) {
        this.onClose = hook;
    }


    /**
     * Writes a frame. A failed write (the client went away) closes the stream; the servlet container completes the
     * request itself, so the connection is not completed here.
     *
     * @return whether the frame was written
     */
    boolean send(String frame) {
        if (closed.get()) {
            return false;
        }
        try {
            channel.send(frame);
            return true;
        } catch (IOException | RuntimeException ex) {
            LOGGER.debug("Push stream of user {} closed after a failed write: {}", userId, ex.toString());
            markClosed();
            return false;
        }
    }


    /**
     * Writes a last frame, if any, and completes the connection (a refused login, the instance shutting down).
     */
    void complete(String lastFrame) {
        if (lastFrame != null) {
            send(lastFrame);
        }
        if (markClosed()) {
            try {
                channel.complete();
            } catch (RuntimeException ex) {
                LOGGER.debug("Completing the push stream of user {} failed: {}", userId, ex.toString());
            }
        }
    }


    /**
     * Records that the connection ended (completed, timed out or failed), as its own callbacks report.
     */
    void ended() {
        markClosed();
    }


    private boolean markClosed() {
        if (!closed.compareAndSet(false, true)) {
            return false;
        }
        onClose.run();
        return true;
    }


    /**
     * The connection a stream writes to: the servlet response behind an SseEmitter, or a recorder in tests.
     */
    public interface Channel {

        void send(String frame) throws IOException;


        void complete();
    }


    /**
     * Whether the login may still hold the stream: false once its merchant account or rider was deactivated.
     */
    @FunctionalInterface
    public interface AccessCheck {

        AccessCheck ALWAYS = () -> true;


        boolean stillAllowed();
    }
}
