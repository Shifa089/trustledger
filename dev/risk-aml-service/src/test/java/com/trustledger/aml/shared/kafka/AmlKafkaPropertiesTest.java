package com.trustledger.aml.shared.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOffExecution;

class AmlKafkaPropertiesTest {

    private static final AmlKafkaProperties.Retry PRODUCTION =
            new AmlKafkaProperties.Retry(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10));

    @Test
    void threeAttemptsMeanTwoRetriesWithGrowingWaits() {
        ExponentialBackOffWithMaxRetries backOff = KafkaConsumerConfig.backOff(PRODUCTION);
        BackOffExecution execution = backOff.start();

        assertThat(backOff.getMaxRetries()).isEqualTo(2);
        assertThat(execution.nextBackOff()).isEqualTo(1000);
        assertThat(execution.nextBackOff()).isEqualTo(2000);
        assertThat(execution.nextBackOff()).as("no third retry").isEqualTo(BackOffExecution.STOP);
    }

    @Test
    void topicsDefaultToNotCreated() {
        assertThat(new AmlKafkaProperties(PRODUCTION, null).topics().create()).isFalse();
    }

    @Test
    void rejectsUnsafeValues() {
        assertThatThrownBy(() -> new AmlKafkaProperties(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AmlKafkaProperties.Retry(0, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AmlKafkaProperties.Retry(3, Duration.ZERO, 2.0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AmlKafkaProperties.Retry(3, Duration.ofSeconds(5), 2.0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AmlKafkaProperties.Retry(3, Duration.ofSeconds(1), 2.0, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AmlKafkaProperties.Retry(3, Duration.ofSeconds(1), 0.5, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AmlKafkaProperties.Topics(true, 0, (short) 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
