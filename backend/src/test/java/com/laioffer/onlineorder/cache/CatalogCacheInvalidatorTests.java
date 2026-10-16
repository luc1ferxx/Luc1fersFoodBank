package com.laioffer.onlineorder.cache;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;


import java.time.Duration;
import java.time.Instant;
import java.util.List;


class CatalogCacheInvalidatorTests {

    private Cache menuCache;
    private Cache restaurantsCache;
    private TaskScheduler taskScheduler;
    private CatalogCacheInvalidator invalidator;


    @BeforeEach
    void setUp() {
        menuCache = Mockito.mock(Cache.class);
        restaurantsCache = Mockito.mock(Cache.class);
        CacheManager cacheManager = Mockito.mock(CacheManager.class);
        Mockito.when(cacheManager.getCache(CatalogCaches.MENU)).thenReturn(menuCache);
        Mockito.when(cacheManager.getCache(CatalogCaches.RESTAURANTS)).thenReturn(restaurantsCache);
        taskScheduler = Mockito.mock(TaskScheduler.class);
        invalidator = new CatalogCacheInvalidator(cacheManager, providerOf(taskScheduler), Duration.ofSeconds(1));
    }


    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }


    @Test
    void evictAfterCommit_shouldWaitForTheCommitThenDeleteTwice() {
        TransactionSynchronizationManager.initSynchronization();

        invalidator.evictMenuAfterCommit(5L);

        Mockito.verifyNoInteractions(menuCache);
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        Assertions.assertEquals(1, synchronizations.size());

        TransactionSynchronizationUtils.invokeAfterCommit(synchronizations);
        Mockito.verify(menuCache, Mockito.times(1)).evict(5L);

        ArgumentCaptor<Runnable> secondDelete = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> when = ArgumentCaptor.forClass(Instant.class);
        Mockito.verify(taskScheduler).schedule(secondDelete.capture(), when.capture());
        Assertions.assertTrue(when.getValue().isAfter(Instant.now().plusMillis(500)));

        secondDelete.getValue().run();
        Mockito.verify(menuCache, Mockito.times(2)).evict(5L);
    }


    @Test
    void evictAfterCommit_whenTheTransactionRollsBack_shouldNotEvict() {
        TransactionSynchronizationManager.initSynchronization();

        invalidator.evictMenuAfterCommit(5L);
        invalidator.evictRestaurantsAfterCommit();
        TransactionSynchronizationUtils.invokeAfterCompletion(
                TransactionSynchronizationManager.getSynchronizations(),
                TransactionSynchronization.STATUS_ROLLED_BACK
        );

        Mockito.verifyNoInteractions(menuCache, restaurantsCache, taskScheduler);
    }


    @Test
    void evictAfterCommit_withoutTransaction_shouldEvictImmediately() {
        invalidator.evictRestaurantsAfterCommit();

        Mockito.verify(restaurantsCache).evict(CatalogCaches.ALL_RESTAURANTS_KEY);
        Mockito.verify(taskScheduler).schedule(Mockito.any(Runnable.class), Mockito.any(Instant.class));
    }


    @Test
    void evictAfterCommit_withZeroDelay_shouldNotScheduleASecondDelete() {
        CacheManager cacheManager = Mockito.mock(CacheManager.class);
        Mockito.when(cacheManager.getCache(CatalogCaches.MENU)).thenReturn(menuCache);
        CatalogCacheInvalidator noDoubleDelete = new CatalogCacheInvalidator(cacheManager, providerOf(taskScheduler), Duration.ZERO);

        noDoubleDelete.evictMenuAfterCommit(5L);

        Mockito.verify(menuCache).evict(5L);
        Mockito.verifyNoInteractions(taskScheduler);
    }


    @Test
    void evictAfterCommit_whenEvictionFails_shouldNotThrowAfterTheCommit() {
        Mockito.doThrow(new IllegalStateException("cache broken")).when(menuCache).evict(5L);
        TransactionSynchronizationManager.initSynchronization();
        invalidator.evictMenuAfterCommit(5L);

        Assertions.assertDoesNotThrow(() ->
                TransactionSynchronizationUtils.invokeAfterCommit(TransactionSynchronizationManager.getSynchronizations()));
    }


    @SuppressWarnings("unchecked")
    private static ObjectProvider<TaskScheduler> providerOf(TaskScheduler scheduler) {
        ObjectProvider<TaskScheduler> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfUnique()).thenReturn(scheduler);
        return provider;
    }
}
