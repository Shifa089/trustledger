package com.trustledger.core.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.payments.PaymentsFixtures.Customer;
import com.trustledger.core.payments.application.PaymentsMetrics;
import com.trustledger.core.payments.application.TopupResult;
import com.trustledger.core.payments.application.TopupService;
import com.trustledger.core.payments.application.TopupSettlementService;
import com.trustledger.core.payments.domain.GatewayPayment;
import com.trustledger.core.payments.domain.GatewayPaymentStatus;
import com.trustledger.core.payments.domain.GatewayUnavailableException;
import com.trustledger.core.payments.domain.PaymentGateway;
import com.trustledger.core.payments.domain.TopupStatus;
import com.trustledger.core.payments.infrastructure.GatewayCircuit;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.WalletTier;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * M12: {@code trustledger.topups{status}} and {@code trustledger.gateway.circuit.state} against the real services,
 * database and WireMock as Razorpay. Counters are shared by the whole context, so each test compares before and
 * after.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TopupMetricsIT {

    private static final String CIRCUIT_STATE = "trustledger.gateway.circuit.state";

    @Autowired
    TopupService topups;

    @Autowired
    TopupSettlementService settlement;

    @Autowired
    PaymentsFixtures fixtures;

    @Autowired
    WireMockServer razorpay;

    @Autowired
    PaymentGateway gateway;

    @Autowired
    MeterRegistry meters;

    private Customer customer;
    private Map<TopupStatus, Double> before;

    @BeforeEach
    void setUp() {
        GatewayCircuit.reset(gateway);
        customer = fixtures.customer(WalletTier.FULL, 0);
        before = new EnumMap<>(TopupStatus.class);
        for (TopupStatus status : TopupStatus.values()) {
            before.put(status, count(status));
        }
    }

    @AfterEach
    void reset() {
        razorpay.resetAll();
        GatewayCircuit.reset(gateway);
    }

    @Test
    void aCreatedOrderCountsAsPendingAndItsCaptureAsCaptured() {
        stubOrders(200);

        TopupResult pending = topups.create(customer.userId(), Money.parse("500.00"));
        assertThat(increase(TopupStatus.PENDING)).isEqualTo(1.0);

        String suffix = pending.gatewayOrderId().substring("order_".length());
        settlement.capture(new GatewayPayment("pay_" + suffix.substring(0, 14), pending.gatewayOrderId(),
                Money.parse("500.00"), GatewayPaymentStatus.CAPTURED, null));

        assertThat(increase(TopupStatus.CAPTURED)).isEqualTo(1.0);
        assertThat(increase(TopupStatus.FAILED)).isZero();
    }

    @Test
    void anOrderTheGatewayCannotCreateCountsAsFailed() {
        stubOrders(503);

        assertThatThrownBy(() -> topups.create(customer.userId(), Money.parse("100.00")))
                .isInstanceOf(GatewayUnavailableException.class);

        assertThat(increase(TopupStatus.FAILED)).isEqualTo(1.0);
        assertThat(increase(TopupStatus.PENDING)).isZero();
    }

    @Test
    void theCircuitStateGaugeFollowsTheGatewayBreaker() {
        assertThat(circuit(CircuitBreaker.State.CLOSED)).isEqualTo(1.0);
        assertThat(circuit(CircuitBreaker.State.OPEN)).isZero();

        stubOrders(503);
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> topups.create(customer.userId(), Money.parse("10.00")))
                    .isInstanceOf(GatewayUnavailableException.class);
        }

        assertThat(GatewayCircuit.state(gateway)).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuit(CircuitBreaker.State.OPEN)).isEqualTo(1.0);
        assertThat(circuit(CircuitBreaker.State.CLOSED)).isZero();
    }

    /** Every order request gets a fresh order ID (status 200), or fails with {@code status}. */
    private void stubOrders(int status) {
        if (status != 200) {
            razorpay.stubFor(WireMock.post("/v1/orders").willReturn(aResponse().withStatus(status)));
            return;
        }
        razorpay.stubFor(WireMock.post("/v1/orders").willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id": "order_{{randomValue length=20 type='ALPHANUMERIC'}}", "entity": "order",
                         "amount": {{jsonPath request.body '$.amount'}}, "currency": "INR",
                         "receipt": "{{jsonPath request.body '$.receipt'}}", "status": "created"}""")
                .withTransformers("response-template")));
    }

    private double count(TopupStatus status) {
        return meters.get(PaymentsMetrics.TOPUPS).tag("status", status.name()).counter().count();
    }

    private double increase(TopupStatus status) {
        return count(status) - before.get(status);
    }

    private double circuit(CircuitBreaker.State state) {
        return meters.get(CIRCUIT_STATE).tag("name", "razorpay").tag("state", state.name()).gauge().value();
    }
}
