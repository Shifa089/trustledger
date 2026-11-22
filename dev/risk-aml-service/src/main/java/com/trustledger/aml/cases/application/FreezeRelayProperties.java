package com.trustledger.aml.cases.application;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code trustledger.aml.freeze-relay.*}: how the outbox of freeze requests is drained (FR-AML-3, ADR 0007).
 * Startup fails on unsafe values.
 *
 * @param interval    pause between two relay runs; also the usual delay between a HIGH alert and its message
 * @param batchSize   requests sent per run at most
 * @param sendTimeout how long one send waits for the broker before the request is left for the next run
 */
@ConfigurationProperties("trustledger.aml.freeze-relay")
public record FreezeRelayProperties(Duration interval, int batchSize, Duration sendTimeout) {

    public FreezeRelayProperties {
        Objects.requireNonNull(interval, "trustledger.aml.freeze-relay.interval is required");
        Objects.requireNonNull(sendTimeout, "trustledger.aml.freeze-relay.send-timeout is required");
        if (interval.isNegative() || interval.isZero() || interval.compareTo(Duration.ofMinutes(1)) > 0) {
            // A freeze is urgent: a minute of delay is already long.
            throw new IllegalArgumentException("trustledger.aml.freeze-relay.interval must be in (0, 1m]");
        }
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("trustledger.aml.freeze-relay.batch-size must be 1..500");
        }
        if (sendTimeout.isNegative() || sendTimeout.isZero() || sendTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("trustledger.aml.freeze-relay.send-timeout must be in (0, 1m]");
        }
    }
}
