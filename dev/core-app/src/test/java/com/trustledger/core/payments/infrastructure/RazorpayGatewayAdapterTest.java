package com.trustledger.core.payments.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;

import com.github.tomakehurst.wiremock.client.BasicCredentials;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.trustledger.core.payments.domain.GatewayOrder;
import com.trustledger.core.payments.domain.GatewayPayment;
import com.trustledger.core.payments.domain.GatewayPaymentStatus;
import com.trustledger.core.payments.domain.GatewayRejectedException;
import com.trustledger.core.payments.domain.GatewayUnavailableException;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The Razorpay adapter against a WireMock Razorpay: wire format, error mapping, timeout, retry and circuit
 * breaker. No Spring context; timings are shortened (300 ms limit, 500 ms open state) to keep the suite fast,
 * the production values are covered by {@link RazorpayPropertiesTest}.
 */
class RazorpayGatewayAdapterTest {

    @RegisterExtension
    static WireMockExtension razorpay = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort())
            .build();

    static final String KEY_ID = "rzp_test_FAKEKEY0000000";
    static final String KEY_SECRET = "fake-secret-never-shown";
    /**
     * Generous on purpose: the first request in a fresh JVM (class loading, Jackson) can take longer than 300 ms,
     * which made a 401 test time out once. Only the timeout tests use {@link #SHORT_TIMEOUT}.
     */
    static final Duration TIMEOUT = Duration.ofSeconds(2);
    static final Duration SHORT_TIMEOUT = Duration.ofMillis(300);
    static final Duration OPEN_STATE = Duration.ofMillis(500);

    static final String ORDER_ID = "order_EKwxwAgItmmXdp";
    static final String PAYMENT_ID = "pay_29QQoUBi66xm2f";

    static final String ORDER_JSON = """
            {"id": "order_EKwxwAgItmmXdp", "entity": "order", "amount": 150050, "amount_paid": 0,
             "amount_due": 150050, "currency": "INR", "receipt": "topup-1", "status": "created",
             "attempts": 0, "notes": [], "created_at": 1582628071}""";

    static String paymentJson(String id, String status, String errorCode) {
        return """
                {"id": "%s", "entity": "payment", "amount": 150050, "currency": "INR", "status": "%s",
                 "order_id": "order_EKwxwAgItmmXdp", "method": "upi", "captured": true,
                 "error_code": %s, "created_at": 1582628100}"""
                .formatted(id, status, errorCode == null ? "null" : "\"" + errorCode + "\"");
    }

    static final String BAD_REQUEST_JSON = """
            {"error": {"code": "BAD_REQUEST_ERROR", "description": "The id provided does not exist",
             "source": "business", "step": "payment_initiation", "reason": "input_validation_failed"}}""";

    RazorpayGatewayAdapter adapter = adapter(3);

    @AfterEach
    void close() {
        adapter.close();
    }

    static RazorpayGatewayAdapter adapter(int maxAttempts) {
        return adapter(maxAttempts, TIMEOUT);
    }

    static RazorpayGatewayAdapter adapter(int maxAttempts, Duration timeout) {
        return RazorpayGatewayAdapter.create(new RazorpayProperties(URI.create(razorpay.baseUrl()), KEY_ID,
                KEY_SECRET, "fake-webhook-secret", Duration.ofMillis(500), timeout,
                new RazorpayProperties.CircuitBreaker(10, 50, 10, OPEN_STATE, 3),
                new RazorpayProperties.Retry(maxAttempts, Duration.ofMillis(10), 2.0)));
    }

    static int requests() {
        return razorpay.findAll(anyRequestedFor(anyUrl())).size();
    }

    @Nested
    class WireFormat {

        @Test
        void checkoutKeyIdIsTheConfiguredTestKeyAndSendsNoRequest() {
            assertThat(adapter.checkoutKeyId()).isEqualTo(KEY_ID).startsWith("rzp_test_");
            assertThat(requests()).isZero();
        }

        @Test
        void createOrderPostsPaiseWithBasicAuthAndMapsTheOrder() {
            razorpay.stubFor(post("/v1/orders").willReturn(okJson(ORDER_JSON)));

            GatewayOrder order = adapter.createOrder(Money.of(150050, Money.INR), "topup-1");

            assertThat(order).isEqualTo(new GatewayOrder(ORDER_ID, Money.of(150050, Money.INR), "topup-1", "created"));
            // Integer paise, exactly these three fields: never "1500.5" or a float.
            razorpay.verify(postRequestedFor(urlEqualTo("/v1/orders"))
                    .withBasicAuth(new BasicCredentials(KEY_ID, KEY_SECRET))
                    .withHeader("Content-Type", equalTo("application/json"))
                    .withRequestBody(equalToJson("""
                            {"amount": 150050, "currency": "INR", "receipt": "topup-1"}""")));
        }

        @Test
        void fetchPaymentMapsCapturedPayment() {
            razorpay.stubFor(get("/v1/payments/" + PAYMENT_ID).willReturn(okJson(paymentJson(PAYMENT_ID, "captured", null))));

            GatewayPayment payment = adapter.fetchPayment(PAYMENT_ID);

            assertThat(payment).isEqualTo(new GatewayPayment(PAYMENT_ID, ORDER_ID, Money.of(150050, Money.INR),
                    GatewayPaymentStatus.CAPTURED, null));
            razorpay.verify(getRequestedFor(urlEqualTo("/v1/payments/" + PAYMENT_ID))
                    .withBasicAuth(new BasicCredentials(KEY_ID, KEY_SECRET)));
        }

        @Test
        void failedPaymentKeepsTheGatewayErrorCode() {
            razorpay.stubFor(get("/v1/payments/" + PAYMENT_ID)
                    .willReturn(okJson(paymentJson(PAYMENT_ID, "failed", "BAD_REQUEST_ERROR"))));

            GatewayPayment payment = adapter.fetchPayment(PAYMENT_ID);

            assertThat(payment.status()).isEqualTo(GatewayPaymentStatus.FAILED);
            assertThat(payment.errorCode()).isEqualTo("BAD_REQUEST_ERROR");
        }

        @Test
        void unknownStatusMapsToUnknownInsteadOfFailing() {
            razorpay.stubFor(get("/v1/payments/" + PAYMENT_ID)
                    .willReturn(okJson(paymentJson(PAYMENT_ID, "pending_review", null))));

            assertThat(adapter.fetchPayment(PAYMENT_ID).status()).isEqualTo(GatewayPaymentStatus.UNKNOWN);
        }

        @Test
        void fetchOrderPaymentsMapsEveryAttempt() {
            razorpay.stubFor(get("/v1/orders/" + ORDER_ID + "/payments").willReturn(okJson("""
                    {"entity": "collection", "count": 2, "items": [%s, %s]}"""
                    .formatted(paymentJson("pay_FAILED001", "failed", "BAD_REQUEST_ERROR"),
                            paymentJson(PAYMENT_ID, "captured", null)))));

            List<GatewayPayment> payments = adapter.fetchOrderPayments(ORDER_ID);

            assertThat(payments).extracting(GatewayPayment::paymentId, GatewayPayment::status).containsExactly(
                    tuple("pay_FAILED001", GatewayPaymentStatus.FAILED),
                    tuple(PAYMENT_ID, GatewayPaymentStatus.CAPTURED));
        }

        @Test
        void orderWithoutPaymentsGivesAnEmptyList() {
            razorpay.stubFor(get("/v1/orders/" + ORDER_ID + "/payments")
                    .willReturn(okJson("{\"entity\": \"collection\", \"count\": 0, \"items\": []}")));

            assertThat(adapter.fetchOrderPayments(ORDER_ID)).isEmpty();
        }
    }

    @Nested
    class InputChecks {

        @Test
        void malformedIdsAreRefusedBeforeAnyRequest() {
            assertThatIllegalArgumentException().isThrownBy(() -> adapter.fetchPayment("../orders"));
            assertThatIllegalArgumentException().isThrownBy(() -> adapter.fetchPayment("order_EKwxwAgItmmXdp"));
            assertThatIllegalArgumentException().isThrownBy(() -> adapter.fetchPayment(null));
            assertThatIllegalArgumentException().isThrownBy(() -> adapter.fetchOrderPayments("order_x/../../v1"));
            assertThat(requests()).isZero();
        }

        @Test
        void orderNeedsAPositiveAmountAndAShortReceipt() {
            assertThatIllegalArgumentException().isThrownBy(() -> adapter.createOrder(Money.zero(Money.INR), "r"));
            assertThatIllegalArgumentException().isThrownBy(
                    () -> adapter.createOrder(Money.of(100, Money.INR), "x".repeat(41)));
            assertThatIllegalArgumentException().isThrownBy(() -> adapter.createOrder(Money.of(100, Money.INR), " "));
            assertThat(requests()).isZero();
        }
    }

    @Nested
    class Rejections {

        @Test
        void badRequestIsRejectedOnceWithTheGatewayCode() {
            razorpay.stubFor(get("/v1/payments/" + PAYMENT_ID)
                    .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                            .withBody(BAD_REQUEST_JSON)));

            GatewayRejectedException e = catchThrowableOfType(GatewayRejectedException.class,
                    () -> adapter.fetchPayment(PAYMENT_ID));

            assertThat(e.errorCode()).isEqualTo(ErrorCode.GATEWAY_REJECTED);
            assertThat(e.httpStatus()).isEqualTo(400);
            assertThat(e.gatewayErrorCode()).isEqualTo("BAD_REQUEST_ERROR");
            assertThat(e.detail()).doesNotContain("does not exist"); // gateway text stays out of the client response
            assertThat(requests()).as("a 4xx is never retried").isEqualTo(1);
        }

        @Test
        void wrongKeysAreRejectedWithoutLeakingTheSecret() {
            razorpay.stubFor(post("/v1/orders").willReturn(aResponse().withStatus(401)
                    .withBody("{\"error\": {\"code\": \"BAD_REQUEST_ERROR\", \"description\": \"Authentication failed\"}}")));

            GatewayRejectedException e = catchThrowableOfType(GatewayRejectedException.class,
                    () -> adapter.createOrder(Money.of(100, Money.INR), "topup-2"));

            assertThat(e.httpStatus()).isEqualTo(401);
            assertThat(e).hasMessageNotContaining(KEY_SECRET);
        }

        @Test
        void rejectionWithoutAJsonBodyStillMapsToRejected() {
            razorpay.stubFor(get("/v1/payments/" + PAYMENT_ID).willReturn(aResponse().withStatus(404).withBody("<html>")));

            GatewayRejectedException e = catchThrowableOfType(GatewayRejectedException.class,
                    () -> adapter.fetchPayment(PAYMENT_ID));

            assertThat(e.httpStatus()).isEqualTo(404);
            assertThat(e.gatewayErrorCode()).isNull();
        }

        @Test
        void rejectionsDoNotOpenTheCircuit() {
            razorpay.stubFor(get("/v1/payments/" + PAYMENT_ID).willReturn(aResponse().withStatus(400).withBody(BAD_REQUEST_JSON)));

            for (int i = 0; i < 12; i++) {
                catchThrowableOfType(GatewayRejectedException.class, () -> adapter.fetchPayment(PAYMENT_ID));
            }

            assertThat(adapter.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(requests()).isEqualTo(12);
        }
    }

    @Nested
    class RetriesAndTimeouts {

        @Test
        void getIsRetriedUntilItSucceeds() {
            razorpay.stubFor(get("/v1/payments/" + PAYMENT_ID).inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(503)).willSetStateTo("second"));
            razorpay.stubFor(get("/v1/payments/" + PAYMENT_ID).inScenario("flaky").whenScenarioStateIs("second")
                    .willReturn(serverError()).willSetStateTo("third"));
            razorpay.stubFor(get("/v1/payments/" + PAYMENT_ID).inScenario("flaky").whenScenarioStateIs("third")
                    .willReturn(okJson(paymentJson(PAYMENT_ID, "captured", null))));

            assertThat(adapter.fetchPayment(PAYMENT_ID).status()).isEqualTo(GatewayPaymentStatus.CAPTURED);
            assertThat(requests()).isEqualTo(3);
        }

        @Test
        void getGivesUpAfterMaxAttempts() {
            razorpay.stubFor(get("/v1/orders/" + ORDER_ID + "/payments").willReturn(aResponse().withStatus(503)));

            GatewayUnavailableException e = catchThrowableOfType(GatewayUnavailableException.class,
                    () -> adapter.fetchOrderPayments(ORDER_ID));

            assertThat(e.errorCode()).isEqualTo(ErrorCode.GATEWAY_UNAVAILABLE);
            assertThat(requests()).isEqualTo(3);
        }

        @Test
        void createOrderIsNeverRetried() {
            razorpay.stubFor(post("/v1/orders").willReturn(serverError()));

            catchThrowableOfType(GatewayUnavailableException.class,
                    () -> adapter.createOrder(Money.of(100, Money.INR), "topup-3"));

            assertThat(requests()).as("a retried POST could create a second order").isEqualTo(1);
        }

        @Test
        void slowGatewayTimesOutAtTheLimitNotAtTheDelay() {
            adapter.close();
            adapter = adapter(3, SHORT_TIMEOUT);
            razorpay.stubFor(post("/v1/orders").willReturn(okJson(ORDER_JSON).withFixedDelay(3_000)));

            long start = System.nanoTime();
            GatewayUnavailableException e = catchThrowableOfType(GatewayUnavailableException.class,
                    () -> adapter.createOrder(Money.of(150050, Money.INR), "topup-1"));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(e).isNotNull();
            assertThat(elapsed).isGreaterThanOrEqualTo(SHORT_TIMEOUT).isLessThan(Duration.ofMillis(1_500));
        }

        @Test
        void connectionResetIsUnavailable() {
            razorpay.stubFor(get("/v1/payments/" + PAYMENT_ID)
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            catchThrowableOfType(GatewayUnavailableException.class, () -> adapter.fetchPayment(PAYMENT_ID));

            // 3 Resilience4j attempts; the JDK HttpClient itself re-sends an idempotent GET once on a reset
            // connection, so each attempt may be 2 requests. It never does that for POST (see createOrder tests).
            assertThat(requests()).isBetween(3, 6);
        }

        @Test
        void connectionResetOnCreateOrderIsSentOnlyOnce() {
            razorpay.stubFor(post("/v1/orders").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            catchThrowableOfType(GatewayUnavailableException.class,
                    () -> adapter.createOrder(Money.of(100, Money.INR), "topup-4"));

            assertThat(requests()).as("neither Resilience4j nor the JDK client re-sends a POST").isEqualTo(1);
        }

        @Test
        void garbledSuccessBodyIsUnavailable() {
            razorpay.stubFor(get("/v1/payments/" + PAYMENT_ID).willReturn(okJson("{not json")));

            GatewayUnavailableException e = catchThrowableOfType(GatewayUnavailableException.class,
                    () -> adapter.fetchPayment(PAYMENT_ID));

            assertThat(e).isNotNull();
        }

        @Test
        void successWithoutAmountIsUnavailableNotAZeroPayment() {
            razorpay.stubFor(get("/v1/payments/" + PAYMENT_ID).willReturn(okJson("""
                    {"id": "pay_29QQoUBi66xm2f", "currency": "INR", "status": "captured"}""")));

            catchThrowableOfType(GatewayUnavailableException.class, () -> adapter.fetchPayment(PAYMENT_ID));
        }
    }

    @Nested
    class CircuitBreaking {

        @Test
        void opensAfterHalfTheWindowFailsThenFailsFastWithoutCallingTheGateway() {
            adapter.close();
            adapter = adapter(1); // one attempt per call, so 10 calls = 10 recorded failures
            razorpay.stubFor(get("/v1/payments/" + PAYMENT_ID).willReturn(aResponse().withStatus(503)));

            for (int i = 0; i < 10; i++) {
                catchThrowableOfType(GatewayUnavailableException.class, () -> adapter.fetchPayment(PAYMENT_ID));
            }
            assertThat(adapter.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

            GatewayUnavailableException e = catchThrowableOfType(GatewayUnavailableException.class,
                    () -> adapter.fetchPayment(PAYMENT_ID));

            assertThat(e).hasCauseInstanceOf(CallNotPermittedException.class);
            assertThat(e.errorCode()).isEqualTo(ErrorCode.GATEWAY_UNAVAILABLE);
            assertThat(requests()).as("an open circuit does not call the gateway").isEqualTo(10);
        }

        @Test
        void openCircuitStopsRetriesAtOnce() {
            razorpay.stubFor(get("/v1/payments/" + PAYMENT_ID).willReturn(aResponse().withStatus(503)));
            for (int i = 0; i < 4; i++) { // 4 calls x 3 attempts: the 10th failure opens the circuit mid-retry
                catchThrowableOfType(GatewayUnavailableException.class, () -> adapter.fetchPayment(PAYMENT_ID));
            }

            assertThat(adapter.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(requests()).isEqualTo(10);
        }

        @Test
        void halfOpenTrialCallsCloseItAgainWhenTheGatewayRecovers() throws InterruptedException {
            adapter.close();
            adapter = adapter(1);
            razorpay.stubFor(get("/v1/payments/" + PAYMENT_ID).willReturn(aResponse().withStatus(503)));
            for (int i = 0; i < 10; i++) {
                catchThrowableOfType(GatewayUnavailableException.class, () -> adapter.fetchPayment(PAYMENT_ID));
            }
            assertThat(adapter.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

            razorpay.stubFor(get("/v1/payments/" + PAYMENT_ID).willReturn(okJson(paymentJson(PAYMENT_ID, "captured", null))));
            Thread.sleep(OPEN_STATE.plusMillis(100));

            for (int i = 0; i < 3; i++) { // permitted-calls-in-half-open
                assertThat(adapter.fetchPayment(PAYMENT_ID).status()).isEqualTo(GatewayPaymentStatus.CAPTURED);
            }
            assertThat(adapter.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        void timeoutsCountAsFailures() {
            adapter.close();
            adapter = adapter(1, SHORT_TIMEOUT);
            razorpay.stubFor(get("/v1/payments/" + PAYMENT_ID).willReturn(okJson(ORDER_JSON).withFixedDelay(1_000)));

            for (int i = 0; i < 10; i++) {
                catchThrowableOfType(GatewayUnavailableException.class, () -> adapter.fetchPayment(PAYMENT_ID));
            }

            assertThat(adapter.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        }
    }
}
