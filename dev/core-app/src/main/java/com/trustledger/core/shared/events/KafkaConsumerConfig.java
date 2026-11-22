package com.trustledger.core.shared.events;

import io.micrometer.observation.ObservationRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/**
 * Retries and the dead-letter topic for core-app's Kafka listeners (FR-AML-3; the same pattern as risk-aml-service,
 * ADR 0007). Boot hands the {@link DefaultErrorHandler} bean to its listener container factory.
 * <ul>
 * <li><b>Transient failures</b> (database down, a lock timeout, an unknown wallet that may not be committed yet):
 * {@code max-attempts} in total with exponential backoff, then {@code <topic>.DLT}; the offset moves on.</li>
 * <li><b>Records that can't be read</b> (not JSON, a contract check fails, a type outside
 * {@code com.trustledger.events}): the {@code ErrorHandlingDeserializer} turns them into a
 * {@code DeserializationException}, never retried, straight to the DLT with the original bytes.</li>
 * </ul>
 * Knows no business module (Modularity test): only Kafka plumbing.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaConsumerProperties.class)
public class KafkaConsumerConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer deadLetterRecoverer,
            KafkaConsumerProperties properties) {
        DefaultErrorHandler handler = new DefaultErrorHandler(deadLetterRecoverer, backOff(properties.retry()));
        // A readable contract of the wrong type for its topic, or a contract with an impossible value (a producer
        // bug): retrying gives the same result.
        handler.addNotRetryableExceptions(ClassCastException.class, IllegalArgumentException.class);
        return handler;
    }

    static ExponentialBackOffWithMaxRetries backOff(KafkaConsumerProperties.Retry retry) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.maxAttempts() - 1);
        backOff.setInitialInterval(retry.initialInterval().toMillis());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        return backOff;
    }

    @Bean
    DeadLetterPublishingRecoverer deadLetterRecoverer(ProducerFactory<?, ?> bootProducerFactory,
            ObservationRegistry observationRegistry) {
        return new DeadLetterPublishingRecoverer(deadLetterTemplate(bootProducerFactory, observationRegistry),
                (record, exception) -> new TopicPartition(record.topic() + KafkaConsumerProperties.DLT_SUFFIX, -1));
    }

    /**
     * Producer for dead letters only. core-app's main producer writes byte arrays (Modulith's outbox converts the
     * contract to JSON first, ADR 0005); a dead letter is either the original bytes (unreadable record) or the
     * contract record read from it, written back as JSON without a new type header (the original one is copied).
     * Connection settings come from Boot's producer factory.
     * <p>
     * <b>Deliberately not a bean:</b> a {@code KafkaTemplate} bean would switch off Boot's auto-configured template,
     * and the outbox's Kafka externalization would then send through this JSON one (base64-encoding its bytes).
     * <p>
     * Observed (M12): {@code spring.kafka.template.observation-enabled} only reaches Boot's own template, and a
     * template that isn't a bean never looks up the registry itself. The dead letter is sent while the listener's
     * span is current, so its {@code traceparent} continues the failed record's trace.
     */
    static KafkaTemplate<String, Object> deadLetterTemplate(ProducerFactory<?, ?> bootProducerFactory,
            ObservationRegistry observationRegistry) {
        JacksonJsonSerializer<Object> json = new JacksonJsonSerializer<>();
        json.setAddTypeInfo(false);
        Map<Class<?>, Serializer<?>> byType = new LinkedHashMap<>();
        byType.put(byte[].class, new ByteArraySerializer());
        byType.put(Object.class, json);
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                bootProducerFactory.getConfigurationProperties(), new StringSerializer(),
                new DelegatingByTypeSerializer(byType, true)));
        template.setObservationEnabled(true);
        template.setObservationRegistry(observationRegistry);
        return template;
    }
}
