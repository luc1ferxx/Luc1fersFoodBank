package com.laioffer.onlineorder;


import com.laioffer.onlineorder.messaging.contract.EventSchemaViolationException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;


import java.sql.SQLTransientException;
import java.time.Duration;
import java.util.Map;


@Configuration
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaListenerConfig {


    @Bean
    @SuppressWarnings("unchecked")
    ConcurrentKafkaListenerContainerFactory<String, String> orderEventKafkaListenerContainerFactory(
            ConsumerFactory<?, ?> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${app.kafka.order-dlt-topic}") String deadLetterTopic,
            @Value("${app.kafka.listener-retries:3}") long retries,
            @Value("${app.kafka.listener-backoff:1s}") Duration backOff,
            @Value("${app.kafka.listener-transient-backoff-max:30s}") Duration transientMaxBackOff,
            @Value("${app.kafka.listener-transient-retry-timeout:5m}") Duration transientRetryTimeout,
            @Value("${app.kafka.listener-concurrency:${app.kafka.partitions:3}}") int concurrency
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory((ConsumerFactory<String, String>) consumerFactory);
        factory.setConcurrency(concurrency);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception ex) -> new TopicPartition(deadLetterTopic, record.partition())
        );
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(backOff.toMillis(), retries)
        );
        errorHandler.setBackOffFunction((record, ex) -> isTransientDatabaseFailure(ex)
                ? transientDatabaseBackOff(backOff, transientMaxBackOff, transientRetryTimeout)
                : null);
        // A record that breaks its event contract (or is not JSON) fails the same way on every attempt: straight to the
        // DLT, with the violations in the exception message header. The classifier looks through the listener's
        // wrapping exceptions.
        errorHandler.addNotRetryableExceptions(EventSchemaViolationException.class);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }


    /**
     * Order events that fail because the database is briefly unavailable get a longer retry budget than
     * listener-retries before they are dead-lettered. The listener shares the Hikari pool with HTTP requests,
     * and its 2s connection-timeout makes every attempt fail fast while the pool is saturated, so a few fixed
     * retries would move events to the DLT after ~11s of overload. The backoff grows from listener-backoff to
     * transientMaxBackOff and stops once transientRetryTimeout has been spent waiting.
     */
    static BackOff transientDatabaseBackOff(Duration backOff, Duration transientMaxBackOff, Duration transientRetryTimeout) {
        long initialIntervalMillis = Math.max(1L, backOff.toMillis());
        ExponentialBackOff transientBackOff = new ExponentialBackOff(initialIntervalMillis, 2.0);
        transientBackOff.setMaxInterval(Math.max(initialIntervalMillis, transientMaxBackOff.toMillis()));
        transientBackOff.setMaxElapsedTime(transientRetryTimeout.toMillis());
        return transientBackOff;
    }


    /**
     * No pooled connection in time (pool saturated or database down), lock or statement timeouts, deadlocks
     * and lost connections. The listener wraps the failure, so the whole cause chain is checked.
     */
    static boolean isTransientDatabaseFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof CannotCreateTransactionException
                    || current instanceof TransientDataAccessException
                    || current instanceof DataAccessResourceFailureException
                    || current instanceof RecoverableDataAccessException
                    || current instanceof SQLTransientException) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }


    @Bean
    @SuppressWarnings("unchecked")
    ConcurrentKafkaListenerContainerFactory<String, String> deadLetterKafkaListenerContainerFactory(
            ConsumerFactory<?, ?> consumerFactory,
            @Value("${app.kafka.dlt-listener-backoff-initial:1s}") Duration initialBackOff,
            @Value("${app.kafka.dlt-listener-backoff-max:30s}") Duration maxBackOff,
            @Value("${app.kafka.listener-concurrency:${app.kafka.partitions:3}}") int concurrency
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory((ConsumerFactory<String, String>) consumerFactory);
        factory.setConcurrency(concurrency);
        factory.setCommonErrorHandler(deadLetterErrorHandler(initialBackOff, maxBackOff));
        return factory;
    }


    /**
     * The DLT listener is the last stop for failed order events, so it never recovers (skips) a record.
     * Every failure, including exceptions Spring Kafka classifies as non-retryable, is retried with a
     * capped exponential backoff until the dead letter is stored. A persistent failure such as a
     * database outage stalls the partition and shows up as consumer lag and ERROR logs instead of
     * silently dropping the record.
     */
    static DefaultErrorHandler deadLetterErrorHandler(Duration initialBackOff, Duration maxBackOff) {
        long initialIntervalMillis = Math.max(1L, initialBackOff.toMillis());
        ExponentialBackOff backOff = new ExponentialBackOff(initialIntervalMillis, 2.0);
        backOff.setMaxInterval(Math.max(initialIntervalMillis, maxBackOff.toMillis()));
        backOff.setMaxElapsedTime(Long.MAX_VALUE);
        backOff.setMaxAttempts(Integer.MAX_VALUE);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(backOff);
        errorHandler.setClassifications(Map.of(), true);
        return errorHandler;
    }
}
