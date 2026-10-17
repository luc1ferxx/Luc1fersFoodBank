package com.laioffer.onlineorder.fulfilment;


import com.laioffer.onlineorder.datasource.PostgresReplicationContainers;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;


import java.util.List;
import java.util.Map;


/**
 * The whole application with read/write splitting on: PostgreSQL primary plus a streaming replica, Redis sessions,
 * Kafka. A paid order goes through the merchant, dispatch, the rider and delivery to COMPLETED, driven only through
 * the HTTP API, while the read-only endpoints (orders, notifications, the merchant inbox, the rider's and the admin's
 * delivery lists) read the replica. Commands, consumers, the outbox and the deadline poller write the primary.
 *
 * <p>Reads by another user than the writer are eventually consistent (bounded by the replica's lag), so they are
 * awaited; a customer's read right after their own payment goes to the primary (read-your-writes, through the session
 * in Redis) and must see the order at once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class FulfilmentReplicaRoutingContainerTests extends FulfilmentContainerTestSupport {

    private static final String READS = "onlineorder.db.routing.reads";

    @Container
    static PostgresReplicationContainers DATABASES = new PostgresReplicationContainers();

    @Container
    static GenericContainer<?> REDIS = redis();

    @Container
    static KafkaContainer KAFKA = kafka();


    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        DATABASES.register(registry);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registerRedisKafkaAndApp(registry, REDIS, KAFKA);
        registry.add("app.fulfilment.accept-timeout", () -> "10m");
        registry.add("app.datasource.replica.sticky-window", () -> "2s");
        registry.add("app.datasource.replica.max-lag", () -> "1s");
        registry.add("app.datasource.replica.lag-check-interval", () -> "200ms");
    }


    @Autowired
    private MeterRegistry meterRegistry;


    @BeforeEach
    void replicaIsRoutable() {
        await("the replica to be routable", EVENT_WAIT, () -> {
            Gauge routable = meterRegistry.find("onlineorder.db.replica.routable").tag("replica", "replica-1").gauge();
            return routable != null && routable.value() == 1.0;
        });
    }


    @Test
    void paidOrderReachesCompleted_withTheReadEndpointsOnTheReplica() throws InterruptedException {
        String customer = signUpAndLogin();
        String burgerKing = login("merchant.burgerking@laifood.com", "merchant123");
        String admin = login("demo@laifood.com", "demo123");
        // Past every login's read-your-writes window.
        Thread.sleep(2_100);
        double replicaReadsBefore = reads("replica-1", "replica");

        Long orderId = payForItemOf(customer, "Burger King", "replica-e2e-" + System.nanoTime());
        double stickyBefore = reads("primary", "sticky");
        Assertions.assertEquals("PAID", orderOf(customer, orderId).get("status"), "the payer sees the order at once");
        Assertions.assertTrue(reads("primary", "sticky") > stickyBefore, "that read used the primary");

        await("the order to reach the Burger King inbox", EVENT_WAIT, () ->
                findById(getList("/merchant/orders?status=PAID", burgerKing), "order_id", orderId) != null);
        ResponseEntity<Map> accepted = post("/merchant/orders/" + orderId + "/accept", burgerKing);
        Assertions.assertEquals(HttpStatus.OK, accepted.getStatusCode());
        Assertions.assertEquals("ACCEPTED", accepted.getBody().get("status"));
        Assertions.assertEquals("PREPARING",
                post("/merchant/orders/" + orderId + "/preparing", burgerKing).getBody().get("status"));

        Map<?, ?> delivery = awaitValue("a rider to be assigned", EVENT_WAIT, () -> {
            List<?> deliveries = getList("/admin/deliveries?order_id=" + orderId, admin);
            return deliveries.isEmpty() ? null : (Map<?, ?>) deliveries.get(0);
        });
        Long deliveryId = ((Number) delivery.get("id")).longValue();
        String rider = login(jdbcTemplate.queryForObject("""
                SELECT customers.email
                FROM riders
                JOIN customers ON customers.id = riders.customer_id
                WHERE riders.id = ?
                """, String.class, ((Number) delivery.get("rider_id")).longValue()), "rider123");
        await("the delivery in the rider's list", EVENT_WAIT, () ->
                findById(getList("/rider/deliveries", rider), "delivery_id", deliveryId) != null);
        Assertions.assertEquals("PICKED_UP",
                post("/rider/deliveries/" + deliveryId + "/picked-up", rider).getBody().get("status"));
        Assertions.assertEquals("DELIVERED",
                post("/rider/deliveries/" + deliveryId + "/delivered", rider).getBody().get("status"));

        await("the order to be completed", EVENT_WAIT, () -> "COMPLETED".equals(orderOf(customer, orderId).get("status")));
        await("the delivered and completed notifications", EVENT_WAIT, () ->
                hasNotification(customer, orderId, "delivery.delivered")
                        && hasNotification(customer, orderId, "order.completed"));
        await("the merchant inbox to show the completed order", EVENT_WAIT, () ->
                "COMPLETED".equals(inboxStatus(burgerKing, orderId)));

        Assertions.assertEquals(
                List.of("order.paid", "order.accepted", "order.preparing", "order.completed"),
                orderEventTypes(orderId)
        );
        Assertions.assertTrue(reads("replica-1", "replica") > replicaReadsBefore, "some reads above used the replica");

        // Once every participant's read-your-writes window has passed, each of them reads the finished order from
        // the replica, which has replayed it by then.
        Thread.sleep(2_100);
        double replicaReads = reads("replica-1", "replica");
        Assertions.assertEquals("COMPLETED", orderOf(customer, orderId).get("status"));
        Assertions.assertTrue(hasNotification(customer, orderId, "order.completed"));
        Assertions.assertEquals("COMPLETED", inboxStatus(burgerKing, orderId));
        Assertions.assertNotNull(findById(getList("/rider/deliveries?status=DELIVERED", rider), "delivery_id", deliveryId));
        Assertions.assertEquals("DELIVERED", ((Map<?, ?>) getList("/admin/deliveries?order_id=" + orderId, admin).get(0))
                .get("status"));
        // The rider's list also reads the restaurant names in a read-only transaction of their own.
        Assertions.assertTrue(reads("replica-1", "replica") - replicaReads >= 5,
                "replica reads: " + (reads("replica-1", "replica") - replicaReads));
        Assertions.assertEquals(0.0, reads("primary", "replica_failed"));
    }


    private double reads(String target, String reason) {
        Counter counter = meterRegistry.find(READS).tag("target", target).tag("reason", reason).counter();
        Assertions.assertNotNull(counter, target + "/" + reason);
        return counter.count();
    }
}
