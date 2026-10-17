package com.laioffer.onlineorder.messaging.contract;


import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;


import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;


/**
 * Event contracts for tests that build services by hand: the application's own JSON mapper (Spring Boot's Jackson
 * auto-configuration with application.yml: snake_case, nulls omitted), so the events they produce and read are the
 * ones on the wire.
 */
public final class EventTestSupport {

    private static final JsonMapper MAPPER = springBootMapper();


    private EventTestSupport() {
    }


    public static JsonMapper mapper() {
        return MAPPER;
    }


    public static EventContracts contracts() {
        return contracts(new SimpleMeterRegistry());
    }


    public static EventContracts contracts(MeterRegistry meterRegistry) {
        return new EventContracts(
                MAPPER,
                EventSchemaRegistry.published(),
                new ApplicationMetricsService(meterRegistry),
                Map.of(
                        "order-events", EventTopic.ORDER_EVENTS,
                        "delivery-events", EventTopic.DELIVERY_EVENTS,
                        "merchant-events", EventTopic.MERCHANT_EVENTS
                )
        );
    }


    private static JsonMapper springBootMapper() {
        AtomicReference<JsonMapper> mapper = new AtomicReference<>();
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> mapper.set(context.getBean(JsonMapper.class)));
        return mapper.get();
    }
}
