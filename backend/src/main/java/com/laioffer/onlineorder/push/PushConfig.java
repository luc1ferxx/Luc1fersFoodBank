package com.laioffer.onlineorder.push;


import com.laioffer.onlineorder.fulfilment.dispatch.RiderService;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantAccountService;
import com.laioffer.onlineorder.service.CustomerService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;


import java.time.Duration;


/**
 * Push signals (docs/adr/0003-push-signals.md): the stream registry and its housekeeping thread, the stream endpoint's
 * service, {@link PushSignals} for the writers, and the fan-out {@code app.push.fanout} picks. Only the redis fan-out
 * subscribes to Redis.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PushProperties.class)
public class PushConfig {

    /**
     * Backoff of the first subscription to the push channel while Redis is unreachable at startup.
     */
    private static final Duration SUBSCRIBE_RETRY_INITIAL_DELAY = Duration.ofSeconds(1);
    private static final Duration SUBSCRIBE_RETRY_MAX_DELAY = Duration.ofSeconds(30);


    @Bean
    PushMetrics pushMetrics(MeterRegistry meterRegistry) {
        return new PushMetrics(meterRegistry);
    }


    @Bean
    PushStreamRegistry pushStreamRegistry(PushProperties properties, PushMetrics metrics) {
        PushStreamRegistry registry = new PushStreamRegistry(
                properties.maxStreams(), properties.maxStreamsPerUser(), metrics);
        metrics.bindStreams(registry::size);
        return registry;
    }


    @Bean
    PushStreamHousekeeping pushStreamHousekeeping(PushStreamRegistry registry, PushProperties properties) {
        return new PushStreamHousekeeping(registry, properties.keepaliveInterval(), properties.streamTimeout());
    }


    @Bean
    PushStreamService pushStreamService(
            CustomerService customerService,
            MerchantAccountService merchantAccountService,
            RiderService riderService,
            PushStreamRegistry registry,
            PushProperties properties
    ) {
        return new PushStreamService(
                customerService, merchantAccountService, riderService, registry, properties.streamTimeout());
    }


    @Bean
    PushSignals pushSignals(PushFanout fanout, PushMetrics metrics) {
        return new PushSignals(fanout, metrics);
    }


    @Bean
    @ConditionalOnProperty(name = "app.push.fanout", havingValue = "local")
    LocalPushFanout localPushFanout(PushStreamRegistry registry, PushMetrics metrics) {
        return new LocalPushFanout(registry, metrics);
    }


    @Bean
    @ConditionalOnProperty(name = "app.push.fanout", havingValue = "redis", matchIfMissing = true)
    RedisPushFanout redisPushFanout(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            PushProperties properties,
            PushMetrics metrics
    ) {
        return new RedisPushFanout(redisTemplate, objectMapper, properties.channel(), metrics);
    }


    @Bean
    @ConditionalOnProperty(name = "app.push.fanout", havingValue = "redis", matchIfMissing = true)
    PushSubscription pushSubscription(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate redisTemplate,
            PushStreamRegistry registry,
            PushMetrics metrics,
            ObjectMapper objectMapper,
            PushProperties properties
    ) {
        return new PushSubscription(
                connectionFactory,
                redisTemplate,
                registry,
                metrics,
                objectMapper,
                properties.channel(),
                properties.probeInterval(),
                SUBSCRIBE_RETRY_INITIAL_DELAY,
                SUBSCRIBE_RETRY_MAX_DELAY
        );
    }
}
