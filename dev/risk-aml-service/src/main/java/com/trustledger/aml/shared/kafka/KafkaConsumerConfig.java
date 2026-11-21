package com.trustledger.aml.shared.kafka;

import com.trustledger.events.TopupCapturedV1;
import com.trustledger.events.TransferCompletedV1;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/**
 * Retries and the dead-letter topic for every listener (M9, SPEC §6 "Kafka"). Boot hands the
 * {@link DefaultErrorHandler} bean to its listener container factory.
 * <ul>
 * <li><b>Transient failures</b> (database down, a deadlock): tried {@code max-attempts} times in total with
 * exponential backoff, then published to {@code <topic>.DLT}, and the offset moves on, so one bad record can't
 * block its partition forever.</li>
 * <li><b>Records that can't be read</b> (not JSON, a contract check fails, a type outside
 * {@code com.trustledger.events}): the {@code ErrorHandlingDeserializer} turns them into a
 * {@code DeserializationException}, which the handler never retries. They go to the DLT on the first attempt:
 * retrying can't change the bytes.</li>
 * </ul>
 * The DLT record keeps the original key, value and headers; Spring adds {@code kafka_dlt-*} headers (original
 * topic, partition, offset, exception class and message). The partition is left to Kafka (by key), so a DLT
 * doesn't need as many partitions as its topic.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AmlKafkaProperties.class)
public class KafkaConsumerConfig {

    /** The topics this service consumes; each has a {@code .DLT}. */
    static final List<String> CONSUMED_TOPICS = List.of(TransferCompletedV1.TOPIC, TopupCapturedV1.TOPIC);

    @Bean
    DefaultErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer deadLetterRecoverer,
            AmlKafkaProperties properties) {
        DefaultErrorHandler handler = new DefaultErrorHandler(deadLetterRecoverer, backOff(properties.retry()));
        // A readable contract of the wrong type for its topic (a producer bug): retrying gives the same result.
        handler.addNotRetryableExceptions(ClassCastException.class);
        return handler;
    }

    static ExponentialBackOffWithMaxRetries backOff(AmlKafkaProperties.Retry retry) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.maxAttempts() - 1);
        backOff.setInitialInterval(retry.initialInterval().toMillis());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        return backOff;
    }

    @Bean
    DeadLetterPublishingRecoverer deadLetterRecoverer(KafkaTemplate<String, Object> deadLetterTemplate) {
        return new DeadLetterPublishingRecoverer(deadLetterTemplate,
                (record, exception) -> new TopicPartition(record.topic() + AmlKafkaProperties.DLT_SUFFIX, -1));
    }

    /**
     * Producer for dead letters only. A record that could not be read is republished as its original bytes; one
     * that was read but failed in processing is the contract record, written back as JSON. No type header is
     * added: the original {@code __TypeId__} header is copied over already.
     * <p>
     * The connection settings come from Boot's producer factory, not from {@code KafkaProperties}: only the factory
     * has the broker address from connection details (Testcontainers, a service binding), not just from
     * {@code spring.kafka.*}.
     */
    @Bean
    KafkaTemplate<String, Object> deadLetterTemplate(ProducerFactory<?, ?> bootProducerFactory) {
        JacksonJsonSerializer<Object> json = new JacksonJsonSerializer<>();
        json.setAddTypeInfo(false);
        Map<Class<?>, Serializer<?>> byType = new LinkedHashMap<>();
        byType.put(byte[].class, new ByteArraySerializer());
        byType.put(Object.class, json);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(bootProducerFactory.getConfigurationProperties(),
                new StringSerializer(), new DelegatingByTypeSerializer(byType, true)));
    }

    /**
     * Local and test only. The consumed topics belong to core-app, which creates them with the same settings
     * (creating an existing topic is a no-op); creating them here as well lets this service start first.
     */
    @Bean
    @ConditionalOnProperty(name = "trustledger.aml.kafka.topics.create", havingValue = "true")
    KafkaAdmin.NewTopics amlTopics(AmlKafkaProperties properties) {
        AmlKafkaProperties.Topics topics = properties.topics();
        List<NewTopic> all = new ArrayList<>();
        for (String topic : CONSUMED_TOPICS) {
            for (String name : List.of(topic, topic + AmlKafkaProperties.DLT_SUFFIX)) {
                all.add(TopicBuilder.name(name).partitions(topics.partitions()).replicas(topics.replicas()).build());
            }
        }
        return new KafkaAdmin.NewTopics(all.toArray(NewTopic[]::new));
    }
}
