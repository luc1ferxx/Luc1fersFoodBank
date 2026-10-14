package com.laioffer.onlineorder;


import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.entity.RestaurantEntity;
import com.laioffer.onlineorder.model.RestaurantDto;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import com.laioffer.onlineorder.repository.RestaurantRepository;
import com.laioffer.onlineorder.service.RestaurantService;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;


import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;


/**
 * Points a real Redis cache at a port with nothing listening, so cache reads and writes fail the
 * same way they do during a Redis outage.
 */
@SpringJUnitConfig(RestaurantCacheFallbackTests.CacheFallbackTestConfig.class)
class RestaurantCacheFallbackTests {

    @Autowired
    private RestaurantService restaurantService;

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Autowired
    private MenuItemRepository menuItemRepository;

    @Autowired
    private MeterRegistry meterRegistry;


    @Test
    void getRestaurants_whenRedisIsUnavailable_shouldFallBackToDatabaseAndRecordCacheErrors() {
        Mockito.when(restaurantRepository.findAll()).thenReturn(List.of(
                new RestaurantEntity(1L, "Burger King", "773 N Mathilda Ave", "(408) 736-0101", "")
        ));
        Mockito.when(menuItemRepository.findAll()).thenReturn(List.of(
                new MenuItemEntity(10L, 1L, "Whopper", "Flame grilled burger", new BigDecimal("6.39"), "")
        ));

        List<RestaurantDto> first = restaurantService.getRestaurants();
        List<RestaurantDto> second = restaurantService.getRestaurants();

        Assertions.assertEquals(first, second);
        Assertions.assertEquals(1, first.size());
        Assertions.assertEquals("Burger King", first.get(0).name());
        Assertions.assertEquals("Whopper", first.get(0).menuItems().get(0).name());
        Mockito.verify(restaurantRepository, Mockito.times(2)).findAll();
        Assertions.assertEquals(2.0, cacheErrors("get"));
        Assertions.assertEquals(2.0, cacheErrors("put"));
    }


    private double cacheErrors(String operation) {
        return meterRegistry.counter("onlineorder.cache.errors", "cache", "restaurants", "operation", operation).count();
    }


    @Configuration
    @EnableCaching
    @Import(CacheConfig.class)
    static class CacheFallbackTestConfig {

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
        RedisCacheManager cacheManager(LettuceConnectionFactory redisConnectionFactory) {
            RedisCacheConfiguration cacheConfiguration = RedisCacheConfiguration.defaultCacheConfig()
                    .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                            new GenericJackson2JsonRedisSerializer()
                    ));
            return RedisCacheManager.builder(redisConnectionFactory).cacheDefaults(cacheConfiguration).build();
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


        @Bean
        RestaurantService restaurantService(RestaurantRepository restaurantRepository, MenuItemRepository menuItemRepository) {
            return new RestaurantService(restaurantRepository, menuItemRepository);
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
