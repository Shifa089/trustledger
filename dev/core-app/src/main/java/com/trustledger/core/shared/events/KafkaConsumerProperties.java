package com.trustledger.core.shared.events;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code trustledger.events.consumer.*}: retries for core-app's Kafka consumers (FR-AML-3, SPEC §6 "Kafka": 3
 * attempts with exponential backoff, then {@code <topic>.DLT}). The same rules as risk-aml-service's
 * {@code trustledger.aml.kafka.retry} (ADR 0007). Startup fails on unsafe values.
 */
@ConfigurationProperties("trustledger.events.consumer")
public record KafkaConsumerProperties(Retry retry) {

    /** Suffix of the dead-letter topic: {@code <topic>.DLT}, Spring Kafka's convention. */
    public static final String DLT_SUFFIX = ".DLT";

    public KafkaConsumerProperties {
        Objects.requireNonNull(retry, "trustledger.events.consumer.retry is required");
    }

    /**
     * @param maxAttempts     attempts in total, the first one included
     * @param initialInterval wait before the first retry
     * @param multiplier      growth of the wait per retry
     * @param maxInterval     upper bound of one wait
     */
    public record Retry(int maxAttempts, Duration initialInterval, double multiplier, Duration maxInterval) {

        public Retry {
            if (maxAttempts < 1 || maxAttempts > 10) {
                throw new IllegalArgumentException("trustledger.events.consumer.retry.max-attempts must be 1..10");
            }
            Objects.requireNonNull(initialInterval, "trustledger.events.consumer.retry.initial-interval is required");
            Objects.requireNonNull(maxInterval, "trustledger.events.consumer.retry.max-interval is required");
            if (initialInterval.isNegative() || initialInterval.isZero()) {
                throw new IllegalArgumentException(
                        "trustledger.events.consumer.retry.initial-interval must be positive");
            }
            if (maxInterval.compareTo(initialInterval) < 0 || maxInterval.compareTo(Duration.ofMinutes(1)) > 0) {
                throw new IllegalArgumentException(
                        "trustledger.events.consumer.retry.max-interval must be between the initial interval and 1m");
            }
            if (multiplier < 1.0) {
                throw new IllegalArgumentException("trustledger.events.consumer.retry.multiplier must be at least 1");
            }
        }
    }
}
