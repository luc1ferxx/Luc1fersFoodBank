package com.laioffer.onlineorder.fulfilment;


import com.laioffer.onlineorder.push.SseTestClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;


import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * The event-driven fulfilment flow end to end on PostgreSQL, Redis and Kafka, driven only through the HTTP API:
 * pay -> merchant inbox -> accept -> rider assigned -> picked up -> delivered -> COMPLETED, once with the merchant
 * marking the order PREPARING and the assigned rider working the delivery through the rider API, and once without
 * either (completion then passes through PREPARING itself; the admin endpoints simulate the rider). The timeout paths
 * are in {@link FulfilmentTimeoutContainerTests}, which needs short timeouts this class must not have. The push signals
 * of the flow reach the merchant's, the customer's and the assigned rider's streams through Redis.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class FulfilmentFlowContainerTests extends FulfilmentContainerTestSupport {

    @Container
    static PostgreSQLContainer POSTGRES = postgres();

    @Container
    static GenericContainer<?> REDIS = redis();

    @Container
    static KafkaContainer KAFKA = kafka();


    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registerInfrastructure(registry, POSTGRES, REDIS, KAFKA);
        // The accept timeout runs (it is on by default), but far longer than any wait below: a slow runner must not let
        // it cancel an order before the test accepts it.
        registry.add("app.fulfilment.accept-timeout", () -> "10m");
    }


    @Autowired
    private ApplicationContext applicationContext;


    /**
     * Spring Boot backs off its own scheduler, the pool every {@code @Scheduled} job runs on, as soon as the context
     * holds any other TaskScheduler bean, and the cache's delayed second delete only runs on a unique one. The pollers
     * therefore run on their own threads. Checked here because this context has every bean of a production instance:
     * Kafka, the outbox publisher and the deadline poller are all on.
     */
    @Test
    void applicationContext_shouldHoldExactlyOneTaskScheduler() {
        Assertions.assertEquals(List.of("taskScheduler"), List.of(applicationContext.getBeanNamesForType(TaskScheduler.class)));
        ThreadPoolTaskScheduler scheduler = applicationContext.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
        Assertions.assertEquals("scheduling-", scheduler.getThreadNamePrefix());
    }


    @Test
    void paidOrder_shouldGoThroughMerchantRiderAndDeliveryToCompleted() {
        runPaidOrderToCompletion(true);
    }


    @Test
    void deliveredOrderTheMerchantNeverMarkedPreparing_shouldStillBeCompleted() {
        runPaidOrderToCompletion(false);
    }


    /**
     * ADR 0003 end to end: each writer publishes after its commit, the signal crosses the Redis channel, and the
     * browsers' streams (here real HTTP streams on the app's port, with Redis sessions) get it.
     */
    @Test
    void paidOrder_shouldSignalTheMerchantTheCustomerAndTheAssignedRiderOverTheirStreams() {
        String customer = signUpAndLogin();
        String burgerKing = login("merchant.burgerking@laifood.com", "merchant123");
        Map<Long, String> riderSessions = new LinkedHashMap<>();
        for (Map<String, Object> rider : jdbcTemplate.queryForList("""
                SELECT riders.id, customers.email
                FROM riders
                JOIN customers ON customers.id = riders.customer_id
                """)) {
            riderSessions.put(((Number) rider.get("id")).longValue(), login((String) rider.get("email"), "rider123"));
        }
        Map<Long, SseTestClient> riderStreams = new LinkedHashMap<>();
        try (SseTestClient customerStream = SseTestClient.open(url(""), customer);
             SseTestClient merchantStream = SseTestClient.open(url(""), burgerKing)) {
            riderSessions.forEach((riderId, session) ->
                    riderStreams.put(riderId, SseTestClient.open(url(""), session)));
            for (SseTestClient stream : List.of(customerStream, merchantStream)) {
                Assertions.assertEquals(200, stream.status());
                stream.awaitLine("retry: 5000", Duration.ofSeconds(10));
            }
            riderStreams.values().forEach(stream -> stream.awaitLine("retry: 5000", Duration.ofSeconds(10)));

            Long orderId = payForItemOf(customer, "Burger King", "e2e-push-" + System.nanoTime());

            // order.paid -> merchant inbox (merchant.orders) and "Payment confirmed" (notifications).
            merchantStream.awaitSignal("merchant.orders", EVENT_WAIT);
            customerStream.awaitSignal("notifications", EVENT_WAIT);
            Assertions.assertEquals(
                    HttpStatus.OK, post("/merchant/orders/" + orderId + "/accept", burgerKing).getStatusCode());
            merchantStream.awaitSignal("merchant.orders", EVENT_WAIT);

            // order.accepted -> dispatch assigns a rider (rider.deliveries) -> "Rider assigned" (notifications).
            Long riderId = awaitValue("a rider to be assigned", EVENT_WAIT, () -> jdbcTemplate.query(
                    "SELECT rider_id FROM deliveries WHERE order_id = ?",
                    rs -> rs.next() ? rs.getLong(1) : null,
                    orderId));
            riderStreams.get(riderId).awaitSignal("rider.deliveries", EVENT_WAIT);
            customerStream.awaitSignal("notifications", EVENT_WAIT);
        } finally {
            riderStreams.values().forEach(SseTestClient::close);
        }
    }


    private void runPaidOrderToCompletion(boolean merchantMarksPreparing) {
        String customer = signUpAndLogin();
        String burgerKing = login("merchant.burgerking@laifood.com", "merchant123");
        String sgdTofu = login("merchant.sgdtofu@laifood.com", "merchant123");
        String admin = login("demo@laifood.com", "demo123");

        Long orderId = payForItemOf(customer, "Burger King", "e2e-happy-" + System.nanoTime());

        // order.paid -> merchant inbox (merchant consumer group) -> merchant.order_received (Burger King is staffed)
        // -> an ACCEPT deadline (timeout-merchant consumer group).
        await("the order to reach the Burger King inbox", EVENT_WAIT, () ->
                findById(getList("/merchant/orders?status=PAID", burgerKing), "order_id", orderId) != null);
        await("the accept deadline", EVENT_WAIT, () -> jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_deadlines WHERE order_id = ? AND deadline_type = 'ACCEPT'",
                Integer.class,
                orderId
        ) == 1);
        Assertions.assertNull(findById(getList("/merchant/orders", sgdTofu), "order_id", orderId));
        Assertions.assertEquals(HttpStatus.NOT_FOUND, post("/merchant/orders/" + orderId + "/accept", sgdTofu).getStatusCode());
        Assertions.assertEquals(HttpStatus.FORBIDDEN, post("/merchant/orders/" + orderId + "/accept", customer).getStatusCode());

        ResponseEntity<Map> accepted = post("/merchant/orders/" + orderId + "/accept", burgerKing);
        Assertions.assertEquals(HttpStatus.OK, accepted.getStatusCode());
        Assertions.assertEquals("ACCEPTED", accepted.getBody().get("status"));
        if (merchantMarksPreparing) {
            ResponseEntity<Map> preparing = post("/merchant/orders/" + orderId + "/preparing", burgerKing);
            Assertions.assertEquals("PREPARING", preparing.getBody().get("status"));
        }

        // order.accepted -> rider assigned (dispatch consumer group) -> delivery.assigned notification.
        Map<?, ?> delivery = awaitValue("a rider to be assigned", EVENT_WAIT, () -> {
            List<?> deliveries = getList("/admin/deliveries?order_id=" + orderId, admin);
            return deliveries.isEmpty() ? null : (Map<?, ?>) deliveries.get(0);
        });
        Assertions.assertEquals("ASSIGNED", delivery.get("status"));
        Assertions.assertNotNull(delivery.get("rider_name"));
        Long deliveryId = ((Number) delivery.get("id")).longValue();
        await("the rider-assigned notification", EVENT_WAIT, () -> hasNotification(customer, orderId, "delivery.assigned"));
        await("the accept deadline to be dropped", EVENT_WAIT, () -> jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_deadlines WHERE order_id = ?", Integer.class, orderId) == 0);

        Assertions.assertEquals(HttpStatus.FORBIDDEN,
                post("/admin/deliveries/" + deliveryId + "/picked-up", customer).getStatusCode());
        // The demo riders have logins: with the merchant path, the assigned rider works the delivery through the rider
        // API; otherwise the admin endpoints simulate the rider.
        String rider = login(jdbcTemplate.queryForObject("""
                SELECT customers.email
                FROM riders
                JOIN customers ON customers.id = riders.customer_id
                WHERE riders.id = ?
                """, String.class, ((Number) delivery.get("rider_id")).longValue()), "rider123");
        Map<?, ?> riderView = findById(getList("/rider/deliveries", rider), "delivery_id", deliveryId);
        Assertions.assertNotNull(riderView, "the delivery in the assigned rider's list");
        Assertions.assertEquals("Burger King", riderView.get("restaurant_name"));
        Assertions.assertEquals(HttpStatus.FORBIDDEN,
                post("/rider/deliveries/" + deliveryId + "/picked-up", customer).getStatusCode());
        String actions = merchantMarksPreparing ? "/rider/deliveries/" : "/admin/deliveries/";
        String actor = merchantMarksPreparing ? rider : admin;
        Assertions.assertEquals("PICKED_UP",
                post(actions + deliveryId + "/picked-up", actor).getBody().get("status"));
        Assertions.assertEquals(merchantMarksPreparing ? "PREPARING" : "ACCEPTED", orderOf(customer, orderId).get("status"));
        Assertions.assertEquals("DELIVERED",
                post(actions + deliveryId + "/delivered", actor).getBody().get("status"));

        // delivery.delivered -> order COMPLETED (order-delivery consumer group) -> notifications. Without the
        // merchant's PREPARING, completion moves ACCEPTED -> PREPARING -> COMPLETED in one transaction.
        await("the order to be completed", EVENT_WAIT, () -> "COMPLETED".equals(orderOf(customer, orderId).get("status")));
        await("the delivered and completed notifications", EVENT_WAIT, () ->
                hasNotification(customer, orderId, "delivery.delivered")
                        && hasNotification(customer, orderId, "order.completed")
                        && hasNotification(customer, orderId, "order.preparing")
                        && hasNotification(customer, orderId, "order.accepted"));
        await("the merchant inbox to show the completed order", EVENT_WAIT, () ->
                "COMPLETED".equals(inboxStatus(burgerKing, orderId)));

        Assertions.assertEquals(
                List.of("order.paid", "order.accepted", "order.preparing", "order.completed"),
                orderEventTypes(orderId)
        );
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_deadlines WHERE order_id = ?", Integer.class, orderId));
        Assertions.assertTrue(metricCount("onlineorder.fulfilment.merchant.decisions", "decision:accepted", admin) >= 1);
        Assertions.assertTrue(metricCount("onlineorder.fulfilment.dispatch.assignments", "result:assigned", admin) >= 1);
    }
}
