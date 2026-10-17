package com.laioffer.onlineorder;


import com.laioffer.onlineorder.audit.AuditLogService;
import com.laioffer.onlineorder.cache.CatalogCacheInvalidator;
import com.laioffer.onlineorder.cache.CatalogCaches;
import com.laioffer.onlineorder.cache.TwoLevelCacheManager;
import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.entity.RestaurantEntity;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.model.RestaurantDto;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import com.laioffer.onlineorder.repository.RestaurantRepository;
import com.laioffer.onlineorder.service.CatalogService;
import com.laioffer.onlineorder.service.MenuItemService;
import com.laioffer.onlineorder.service.RestaurantService;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.ConversionService;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;


import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;


/**
 * Wires the real two-level cache configuration ({@link RedisConfig}, including the pub/sub listener container)
 * against a Redis port with nothing listening, so every Redis call is refused at once. A Redis that hangs
 * instead (every call waits for the command timeout) is covered by {@link CatalogRedisHangIntegrationTests}.
 */
@SpringJUnitConfig(RestaurantCacheFallbackTests.CacheFallbackTestConfig.class)
class RestaurantCacheFallbackTests {

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Autowired
    private MenuItemRepository menuItemRepository;

    @Autowired
    private TwoLevelCacheManager cacheManager;

    @Autowired
    private MeterRegistry meterRegistry;


    @BeforeEach
    void stubDatabase() {
        Mockito.reset(restaurantRepository, menuItemRepository);
        cacheManager.clearLocal();
        cacheManager.getTwoLevelCache(CatalogCaches.MENU).getRemoteCircuitBreaker().reset();
        Mockito.when(restaurantRepository.findAllOrderById()).thenReturn(List.of(
                new RestaurantEntity(1L, "Burger King", "773 N Mathilda Ave", "(408) 736-0101", "", "USD")
        ));
        Mockito.when(menuItemRepository.findByRestaurantIdOrderById(1L)).thenReturn(List.of(
                new MenuItemEntity(10L, 1L, "Whopper", "Flame grilled burger", new BigDecimal("6.39"), "", "USD")
        ));
    }


    @Test
    void getRestaurantsWithMenus_whenRedisIsUnavailable_shouldLoadFromDatabaseAndKeepServingFromL1() {
        double getErrorsBefore = cacheErrors(CatalogCaches.RESTAURANTS, "get") + cacheErrors(CatalogCaches.MENU, "get");
        double putErrorsBefore = cacheErrors(CatalogCaches.RESTAURANTS, "put") + cacheErrors(CatalogCaches.MENU, "put");

        List<RestaurantDto> first = catalogService.getRestaurantsWithMenus();
        List<RestaurantDto> second = catalogService.getRestaurantsWithMenus();

        Assertions.assertEquals(first, second);
        Assertions.assertEquals(1, first.size());
        Assertions.assertEquals("Burger King", first.get(0).name());
        Assertions.assertEquals("Whopper", first.get(0).menuItems().get(0).name());
        // Redis is down: the first call goes to the database, the second is served by the in-process L1.
        Mockito.verify(restaurantRepository, Mockito.times(1)).findAllOrderById();
        Mockito.verify(menuItemRepository, Mockito.times(1)).findByRestaurantIdOrderById(1L);
        // Only the first Redis read fails (recorded by the existing error handler). It opens the shared circuit
        // breaker, so the menu read and both write-backs skip Redis instead of failing one by one.
        Assertions.assertEquals(1.0, cacheErrors(CatalogCaches.RESTAURANTS, "get") + cacheErrors(CatalogCaches.MENU, "get") - getErrorsBefore);
        Assertions.assertEquals(0.0, cacheErrors(CatalogCaches.RESTAURANTS, "put") + cacheErrors(CatalogCaches.MENU, "put") - putErrorsBefore);
        Assertions.assertTrue(cacheManager.getTwoLevelCache(CatalogCaches.RESTAURANTS).getRemoteCircuitBreaker().isOpen());
    }


    @Test
    void getRestaurantsWithMenus_whenRedisIsUnavailableAndL1Expired_shouldFallBackToDatabaseAgain() {
        catalogService.getRestaurantsWithMenus();
        cacheManager.clearLocal();

        List<RestaurantDto> restaurants = catalogService.getRestaurantsWithMenus();

        Assertions.assertEquals("Whopper", restaurants.get(0).menuItems().get(0).name());
        Mockito.verify(restaurantRepository, Mockito.times(2)).findAllOrderById();
        Mockito.verify(menuItemRepository, Mockito.times(2)).findByRestaurantIdOrderById(1L);
    }


    @Test
    void getMenu_whenRedisIsUnavailable_shouldStillRejectUnknownRestaurantsWithoutQueryingMenus() {
        Assertions.assertEquals("Whopper", catalogService.getMenu(1L).get(0).name());
        Assertions.assertThrows(ResourceNotFoundException.class, () -> catalogService.getMenu(404L));

        Mockito.verify(menuItemRepository, Mockito.never()).findByRestaurantIdOrderById(404L);
        Mockito.verify(restaurantRepository, Mockito.times(1)).findAllOrderById();
        // One bounded primary-key check, in case the restaurant was created after the list was cached.
        Mockito.verify(restaurantRepository, Mockito.times(1)).existsById(404L);
    }


    private double cacheErrors(String cacheName, String operation) {
        return meterRegistry.counter("onlineorder.cache.errors", "cache", cacheName, "operation", operation).count();
    }


    @Configuration
    @EnableCaching
    @Import({
            CacheConfig.class,
            RedisConfig.class,
            CatalogCacheInvalidator.class,
            RestaurantService.class,
            MenuItemService.class,
            CatalogService.class
    })
    static class CacheFallbackTestConfig {

        /**
         * Lets {@code @Value} bind Boot-style durations such as {@code 30s} outside a Spring Boot context.
         */
        @Bean
        static ConversionService conversionService() {
            return ApplicationConversionService.getSharedInstance();
        }


        @Bean
        LettuceConnectionFactory redisConnectionFactory() {
            LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                    .commandTimeout(Duration.ofMillis(500))
                    .clientOptions(ClientOptions.builder()
                            .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(200)).build())
                            .build())
                    .build();
            return new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration("127.0.0.1", unusedPort()),
                    clientConfiguration
            );
        }


        @Bean
        StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory redisConnectionFactory) {
            return new StringRedisTemplate(redisConnectionFactory);
        }


        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }


        @Bean
        ApplicationMetricsService applicationMetricsService(MeterRegistry meterRegistry) {
            return new ApplicationMetricsService(meterRegistry);
        }


        @Bean
        RestaurantRepository restaurantRepository() {
            return Mockito.mock(RestaurantRepository.class);
        }


        @Bean
        MenuItemRepository menuItemRepository() {
            return Mockito.mock(MenuItemRepository.class);
        }


        /**
         * The catalog services record admin edits in the audit log; these tests only read.
         */
        @Bean
        AuditLogService auditLogService() {
            return Mockito.mock(AuditLogService.class);
        }


        private static int unusedPort() {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }
}
