package com.laioffer.onlineorder.push;


import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;


import java.util.ArrayList;
import java.util.List;


/**
 * {@link PushSignals#publish}: after the commit of the writer's transaction, nothing for a rollback, at once outside a
 * transaction, and never an exception for the writer.
 */
class PushSignalsTests {

    private static final PushRecipient RESTAURANT = PushRecipient.restaurant(2L);

    private final List<String> sent = new ArrayList<>();
    private SimpleMeterRegistry meters;
    private PushSignals signals;
    private TransactionTemplate transactions;


    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        signals = new PushSignals((recipient, topic) -> sent.add(recipient + " " + topic.wireName()),
                new PushMetrics(meters));
        transactions = new TransactionTemplate(new ResourcelessTransactionManager());
    }


    @Test
    void publishInATransaction_shouldSendOnlyAfterTheCommit() {
        transactions.executeWithoutResult(status -> {
            signals.publish(RESTAURANT, PushTopic.MERCHANT_ORDERS);
            signals.publish(PushRecipient.rider(4L), PushTopic.RIDER_DELIVERIES);
            Assertions.assertTrue(sent.isEmpty(), "nothing before the commit");
        });

        Assertions.assertEquals(List.of("restaurant:2 merchant.orders", "rider:4 rider.deliveries"), sent);
        Assertions.assertEquals(1.0, signalCount("merchant.orders"));
        Assertions.assertEquals(1.0, signalCount("rider.deliveries"));
    }


    @Test
    void publishInATransactionThatRollsBack_shouldSendNothing() {
        transactions.executeWithoutResult(status -> {
            signals.publish(RESTAURANT, PushTopic.MERCHANT_ORDERS);
            status.setRollbackOnly();
        });
        Assertions.assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            signals.publish(RESTAURANT, PushTopic.MERCHANT_AFTER_SALES);
            throw new IllegalStateException("the writer failed");
        }));

        Assertions.assertTrue(sent.isEmpty());
        Assertions.assertEquals(0.0, signalCount("merchant.orders"));
        Assertions.assertEquals(0.0, signalCount("merchant.after_sales"));
    }


    @Test
    void publishOutsideATransaction_shouldSendAtOnce() {
        signals.publish(PushRecipient.customer(3L), PushTopic.NOTIFICATIONS);

        Assertions.assertEquals(List.of("customer:3 notifications"), sent);
    }


    @Test
    void aFanoutThatThrows_shouldNeitherFailTheWriterNorItsCommit() {
        PushSignals failing = new PushSignals((recipient, topic) -> {
            throw new IllegalStateException("Redis is gone");
        }, new PushMetrics(meters));

        String result = Assertions.assertDoesNotThrow(() -> transactions.execute(status -> {
            failing.publish(RESTAURANT, PushTopic.MERCHANT_ORDERS);
            return "committed";
        }));

        Assertions.assertEquals("committed", result);
        Assertions.assertEquals(1.0, meters.get(PushMetrics.PUBLISH_FAILURES).counter().count());
    }


    private double signalCount(String topic) {
        return meters.get(PushMetrics.SIGNALS).tag("topic", topic).counter().count();
    }


    /**
     * A transaction manager without a resource: enough for Spring's transaction synchronization.
     */
    private static final class ResourcelessTransactionManager extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;


        @Override
        protected Object doGetTransaction() {
            return new Object();
        }


        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }


        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }


        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
