package com.laioffer.onlineorder.cache;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.serializer.SerializationException;


import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;


class RemoteCacheCircuitBreakerTests {

    private static final long FAST = Duration.ofMillis(1).toNanos();
    private static final long SLOW = Duration.ofMillis(250).toNanos();

    private final AtomicLong now = new AtomicLong(1_000);
    private final RemoteCacheCircuitBreaker breaker = new RemoteCacheCircuitBreaker(
            Duration.ofSeconds(5),
            Duration.ofMillis(200),
            now::get
    );


    @Test
    void anError_shouldSkipRedisUntilTheOpenPeriodEndsThenLetExactlyOneProbeThrough() {
        Assertions.assertTrue(breaker.allowRequest());

        breaker.onError(new QueryTimeoutException("Redis command timed out"), Duration.ofMillis(500).toNanos());

        Assertions.assertTrue(breaker.isOpen());
        Assertions.assertFalse(breaker.allowRequest());
        advance(Duration.ofMillis(4_999));
        Assertions.assertFalse(breaker.allowRequest());

        advance(Duration.ofMillis(1));
        Assertions.assertTrue(breaker.allowRequest(), "the open period is over: one probe");
        Assertions.assertFalse(breaker.allowRequest(), "everyone else keeps skipping while the probe runs");
        Assertions.assertTrue(breaker.isOpen());
    }


    @Test
    void aFailedProbe_shouldStartAnotherOpenPeriodAndASuccessfulOneShouldClose() {
        breaker.onError(new QueryTimeoutException("timeout"), FAST);
        advance(Duration.ofSeconds(5));
        Assertions.assertTrue(breaker.allowRequest());

        breaker.onError(new QueryTimeoutException("still hung"), FAST);
        advance(Duration.ofSeconds(4));
        Assertions.assertFalse(breaker.allowRequest(), "a failed probe re-opens for a full period");

        advance(Duration.ofSeconds(1));
        Assertions.assertTrue(breaker.allowRequest());
        breaker.onSuccess(FAST);

        Assertions.assertFalse(breaker.isOpen());
        Assertions.assertTrue(breaker.allowRequest());
        Assertions.assertTrue(breaker.allowRequest());
    }


    @Test
    void aProbeThatNeverReportsBack_shouldNotKeepRedisSkippedForever() {
        breaker.onError(new QueryTimeoutException("timeout"), FAST);
        advance(Duration.ofSeconds(5));
        Assertions.assertTrue(breaker.allowRequest());

        advance(Duration.ofSeconds(5));

        Assertions.assertTrue(breaker.allowRequest(), "another caller probes once the next period ends");
    }


    @Test
    void consecutiveSlowCalls_shouldOpenButAFastCallInBetweenResetsTheCount() {
        breaker.onSuccess(SLOW);
        breaker.onSuccess(SLOW);
        breaker.onSuccess(FAST);
        breaker.onSuccess(SLOW);
        breaker.onSuccess(SLOW);
        Assertions.assertFalse(breaker.isOpen(), "a single slow call (a new connection, a GC pause) is tolerated");

        breaker.onSuccess(SLOW);

        Assertions.assertTrue(breaker.isOpen(), RemoteCacheCircuitBreaker.SLOW_CALLS_TO_OPEN + " slow calls in a row");
        Assertions.assertFalse(breaker.allowRequest());
    }


    @Test
    void anUnreadableEntry_shouldNotOpenTheBreakerBecauseRedisAnswered() {
        breaker.onError(new SerializationException("Could not read JSON"), FAST);

        Assertions.assertFalse(breaker.isOpen());
        Assertions.assertTrue(breaker.allowRequest());
    }


    @Test
    void reset_shouldClose() {
        breaker.onError(new QueryTimeoutException("timeout"), FAST);

        breaker.reset();

        Assertions.assertFalse(breaker.isOpen());
        Assertions.assertTrue(breaker.allowRequest());
    }


    @Test
    void disabled_shouldNeverOpen() {
        RemoteCacheCircuitBreaker disabled = RemoteCacheCircuitBreaker.disabled();

        disabled.onError(new QueryTimeoutException("timeout"), FAST);
        for (int i = 0; i < 5; i++) {
            disabled.onSuccess(Duration.ofSeconds(1).toNanos());
        }

        Assertions.assertFalse(disabled.isOpen());
        Assertions.assertTrue(disabled.allowRequest());
    }


    private void advance(Duration duration) {
        now.addAndGet(duration.toNanos());
    }
}
