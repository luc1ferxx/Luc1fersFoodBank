package com.laioffer.onlineorder.aftersales;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;


/**
 * The after-sales deadlines on H2 (the {@code h2} profile): the same claim (FOR UPDATE SKIP LOCKED), steps, refunds
 * and back-off as on PostgreSQL.
 */
@SpringBootTest(properties = {
        // Sessions live in the MockMvc session instead of Redis.
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class AfterSalesDeadlineH2IntegrationTests extends AbstractAfterSalesDeadlineIntegrationTests {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.session.store-type", () -> "none");
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.outbox.publisher-enabled", () -> "false");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }


    /**
     * app.after-sales.poller.enabled follows app.kafka.enabled (D13): off here, as in the plain h2 profile. The
     * processor stays a bean, for the tests and for the poller of a context that enables it.
     */
    @Test
    void poller_shouldFollowKafkaAndBeOffWithoutIt() {
        Assertions.assertEquals(0, applicationContext.getBeanNamesForType(AfterSalesPoller.class).length);
        Assertions.assertEquals(1, applicationContext.getBeanNamesForType(AfterSalesDeadlineProcessor.class).length);
    }
}
