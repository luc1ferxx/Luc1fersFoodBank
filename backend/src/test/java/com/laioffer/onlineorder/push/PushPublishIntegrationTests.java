package com.laioffer.onlineorder.push;


import com.laioffer.onlineorder.fulfilment.FulfilmentEvents;
import com.laioffer.onlineorder.fulfilment.FulfilmentTestEvents;
import com.laioffer.onlineorder.fulfilment.dispatch.DispatchService;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantAfterSalesInboxService;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantInboxService;
import com.laioffer.onlineorder.messaging.AfterSalesCaseLinePayload;
import com.laioffer.onlineorder.messaging.AfterSalesCasePayload;
import com.laioffer.onlineorder.messaging.AfterSalesEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.service.CustomerService;
import com.laioffer.onlineorder.service.OrderNotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;


import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Every writer of a list a console shows publishes its push topic for the recipient of the row it changed, after its
 * transaction committed (ADR 0003 "Topics and recipients"): the merchant inbox consumer, the merchant after-sales
 * projection, dispatch (assignment, reassignment to both riders, pickup, delivery, cancellation) and the notification
 * inserts. A redelivered event that changes nothing and a rolled-back one signal nothing. The writers are called
 * directly, as their Kafka consumers call them; the streams are recorders registered on this instance (local fan-out).
 *
 * <p>Absence is checked without waiting: signals reach the streams in publish order on one delivery thread, so once a
 * later signal arrived, an earlier one would have arrived first.
 */
