package com.laioffer.onlineorder.fulfilment.notification;


import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;


@SpringBootTest
@ActiveProfiles("h2")
class AfterSalesNotificationH2IntegrationTests extends AbstractAfterSalesNotificationIntegrationTests {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }
}
