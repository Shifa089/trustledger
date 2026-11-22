package com.trustledger.core.shared.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

class KafkaConsumerPropertiesTest {

    private static final Duration SECOND = Duration.ofSeconds(1);

    @Test
    void threeAttemptsMeanTwoRetriesWithExponentialWaits() {
        ExponentialBackOffWithMaxRetries backOff = KafkaConsumerConfig.backOff(
                new KafkaConsumerProperties.Retry(3, SECOND, 2.0, Duration.ofSeconds(10)));

        assertThat(backOff.getMaxRetries()).isEqualTo(2);
        assertThat(backOff.getInitialInterval()).isEqualTo(1000);
        assertThat(backOff.getMultiplier()).isEqualTo(2.0);
        assertThat(backOff.getMaxInterval()).isEqualTo(10_000);
    }

    @Test
    void unsafeValuesFailStartup() {
        assertThatThrownBy(() -> new KafkaConsumerProperties.Retry(0, SECOND, 2.0, SECOND))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KafkaConsumerProperties.Retry(11, SECOND, 2.0, SECOND))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KafkaConsumerProperties.Retry(3, Duration.ZERO, 2.0, SECOND))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KafkaConsumerProperties.Retry(3, SECOND, 2.0, Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KafkaConsumerProperties.Retry(3, SECOND, 2.0, Duration.ofMinutes(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KafkaConsumerProperties.Retry(3, SECOND, 0.5, SECOND))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KafkaConsumerProperties(null)).isInstanceOf(NullPointerException.class);
    }
}