@SpringBootTest
@ActiveProfiles("h2")
class PushPublishIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final long RESTAURANT_ID = 1L;
    private static final AtomicLong NEXT_ORDER_ID = new AtomicLong(System.currentTimeMillis() * 10);
    private static final AtomicLong NEXT_USER_ID = new AtomicLong(9_000_000_000L);

    @Autowired
    private MerchantInboxService merchantInboxService;

    @Autowired
    private MerchantAfterSalesInboxService merchantAfterSalesInboxService;

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private OrderNotificationService orderNotificationService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private PushStreamRegistry registry;

    @Autowired
    private PushSignals pushSignals;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    @AfterEach
    void closeStreams() {
        registry.close();
        registry.open();
    }


    @Test
    void aPaidOrder_shouldSignalItsRestaurantsMerchantsOnce() {
        RecordingChannel merchant = openStream(PushRecipient.restaurant(RESTAURANT_ID));
        RecordingChannel otherRestaurant = openStream(PushRecipient.restaurant(2L));
        long orderId = NEXT_ORDER_ID.incrementAndGet();
        OrderEventEnvelope paid = FulfilmentTestEvents.orderEvent(
                FulfilmentEvents.ORDER_PAID, orderId, customerId(), RESTAURANT_ID, "PAID", null, LocalDateTime.now());

        merchantInboxService.handle(paid);
        merchant.awaitSignal(PushTopic.MERCHANT_ORDERS, WAIT);

        // A redelivery changes nothing; an accept whose transaction rolls back changes nothing either.
        merchantInboxService.handle(paid);
        transactionTemplate.executeWithoutResult(status -> {
            merchantInboxService.handle(FulfilmentTestEvents.orderEvent(FulfilmentEvents.ORDER_ACCEPTED, orderId,
                    customerId(), RESTAURANT_ID, "ACCEPTED", null, LocalDateTime.now()));
            status.setRollbackOnly();
        });
        merchantInboxService.handle(FulfilmentTestEvents.orderEvent(
                FulfilmentEvents.ORDER_CANCELLED, orderId, customerId(), RESTAURANT_ID, "CANCELLED", "TEST",
                LocalDateTime.now()));
        merchant.awaitSignal(PushTopic.MERCHANT_ORDERS, WAIT);

        sentinel(PushRecipient.restaurant(RESTAURANT_ID), merchant, PushTopic.NOTIFICATIONS);
        Assertions.assertEquals(2, merchant.signals(PushTopic.MERCHANT_ORDERS), "paid and cancelled, nothing else");
        sentinel(PushRecipient.restaurant(2L), otherRestaurant, PushTopic.NOTIFICATIONS);
        Assertions.assertEquals(0, otherRestaurant.signals(PushTopic.MERCHANT_ORDERS));
    }


    @Test
    void aCustomersRefundRequest_shouldSignalTheRestaurantsAfterSalesInbox() {
        RecordingChannel merchant = openStream(PushRecipient.restaurant(RESTAURANT_ID));
        long caseId = NEXT_ORDER_ID.incrementAndGet();
        AfterSalesEventEnvelope opened = afterSalesEvent(caseId, NEXT_ORDER_ID.incrementAndGet());

        merchantAfterSalesInboxService.handle(opened);
        merchant.awaitSignal(PushTopic.MERCHANT_AFTER_SALES, WAIT);

        merchantAfterSalesInboxService.handle(opened);
        sentinel(PushRecipient.restaurant(RESTAURANT_ID), merchant, PushTopic.NOTIFICATIONS);
        Assertions.assertEquals(1, merchant.signals(PushTopic.MERCHANT_AFTER_SALES), "a redelivery adds nothing");
    }


    @Test
    void aDeliverysChanges_shouldSignalItsRiderAndAReassignmentBothRiders() {
        Map<Long, RecordingChannel> riders = new HashMap<>();
        for (Long riderId : jdbcTemplate.queryForList("SELECT id FROM riders", Long.class)) {
            riders.put(riderId, openStream(PushRecipient.rider(riderId)));
        }
        long orderId = NEXT_ORDER_ID.incrementAndGet();

        dispatchService.handle(orderEvent(FulfilmentEvents.ORDER_ACCEPTED, orderId, "ACCEPTED", null));
        Map<String, Object> delivery = jdbcTemplate.queryForMap(
                "SELECT id, rider_id FROM deliveries WHERE order_id = ?", orderId);
        long deliveryId = ((Number) delivery.get("id")).longValue();
        long assigned = ((Number) delivery.get("rider_id")).longValue();
        riders.get(assigned).awaitSignal(PushTopic.RIDER_DELIVERIES, WAIT);

        long other = jdbcTemplate.queryForObject(
                "SELECT MIN(id) FROM riders WHERE active AND on_duty AND id <> ?", Long.class, assigned);
        dispatchService.reassign(deliveryId, other);
        riders.get(assigned).awaitSignal(PushTopic.RIDER_DELIVERIES, WAIT);
        riders.get(other).awaitSignal(PushTopic.RIDER_DELIVERIES, WAIT);

        dispatchService.markPickedUp(deliveryId);
        riders.get(other).awaitSignal(PushTopic.RIDER_DELIVERIES, WAIT);
        dispatchService.markPickedUp(deliveryId);
        dispatchService.markDelivered(deliveryId);
        riders.get(other).awaitSignal(PushTopic.RIDER_DELIVERIES, WAIT);

        sentinel(PushRecipient.rider(other), riders.get(other), PushTopic.NOTIFICATIONS);
        Assertions.assertEquals(3, riders.get(other).signals(PushTopic.RIDER_DELIVERIES),
                "reassigned, picked up, delivered; the repeated pickup changed nothing");
        sentinel(PushRecipient.rider(assigned), riders.get(assigned), PushTopic.NOTIFICATIONS);
        Assertions.assertEquals(2, riders.get(assigned).signals(PushTopic.RIDER_DELIVERIES), "assigned, reassigned");
    }


    @Test
    void aCancelledOrder_shouldSignalTheRiderOfItsOpenDelivery() {
        Map<Long, RecordingChannel> riders = new HashMap<>();
        for (Long riderId : jdbcTemplate.queryForList("SELECT id FROM riders", Long.class)) {
            riders.put(riderId, openStream(PushRecipient.rider(riderId)));
        }
        long orderId = NEXT_ORDER_ID.incrementAndGet();
        dispatchService.handle(orderEvent(FulfilmentEvents.ORDER_ACCEPTED, orderId, "ACCEPTED", null));
        long assigned = jdbcTemplate.queryForObject(
                "SELECT rider_id FROM deliveries WHERE order_id = ?", Long.class, orderId);
        riders.get(assigned).awaitSignal(PushTopic.RIDER_DELIVERIES, WAIT);

        dispatchService.handle(
                orderEvent(FulfilmentEvents.ORDER_CANCELLED, orderId, "CANCELLED", "CUSTOMER_CANCELLED"));

        riders.get(assigned).awaitSignal(PushTopic.RIDER_DELIVERIES, WAIT);
        Assertions.assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "SELECT status FROM deliveries WHERE order_id = ?", String.class, orderId));
    }


    @Test
    void aNewNotification_shouldSignalItsCustomerAndARedeliveredOneNothing() {
        long customerId = customerService.getCustomerByEmail("demo@laifood.com").id();
        RecordingChannel customer = openStream(PushRecipient.customer(customerId));
        long orderId = insertOrder(customerId);
        // The after-sales notices insert with a target-less ON CONFLICT, which H2 runs too.
        AfterSalesEventEnvelope opened = afterSalesEvent(NEXT_ORDER_ID.incrementAndGet(), orderId);

        transactionTemplate.executeWithoutResult(status -> orderNotificationService.recordAfterSalesEvent(opened));
        customer.awaitSignal(PushTopic.NOTIFICATIONS, WAIT);
        transactionTemplate.executeWithoutResult(status -> orderNotificationService.recordAfterSalesEvent(opened));

        sentinel(PushRecipient.customer(customerId), customer, PushTopic.RIDER_DELIVERIES);
        Assertions.assertEquals(1, customer.signals(PushTopic.NOTIFICATIONS), "the redelivery inserted nothing");
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_notifications WHERE order_id = ?", Integer.class, orderId));
    }


    private RecordingChannel openStream(PushRecipient recipient) {
        long userId = NEXT_USER_ID.incrementAndGet();
        return RecordingChannel.open(registry, userId, PushRecipient.customer(userId), recipient);
    }


    /**
     * Publishes {@code unrelated}, a topic the test does not count, to the recipient and waits for it: every signal
     * published before it has reached the stream by then.
     */
    private void sentinel(PushRecipient recipient, RecordingChannel channel, PushTopic unrelated) {
        pushSignals.publish(recipient, unrelated);
        channel.awaitSignal(unrelated, WAIT);
    }


    private long customerId() {
        return customerService.getCustomerByEmail("demo@laifood.com").id();
    }


    private OrderEventEnvelope orderEvent(String type, long orderId, String status, String reason) {
        return FulfilmentTestEvents.orderEvent(
                type, orderId, customerId(), RESTAURANT_ID, status, reason, LocalDateTime.now());
    }


    private long insertOrder(long customerId) {
        jdbcTemplate.update("""
                        INSERT INTO orders (customer_id, total_price, status, created_at, order_no)
                        VALUES (?, 5, 'PAID', ?, (SELECT COALESCE(MAX(order_no), 0) + 1 FROM orders))
                        """,
                customerId,
                Timestamp.valueOf(LocalDateTime.now())
        );
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM orders", Long.class);
    }


    private AfterSalesEventEnvelope afterSalesEvent(long caseId, long orderId) {
        LocalDateTime openedAt = LocalDateTime.now();
        AfterSalesCasePayload data = new AfterSalesCasePayload(
                caseId,
                orderId,
                orderId,
                customerId(),
                RESTAURANT_ID,
                "CUSTOMER_REQUEST",
                1,
                "PENDING_MERCHANT",
                "OPEN",
                "CUSTOMER",
                customerId(),
                0L,
                "MISSING_ITEM",
                "The fries were missing.",
                new BigDecimal("16.00"),
                null,
                null,
                null,
                "USD",
                null,
                null,
                null,
                null,
                openedAt.plusHours(24),
                List.of(new AfterSalesCaseLinePayload(
                        77L, "Whopper Meal", 2, new BigDecimal("8.00"), new BigDecimal("16.00"), "USD")),
                openedAt
        );
        return new AfterSalesEventEnvelope("push-" + caseId, 1, FulfilmentEvents.AFTER_SALES_CASE_OPENED,
                "AFTER_SALES_CASE", caseId, null, openedAt, "correlation", null, data);
    }
}
