package com.trustledger.aml.shared.kafka;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code trustledger.aml.kafka.*}: retries and topics for the consumers (M9, SPEC §6 "Kafka"). Startup fails on
 * unsafe values.
 *
 * @param retry  how often a failing record is tried before it goes to the dead-letter topic
 * @param topics topic creation; only local and test turn it on (in AWS topics come from infrastructure)
 */
@ConfigurationProperties("trustledger.aml.kafka")
public record AmlKafkaProperties(Retry retry, Topics topics) {

    /** Suffix of the dead-letter topic: {@code <topic>.DLT}, Spring Kafka's convention. */
    public static final String DLT_SUFFIX = ".DLT";

    public AmlKafkaProperties {
        Objects.requireNonNull(retry, "trustledger.aml.kafka.retry is required");
        if (topics == null) {
            topics = new Topics(false, 3, (short) 1);
        }
    }

    /**
     * Exponential backoff between attempts.
     *
     * @param maxAttempts     attempts in total, the first one included (3: one try and two retries)
     * @param initialInterval wait before the first retry
     * @param multiplier      growth of the wait per retry
     * @param maxInterval     upper bound of one wait
     */
    public record Retry(int maxAttempts, Duration initialInterval, double multiplier, Duration maxInterval) {

        public Retry {
            if (maxAttempts < 1 || maxAttempts > 10) {
                throw new IllegalArgumentException("trustledger.aml.kafka.retry.max-attempts must be 1..10");
            }
            Objects.requireNonNull(initialInterval, "trustledger.aml.kafka.retry.initial-interval is required");
            Objects.requireNonNull(maxInterval, "trustledger.aml.kafka.retry.max-interval is required");
            if (initialInterval.isNegative() || initialInterval.isZero()) {
                throw new IllegalArgumentException("trustledger.aml.kafka.retry.initial-interval must be positive");
            }
            if (maxInterval.compareTo(initialInterval) < 0) {
                throw new IllegalArgumentException(
                        "trustledger.aml.kafka.retry.max-interval must be at least the initial interval");
            }
            // A retry holds up its partition; a minute between tries is already far longer than a DB hiccup.
            if (maxInterval.compareTo(Duration.ofMinutes(1)) > 0) {
                throw new IllegalArgumentException("trustledger.aml.kafka.retry.max-interval must be at most 1 minute");
            }
            if (multiplier < 1.0) {
                throw new IllegalArgumentException("trustledger.aml.kafka.retry.multiplier must be at least 1");
            }
        }
    }

    /**
     * @param create     create the consumed topics and their dead-letter topics at startup ({@code NewTopic} beans)
     * @param partitions partitions per topic, the same as core-app's
     * @param replicas   replication factor
     */
    public record Topics(boolean create, int partitions, short replicas) {

        public Topics {
            if (partitions < 1) {
                throw new IllegalArgumentException("trustledger.aml.kafka.topics.partitions must be positive");
            }
            if (replicas < 1) {
                throw new IllegalArgumentException("trustledger.aml.kafka.topics.replicas must be positive");
            }
        }
    }
}
