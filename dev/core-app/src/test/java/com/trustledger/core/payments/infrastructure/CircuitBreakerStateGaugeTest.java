package com.trustledger.core.payments.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CircuitBreakerStateGaugeTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CircuitBreaker breaker = CircuitBreaker.ofDefaults("razorpay");

    @Test
    void exactlyTheCurrentStateIsOneAsTheBreakerMoves() {
        CircuitBreakerStateGauge.register(meters, breaker);
        assertThat(meters.find(CircuitBreakerStateGauge.METRIC).gauges())
                .as("one series per state").hasSize(CircuitBreaker.State.values().length);
        assertOnlyCurrent(CircuitBreaker.State.CLOSED);

        breaker.transitionToOpenState();
        assertOnlyCurrent(CircuitBreaker.State.OPEN);

        breaker.transitionToHalfOpenState();
        assertOnlyCurrent(CircuitBreaker.State.HALF_OPEN);

        breaker.transitionToClosedState();
        assertOnlyCurrent(CircuitBreaker.State.CLOSED);
    }

    @Test
    void theBreakerNameIsATag() {
        CircuitBreakerStateGauge.register(meters, breaker);

        assertThat(meters.get(CircuitBreakerStateGauge.METRIC).tag("name", "razorpay").gauges()).isNotEmpty();
    }

    private void assertOnlyCurrent(CircuitBreaker.State current) {
        Arrays.stream(CircuitBreaker.State.values()).forEach(state -> {
            Gauge gauge = meters.get(CircuitBreakerStateGauge.METRIC).tag("state", state.name()).gauge();
            assertThat(gauge.value()).as(state.name()).isEqualTo(state == current ? 1.0 : 0.0);
        });
    }
}
