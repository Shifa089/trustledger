package com.trustledger.core.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.trustledger.core.ApiMockMvc.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.payments.PaymentsFixtures.Customer;
import com.trustledger.core.payments.domain.PaymentGateway;
import com.trustledger.core.payments.infrastructure.GatewayCircuit;
import com.trustledger.core.wallet.WalletTier;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /topups} and {@code GET /topups/{id}} through the production filter chain, with the shared WireMock
 * server standing in for Razorpay (ADR 0014): order creation, idempotency, every refusal (with no gateway request),
 * gateway failures (503/502, top-up FAILED, key released), the open circuit, and BOLA on reads.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TopupControllerIT {

    private static final String URL = "/api/v1/topups";
    private static final String KEY_ID = "rzp_test_FAKEKEY0000000";

    @Autowired
    WebApplicationContext context;

    @Autowired
    PaymentsFixtures fixtures;

    @Autowired
    WireMockServer razorpay;

    @Autowired
    PaymentGateway gateway;

    @Autowired
    JsonMapper json;

    MockMvc mvc;
    Customer full;

    @BeforeEach
    void setUp() {
        GatewayCircuit.reset(gateway);
        mvc = ApiMockMvc.create(context);
        full = fixtures.customer(WalletTier.FULL, 0);
    }

    @AfterEach
    void tearDown() {
        razorpay.resetAll();
        GatewayCircuit.reset(gateway); // later test classes share this adapter
    }

    // --- helpers ---

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static String newOrderId() {
        return "order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    /** Razorpay answers every order request with a fresh order ID for the requested amount. */
    private String stubOrder(long paise) {
        String orderId = newOrderId();
        razorpay.stubFor(WireMock.post("/v1/orders").willReturn(okJson("""
                {"id": "%s", "entity": "order", "amount": %d, "amount_paid": 0, "amount_due": %d,
                 "currency": "INR", "receipt": "r", "status": "created", "attempts": 0}"""
                .formatted(orderId, paise, paise))));
        return orderId;
    }

    private void stubOrderStatus(int httpStatus) {
        razorpay.stubFor(WireMock.post("/v1/orders").willReturn(aResponse().withStatus(httpStatus)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": {\"code\": \"BAD_REQUEST_ERROR\"}}")));
    }

    private int orderRequests() {
        return razorpay.findAll(postRequestedFor(urlEqualTo("/v1/orders"))).size();
    }

    private static MockHttpServletRequestBuilder topup(Customer who, String key, String amount) {
        MockHttpServletRequestBuilder builder = post(URL).with(customer(who.subject()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"" + amount + "\"}");
        return key == null ? builder : builder.header("Idempotency-Key", key);
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
    }

    // --- happy path and idempotency ---

    @Test
    void createsAPendingTopupWithTheGatewayOrder() throws Exception {
        String orderId = stubOrder(50_000);

        JsonNode created = body(mvc.perform(topup(full, newKey(), "500.00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value("500.00"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.gatewayOrderId").value(orderId))
                .andExpect(jsonPath("$.keyId").value(KEY_ID))
                .andExpect(jsonPath("$.gatewayPaymentId").doesNotExist())
                .andReturn().getResponse());

        UUID id = UUID.fromString(created.get("id").asText());
        // Integer paise; the receipt is our top-up ID, so the order can be traced back.
        razorpay.verify(1, postRequestedFor(urlEqualTo("/v1/orders")).withRequestBody(equalToJson("""
                {"amount": 50000, "currency": "INR", "receipt": "%s"}""".formatted(id))));
        assertThat(fixtures.topupStatus(id)).isEqualTo("PENDING");
        assertThat(fixtures.topupOrderId(id)).isEqualTo(orderId);
        // Nothing is credited before the payment is captured (webhook, M7 part 3).
        assertThat(fixtures.wallet(full.walletId()).balance().amountMinor()).isZero();
    }

    @Test
    void sameKeyTwiceCreatesOneOrderAndReplaysTheResponse() throws Exception {
        stubOrder(25_000);
        String key = newKey();

        String first = mvc.perform(topup(full, key, "250.00")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(topup(full, key, "250.00")).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(second)).isEqualTo(json.readTree(first));
        assertThat(orderRequests()).isEqualTo(1);
        assertThat(fixtures.topupsOf(full.walletId())).isEqualTo(1);
    }

    @Test
    void sameKeyWithAnotherAmountIsRefused() throws Exception {
        stubOrder(25_000);
        String key = newKey();
        mvc.perform(topup(full, key, "250.00")).andExpect(status().isCreated());

        mvc.perform(topup(full, key, "251.00")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(orderRequests()).isEqualTo(1);
    }

    @Test
    void idempotencyKeyIsRequired() throws Exception {
        mvc.perform(topup(full, null, "250.00")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        assertThat(orderRequests()).isZero();
    }

    // --- refusals: no gateway request, no row ---

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "0.50", "0.99", "1.005", "-5.00", "abc", "1e3", ""})
    void badAmountsAreRefusedBeforeTheGateway(String amount) throws Exception {
        mvc.perform(topup(full, newKey(), amount)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        assertThat(orderRequests()).isZero();
        assertThat(fixtures.topupsOf(full.walletId())).isZero();
    }

    @Test
    void theMinimumItselfIsAccepted() throws Exception {
        stubOrder(100);
        mvc.perform(topup(full, newKey(), "1.00")).andExpect(status().isCreated());
    }

    @Test
    void monthlyLoadIsEnforcedWithOpenAndCapturedTopups() throws Exception {
        Customer min = fixtures.customer(WalletTier.MIN, 0);
        fixtures.insertTopup(min.walletId(), 600_000, "CAPTURED", Instant.now()); // ₹6,000 loaded
        fixtures.insertTopup(min.walletId(), 300_000, "PENDING", Instant.now());  // ₹3,000 still open
        fixtures.insertTopup(min.walletId(), 500_000, "FAILED", Instant.now());   // does not count

        mvc.perform(topup(min, newKey(), "1000.01")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("monthly")));
        assertThat(orderRequests()).isZero();
        assertThat(fixtures.topupsOf(min.walletId())).isEqualTo(3);

        stubOrder(100_000);
        mvc.perform(topup(min, newKey(), "1000.00")).andExpect(status().isCreated()); // exactly on the limit
    }

    @Test
    void balanceCapIsEnforcedForAFullWallet() throws Exception {
        Customer rich = fixtures.customer(WalletTier.FULL, 19_950_000); // ₹1,99,500 of a ₹2,00,000 cap

        mvc.perform(topup(rich, newKey(), "500.01")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("maximum")));
        assertThat(orderRequests()).isZero();
        assertThat(fixtures.topupsOf(rich.walletId())).isZero();
    }

    @Test
    void minTierCapIsTenThousandRupees() throws Exception {
        Customer min = fixtures.customer(WalletTier.MIN, 950_000); // ₹9,500

        mvc.perform(topup(min, newKey(), "500.01")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("LIMIT_EXCEEDED"));
        assertThat(orderRequests()).isZero();

        stubOrder(50_000);
        mvc.perform(topup(min, newKey(), "500.00")).andExpect(status().isCreated());
    }

    @Test
    void frozenWalletCannotTopUp() throws Exception {
        fixtures.freeze(full.walletId());

        mvc.perform(topup(full, newKey(), "100.00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("WALLET_FROZEN"));
        assertThat(orderRequests()).isZero();
        assertThat(fixtures.topupsOf(full.walletId())).isZero();
    }

    @Test
    void userWithoutWalletNeedsKyc() throws Exception {
        Customer noWallet = fixtures.userWithoutWallet();

        mvc.perform(topup(noWallet, newKey(), "100.00")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("KYC_REQUIRED"));
        assertThat(orderRequests()).isZero();
    }

    // --- gateway failures ---

    @Test
    void gatewayServerErrorIs503AndTheKeyCanBeRetried() throws Exception {
        stubOrderStatus(500);
        String key = newKey();

        mvc.perform(topup(full, key, "300.00")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("GATEWAY_UNAVAILABLE"));
        assertThat(orderRequests()).isEqualTo(1); // createOrder is never retried
        assertThat(fixtures.topupsOf(full.walletId())).isEqualTo(1);

        // The key was released: the same request succeeds once the gateway is back, as a new top-up.
        razorpay.resetAll();
        stubOrder(30_000);
        String retried = mvc.perform(topup(full, key, "300.00")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(json.readTree(retried).get("id").asText());
        assertThat(fixtures.topupStatus(id)).isEqualTo("PENDING");
        assertThat(fixtures.topupsOf(full.walletId())).isEqualTo(2);
        assertThat(failedTopups(full)).isEqualTo(1);
    }

    @Test
    void gatewayTimeoutIs503AndTheTopupFails() throws Exception {
        razorpay.stubFor(WireMock.post("/v1/orders").willReturn(okJson("{}").withFixedDelay(4_000)));

        long start = System.nanoTime();
        mvc.perform(topup(full, newKey(), "300.00")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("GATEWAY_UNAVAILABLE"));
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(millis).isLessThan(3_900); // the 3 s time limit, not the 4 s delay
        assertThat(failedTopups(full)).isEqualTo(1);
    }

    @Test
    void gatewayRejectionIs502AndTheTopupFails() throws Exception {
        stubOrderStatus(400);

        mvc.perform(topup(full, newKey(), "300.00")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("GATEWAY_REJECTED"))
                .andExpect(jsonPath("$.detail").value("The payment gateway rejected the request."));
        assertThat(failedTopups(full)).isEqualTo(1);
    }

    @Test
    void openCircuitFailsFastWithoutCallingTheGateway() throws Exception {
        stubOrderStatus(503);
        for (int i = 0; i < 10; i++) {
            mvc.perform(topup(full, newKey(), "10.00")).andExpect(status().isServiceUnavailable());
        }
        assertThat(GatewayCircuit.state(gateway)).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(orderRequests()).isEqualTo(10);

        mvc.perform(topup(full, newKey(), "10.00")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("GATEWAY_UNAVAILABLE"));

        assertThat(orderRequests()).isEqualTo(10); // no request reached the gateway
        assertThat(failedTopups(full)).isEqualTo(11);
    }

    private long failedTopups(Customer who) {
        return fixtures.topupsIn(who.walletId(), "FAILED");
    }

    // --- reads ---

    @Test
    void ownerCanReadTheTopupAndNobodyElseCan() throws Exception {
        String orderId = stubOrder(12_345);
        String created = mvc.perform(topup(full, newKey(), "123.45")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(created).get("id").asText());

        mvc.perform(get(URL + "/" + id).with(customer(full.subject()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value("123.45"))
                .andExpect(jsonPath("$.gatewayOrderId").value(orderId))
                .andExpect(jsonPath("$.keyId").value(KEY_ID));

        Customer other = fixtures.customer(WalletTier.FULL, 0);
        String otherProblem = mvc.perform(get(URL + "/" + id).with(customer(other.subject())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        String unknownProblem = mvc.perform(get(URL + "/" + UUID.randomUUID()).with(customer(full.subject())))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        // Same detail for "someone else's" and "does not exist": IDs can't be probed.
        assertThat(json.readTree(otherProblem).get("detail")).isEqualTo(json.readTree(unknownProblem).get("detail"));

        Customer noWallet = fixtures.userWithoutWallet();
        mvc.perform(get(URL + "/" + id).with(customer(noWallet.subject()))).andExpect(status().isNotFound());
    }
}
