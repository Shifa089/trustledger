package com.trustledger.core.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.BasicCredentials;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.payments.domain.GatewayOrder;
import com.trustledger.core.payments.domain.GatewayRejectedException;
import com.trustledger.core.payments.domain.PaymentGateway;
import com.trustledger.core.shared.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The application context wires the Razorpay adapter from {@code trustledger.razorpay.*}; in the test profile the
 * base URL is the shared WireMock server and the keys are the fake test keys. Resilience behaviour is covered by
 * {@code RazorpayGatewayAdapterTest}; this checks the wiring end to end. Only 4xx/2xx stubs here, so the shared
 * circuit breaker is never pushed towards open for other test classes.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentGatewayIT {

    @Autowired
    PaymentGateway gateway;

    @Autowired
    WireMockServer razorpay;

    @AfterEach
    void reset() {
        razorpay.resetAll();
    }

    @Test
    void createsAnOrderThroughTheConfiguredGateway() {
        razorpay.stubFor(post("/v1/orders").willReturn(okJson("""
                {"id": "order_IT0000000001", "entity": "order", "amount": 50000, "currency": "INR",
                 "receipt": "it-receipt", "status": "created"}""")));

        GatewayOrder order = gateway.createOrder(Money.of(50_000, Money.INR), "it-receipt");

        assertThat(order.orderId()).isEqualTo("order_IT0000000001");
        assertThat(order.amount()).isEqualTo(Money.of(50_000, Money.INR));
        razorpay.verify(postRequestedFor(urlEqualTo("/v1/orders"))
                .withBasicAuth(new BasicCredentials("rzp_test_FAKEKEY0000000", "fake-key-secret-for-tests"))
                .withRequestBody(equalToJson("""
                        {"amount": 50000, "currency": "INR", "receipt": "it-receipt"}""")));
    }

    @Test
    void gatewayRejectionSurfacesAsGatewayRejected() {
        razorpay.stubFor(get("/v1/payments/pay_UNKNOWN0001").willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": {\"code\": \"BAD_REQUEST_ERROR\"}}")));

        GatewayRejectedException e = catchThrowableOfType(GatewayRejectedException.class,
                () -> gateway.fetchPayment("pay_UNKNOWN0001"));

        assertThat(e.gatewayErrorCode()).isEqualTo("BAD_REQUEST_ERROR");
        assertThat(razorpay.getAllServeEvents()).hasSize(1);
    }
}
