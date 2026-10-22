package com.laioffer.onlineorder.push;


import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;


import java.time.Duration;


/**
 * Push signals over Server-Sent Events ({@code app.push.*}, docs/adr/0003-push-signals.md).
 *
 * @param fanout            how a signal reaches the streams: {@link Fanout#REDIS} through Redis pub/sub to every
 *                          instance, {@link Fanout#LOCAL} only to this instance's streams
 * @param channel           the Redis pub/sub channel of the redis fan-out; the suffix versions the message format
 * @param streamTimeout     a stream is completed after this long; the browser opens a new one by itself
 * @param keepaliveInterval time between keepalive comments, each followed by the re-check of a merchant's or rider's
 *                          active flag; keep it well below the proxies' read timeout
 * @param maxStreamsPerUser open streams one login may hold on this instance; the next is refused with 429
 * @param maxStreams        open streams this instance holds at most; the next is refused with 503
 */
@ConfigurationProperties("app.push")
public record PushProperties(
        @DefaultValue("redis") Fanout fanout,
        @DefaultValue("onlineorder:push:v1") String channel,
        @DefaultValue("30m") Duration streamTimeout,
        @DefaultValue("25s") Duration keepaliveInterval,
        @DefaultValue("5") int maxStreamsPerUser,
        @DefaultValue("2000") int maxStreams
) {

    public enum Fanout {
        REDIS,
        LOCAL
    }


    public PushProperties {
        if (fanout == null) {
            throw new IllegalArgumentException("app.push.fanout must be redis or local");
        }
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("app.push.channel must not be blank");
        }
        requirePositive("app.push.stream-timeout", streamTimeout);
        requirePositive("app.push.keepalive-interval", keepaliveInterval);
        if (maxStreamsPerUser < 1 || maxStreams < 1) {
            throw new IllegalArgumentException(
                    "app.push.max-streams-per-user and app.push.max-streams must be at least 1");
        }
    }


    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive, got " + value);
        }
    }
}
