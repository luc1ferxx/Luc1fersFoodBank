package com.laioffer.onlineorder;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;


import java.time.Duration;


@Configuration
public class RedisConfig {


    @Bean
    RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer(
            @Value("${spring.cache.redis.time-to-live:10m}") Duration cacheTtl
    ) {
        RedisSerializationContext.SerializationPair<Object> valueSerializer =
                RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer());

        RedisCacheConfiguration cacheConfiguration = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(cacheTtl)
                .disableCachingNullValues()
                .serializeValuesWith(valueSerializer);

        return builder -> builder.cacheDefaults(cacheConfiguration);
    }
}
