package com.trustledger.core.payments.infrastructure;

import com.trustledger.core.payments.domain.PaymentGateway;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/** Test access to the shared adapter's circuit breaker, which is package-private in production code. */
public final class GatewayCircuit {

    private GatewayCircuit() {
    }

    public static CircuitBreaker.State state(PaymentGateway gateway) {
        return ((RazorpayGatewayAdapter) gateway).circuitState();
    }

    /** Integration tests share one adapter: a test that opens the circuit must close it again afterwards. */
    public static void reset(PaymentGateway gateway) {
        ((RazorpayGatewayAdapter) gateway).resetCircuit();
    }
}
