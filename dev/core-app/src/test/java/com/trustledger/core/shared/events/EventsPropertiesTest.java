package com.trustledger.core.shared.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Startup fails on an outbox configuration that would delete too early or never run. */
class EventsPropertiesTest {

    private static final String CRON = "0 30 3 * * *";
    private static final Duration LOCK = Duration.ofMinutes(10);
    private static final EventsProperties.Topics TOPICS = new EventsProperties.Topics(true, 3, (short) 1);

    @Test
    void acceptsTheShippedValues() {
        var properties = new EventsProperties(Duration.ofDays(7), CRON, LOCK, TOPICS);

        assertThat(properties.retention()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.topics().partitions()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "-PT1H", "PT23H"})
    void rejectsARetentionBelowOneDay(String retention) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventsProperties(Duration.parse(retention), CRON, LOCK, TOPICS))
                .withMessageContaining("retention");
    }

    @Test
    void rejectsMissingRetentionInvalidCronAndNonPositiveLock() {
        assertThatIllegalArgumentException().isThrownBy(() -> new EventsProperties(null, CRON, LOCK, TOPICS));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventsProperties(Duration.ofDays(7), "daily", LOCK, TOPICS));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventsProperties(Duration.ofDays(7), CRON, Duration.ZERO, TOPICS));
    }

    @Test
    void topicsDefaultToNotCreatedWhenLeftOut() {
        var properties = new EventsProperties(Duration.ofDays(7), CRON, LOCK, null);

        assertThat(properties.topics().create()).isFalse();
    }

    @Test
    void rejectsZeroPartitionsOrReplicas() {
        assertThatIllegalArgumentException().isThrownBy(() -> new EventsProperties.Topics(true, 0, (short) 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new EventsProperties.Topics(true, 3, (short) 0));
    }
}
