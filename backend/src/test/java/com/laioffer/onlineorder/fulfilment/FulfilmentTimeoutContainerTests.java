package com.laioffer.onlineorder.fulfilment;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;


import java.time.Duration;
import java.util.Map;


/**
 * The timeout paths end to end on PostgreSQL, Redis and Kafka: pay -> no accept -> auto-cancelled
 * (MERCHANT_TIMEOUT), and an unpaid order cancelled by the payment timeout. Own class (and containers) because its
 * timeouts are short; the happy paths in {@link FulfilmentFlowContainerTests} run with a long accept timeout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class FulfilmentTimeoutContainerTests extends FulfilmentContainerTestSupport {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    @Container
    static PostgreSQLContainer POSTGRES = postgres();

    @Container
    static GenericContainer<?> REDIS = redis();

    @Container
    static KafkaContainer KAFKA = kafka();


    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registerInfrastructure(registry, POSTGRES, REDIS, KAFKA);
        registry.add("app.fulfilment.accept-timeout-enabled", () -> "true");
        registry.add("app.fulfilment.accept-timeout", () -> TIMEOUT.toSeconds() + "s");
        registry.add("app.fulfilment.payment-timeout", () -> TIMEOUT.toSeconds() + "s");
        registry.add("app.fulfilment.deadline-poller.interval", () -> "250ms");
    }


    @Test
    void ordersNobodyActsOn_shouldBeCancelledByTheAcceptAndPaymentTimeouts() {
        String customer = signUpAndLogin();
        String fashionWok = login("merchant.fashionwok@laifood.com", "merchant123");
        String admin = login("demo@laifood.com", "demo123");

        Long paidOrderId = payForItemOf(customer, "Fashion Wok", "e2e-timeout-" + System.nanoTime());
        addToCart(customer, menuItemOf("SGD Tofu House"));
        HttpHeaders checkoutHeaders = headers(customer);
        checkoutHeaders.set("Idempotency-Key", "e2e-placed-" + System.nanoTime());
        ResponseEntity<Map> placed = restTemplate.exchange(
                url("/cart/checkout"), HttpMethod.POST, new HttpEntity<>(null, checkoutHeaders), Map.class);
        Assertions.assertEquals("PLACED", placed.getBody().get("status"));
        Long placedOrderId = ((Number) placed.getBody().get("id")).longValue();

        // Present in the inbox, whatever its status by then.
        await("the paid order to reach the Fashion Wok inbox", EVENT_WAIT, () ->
                findById(getList("/merchant/orders", fashionWok), "order_id", paidOrderId) != null);

        Duration timeoutWait = TIMEOUT.plus(EVENT_WAIT);
        await("the accept timeout to cancel the paid order", timeoutWait, () ->
                "CANCELLED".equals(orderOf(customer, paidOrderId).get("status")));
        Assertions.assertEquals("MERCHANT_TIMEOUT", orderOf(customer, paidOrderId).get("cancel_reason"));
        await("the payment timeout to cancel the placed order", timeoutWait, () ->
                "CANCELLED".equals(orderOf(customer, placedOrderId).get("status")));
        Assertions.assertEquals("PAYMENT_TIMEOUT", orderOf(customer, placedOrderId).get("cancel_reason"));

        Map<?, ?> notification = awaitValue("the timeout notification", EVENT_WAIT, () -> {
            Map<?, ?> cancelled = notificationOf(customer, paidOrderId, "order.cancelled");
            return cancelled != null && "Order cancelled: the restaurant did not respond".equals(cancelled.get("title"))
                    ? cancelled
                    : null;
        });
        // Refunds are not automated: the notification must not promise one.
        Assertions.assertFalse(String.valueOf(notification.get("message")).contains("refunded"));
        await("the merchant inbox to show the cancelled order", EVENT_WAIT, () ->
                "CANCELLED".equals(inboxStatus(fashionWok, paidOrderId)));
        ResponseEntity<Map> lateAccept = post("/merchant/orders/" + paidOrderId + "/accept", fashionWok);
        Assertions.assertEquals(HttpStatus.CONFLICT, lateAccept.getStatusCode());
        Assertions.assertEquals("ORDER_STATUS_CONFLICT", lateAccept.getBody().get("code"));
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM deliveries WHERE order_id IN (?, ?)", Integer.class, paidOrderId, placedOrderId));
        Assertions.assertTrue(metricCount("onlineorder.fulfilment.merchant.decisions", "decision:timeout", admin) >= 1);
    }
}
