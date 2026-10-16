package com.laioffer.onlineorder.observability;


import com.laioffer.onlineorder.messaging.OutboxEventPublisher;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;


/**
 * Tracing wiring that Spring Boot's auto-configuration does not cover on its own.
 */
@Configuration(proxyBeanMethods = false)
public class TracingConfig {

    static final String TEMPLATE_OBSERVATION_PROPERTY = "spring.kafka.template.observation-enabled";
    static final String LISTENER_OBSERVATION_PROPERTY = "spring.kafka.listener.observation-enabled";


    /**
     * Boot applies spring.kafka.template.observation-enabled and spring.kafka.listener.observation-enabled only to
     * the KafkaTemplate and listener container factory it creates itself. This applies the same settings to every
     * KafkaTemplate and listener container factory bean, including the order-event, dead-letter and any later
     * custom factories, so a consumer span always continues the producer's trace from the record's traceparent
     * header.
     */
    @Bean
    static BeanPostProcessor kafkaObservationBeanPostProcessor(Environment environment) {
        boolean templateObservation = environment.getProperty(TEMPLATE_OBSERVATION_PROPERTY, Boolean.class, false);
        boolean listenerObservation = environment.getProperty(LISTENER_OBSERVATION_PROPERTY, Boolean.class, false);
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (templateObservation && bean instanceof KafkaTemplate<?, ?> template) {
                    template.setObservationEnabled(true);
                }
                if (listenerObservation && bean instanceof AbstractKafkaListenerContainerFactory<?, ?, ?> factory) {
                    factory.getContainerProperties().setObservationEnabled(true);
                }
                return bean;
            }
        };
    }


    /**
     * The outbox publisher polls every app.outbox.publish-interval, and Spring observes each scheduled run. Those
     * runs would export a root span twice a second with nothing under it: every relayed event gets its own span
     * that continues the trace of the request that wrote it (see {@link OutboxTracing}). The run's metrics
     * (tasks.scheduled.execution) are kept; only the empty spans are not exported.
     */
    @Bean
    SpanExportingPredicate outboxPollerSpanFilter() {
        String publisherClass = OutboxEventPublisher.class.getName();
        return span -> !publisherClass.equals(span.getTags().get("code.namespace"));
    }
}
