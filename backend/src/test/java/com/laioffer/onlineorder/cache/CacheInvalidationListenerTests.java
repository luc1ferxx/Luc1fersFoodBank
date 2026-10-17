package com.laioffer.onlineorder.cache;


import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;


import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;


class CacheInvalidationListenerTests {

    private static final byte[] CHANNEL = "onlineorder:cache:invalidation".getBytes(StandardCharsets.UTF_8);


    /**
     * Readers of {@link CacheInvalidationListener#subscriptionCount()} treat a higher count as "re-subscribed
     * and L1 already reset". Counting before the clear let them see the new subscription while L1 still held
     * entries from before the disconnect.
     */
    @Test
    void onChannelSubscribed_shouldClearL1BeforeCountingTheSubscription() {
        AtomicReference<CacheInvalidationListener> listener = new AtomicReference<>();
        List<Long> countsSeenWhileClearing = new CopyOnWriteArrayList<>();
        TwoLevelCache menu = new TwoLevelCache(
                CatalogCaches.MENU,
                Caffeine.newBuilder().build(),
                new TwoLevelCacheTests.FakeRemoteStore(),
                new TwoLevelCacheTests.RecordingPublisher(),
                new TwoLevelCacheTests.RecordingErrorHandler()
        ) {
            @Override
            public void clearLocal() {
                countsSeenWhileClearing.add(listener.get().subscriptionCount());
                super.clearLocal();
            }
        };
        listener.set(new CacheInvalidationListener(new TwoLevelCacheManager(List.of(menu)), TwoLevelCaches.objectMapper(), "instance"));

        menu.get(5L, () -> "menu-5");
        listener.get().onChannelSubscribed(CHANNEL, 1);

        Assertions.assertEquals(List.of(0L), countsSeenWhileClearing, "L1 is cleared before the subscription is counted");
        Assertions.assertEquals(1, listener.get().subscriptionCount());
        Assertions.assertFalse(menu.isCachedLocally(5L));

        menu.get(5L, () -> "menu-5");
        listener.get().onChannelSubscribed(CHANNEL, 1);

        Assertions.assertEquals(List.of(0L, 1L), countsSeenWhileClearing, "a re-subscription is counted after its clear too");
        Assertions.assertEquals(2, listener.get().subscriptionCount());
        Assertions.assertFalse(menu.isCachedLocally(5L));
    }


    @Test
    void onMessage_shouldApplyOtherInstancesInvalidationsAndIgnoreItsOwnAndMalformedOnes() {
        TwoLevelCache menu = new TwoLevelCache(
                CatalogCaches.MENU,
                Caffeine.newBuilder().build(),
                new TwoLevelCacheTests.FakeRemoteStore(),
                new TwoLevelCacheTests.RecordingPublisher(),
                new TwoLevelCacheTests.RecordingErrorHandler()
        );
        CacheInvalidationListener listener = new CacheInvalidationListener(
                new TwoLevelCacheManager(List.of(menu)), TwoLevelCaches.objectMapper(), "instance"
        );
        menu.get(5L, () -> "menu-5");
        menu.get(6L, () -> "menu-6");

        listener.onMessage(message("{\"cache\":\"menu\",\"key\":\"5\",\"origin\":\"instance\"}"), null);
        listener.onMessage(message("not json"), null);
        listener.onMessage(message("{\"cache\":\"unknown\",\"key\":\"5\",\"origin\":\"other\"}"), null);
        Assertions.assertTrue(menu.isCachedLocally(5L), "own, malformed and unknown-cache messages change nothing");
        Assertions.assertEquals(0, menu.remoteInvalidationsReceivedCount());

        listener.onMessage(message("{\"cache\":\"menu\",\"key\":\"5\",\"origin\":\"other\"}"), null);
        Assertions.assertFalse(menu.isCachedLocally(5L));
        Assertions.assertTrue(menu.isCachedLocally(6L));

        listener.onMessage(message("{\"cache\":\"menu\",\"key\":null,\"origin\":\"other\"}"), null);
        Assertions.assertFalse(menu.isCachedLocally(6L), "a null key is a remote clear");
        Assertions.assertEquals(2, menu.remoteInvalidationsReceivedCount());
    }


    private static DefaultMessage message(String body) {
        return new DefaultMessage(CHANNEL, body.getBytes(StandardCharsets.UTF_8));
    }
}
