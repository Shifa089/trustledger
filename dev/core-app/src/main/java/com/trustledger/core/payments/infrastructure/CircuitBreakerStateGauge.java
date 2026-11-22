package com.trustledger.core.payments.infrastructure;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@code trustledger.gateway.circuit.state{name,state}} (M12; Prometheus {@code trustledger_gateway_circuit_state}):
 * one gauge per circuit-breaker state, 1 for the current state and 0 for every other one. The same shape as
 * resilience4j-micrometer's {@code resilience4j_circuitbreaker_state}, written by hand so no extra dependency is
 * needed (ADR 0020). A dashboard shows the current state with {@code max by (state) (...) == 1}.
 */
final class CircuitBreakerStateGauge {

    static final String METRIC = "trustledger.gateway.circuit.state";

    private CircuitBreakerStateGauge() {
    }

    static void register(MeterRegistry meters, CircuitBreaker circuitBreaker) {
        for (CircuitBreaker.State state : CircuitBreaker.State.values()) {
            Gauge.builder(METRIC, circuitBreaker, breaker -> breaker.getState() == state ? 1 : 0)
                    .description("Payment gateway circuit breaker: 1 for the current state, 0 for the others")
                    .tag("name", circuitBreaker.getName())
                    .tag("state", state.name())
                    .strongReference(true)
                    .register(meters);
        }
    }
}
