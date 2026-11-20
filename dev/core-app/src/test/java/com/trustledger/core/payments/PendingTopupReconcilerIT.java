package com.trustledger.core.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.trustledger.core.payments.RazorpayWebhooks.captured;
import static com.trustledger.core.payments.RazorpayWebhooks.newEventId;
import static com.trustledger.core.payments.RazorpayWebhooks.newPaymentId;
import static com.trustledger.core.payments.RazorpayWebhooks.signed;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.payments.PaymentsFixtures.Customer;
import com.trustledger.core.payments.application.PendingTopupReconciler;
import com.trustledger.core.payments.application.TopupReconciliationReport;
import com.trustledger.core.payments.application.TopupReconciliationService;
import com.trustledger.core.payments.domain.PaymentGateway;
import com.trustledger.core.payments.infrastructure.GatewayCircuit;
import com.trustledger.core.wallet.WalletTier;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * FR-P-6 against Postgres and WireMock (Razorpay's {@code GET /v1/orders/{id}/payments}): PENDING top-ups older than
 * 15 minutes are credited when the gateway says captured, expired after 24 hours with nothing captured, and left
 * alone when the gateway can't be asked. A webhook and the reconciler settling the same top-up at once credit once.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PendingTopupReconcilerIT {

    private static final long AMOUNT = 50_000;

    @Autowired
    PendingTopupReconciler reconciler;

    @Autowired
    TopupReconciliationService service;

    @Autowired
    PaymentsFixtures fixtures;

    @Autowired
    WireMockServer razorpay;

    @Autowired
    PaymentGateway gateway;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
        GatewayCircuit.reset(gateway);
        // Other IT classes leave old PENDING rows behind (shared database). Expire them, so each run here looks at
        // this test's top-ups only and the batch (oldest first) can't be filled by someone else's.
        jdbc.update("""
                update payments.topups set status = 'EXPIRED', updated_at = now()
                where status = 'PENDING' and created_at < now() - interval '15 minutes'""");
    }

    @AfterEach
    void tearDown() {
        razorpay.resetAll();
        GatewayCircuit.reset(gateway);
    }

    private record Pending(Customer customer, UUID topupId, String orderId) {
    }

    private Pending pendingFor(Duration age) {
        Customer customer = fixtures.customer(WalletTier.MIN, 0);
        UUID topupId = fixtures.insertTopup(customer.walletId(), AMOUNT, "PENDING", Instant.now().minus(age));
        return new Pending(customer, topupId, fixtures.topupOrderId(topupId));
    }

    private void orderPayments(String orderId, String... items) {
        razorpay.stubFor(WireMock.get("/v1/orders/" + orderId + "/payments").willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"entity":"collection","count":%d,"items":[%s]}"""
                        .formatted(items.length, String.join(",", items)))));
    }

    private static String payment(String paymentId, String orderId, String status) {
        return """
                {"id":"%s","entity":"payment","amount":%d,"currency":"INR","status":"%s","order_id":"%s",
                 "error_code":null,"email":"customer.one@example.test","contact":"+919999999999"}"""
                .formatted(paymentId, AMOUNT, status, orderId);
    }

    private TopupReconciliationReport run() {
        return reconciler.runLocked().orElseThrow();
    }

    private long balance(Customer customer) {
        return fixtures.wallet(customer.walletId()).balance().amountMinor();
    }

    @Test
    void aCapturedPaymentWhoseWebhookNeverArrivedIsCredited() {
        Pending topup = pendingFor(Duration.ofMinutes(20));
        String paymentId = newPaymentId();
        orderPayments(topup.orderId(), payment(newPaymentId(), topup.orderId(), "failed"),
                payment(paymentId, topup.orderId(), "captured"));

        TopupReconciliationReport report = run();

        assertThat(report.captured()).isEqualTo(1);
        assertThat(fixtures.topupStatus(topup.topupId())).isEqualTo("CAPTURED");
        assertThat(fixtures.topupPaymentId(topup.topupId())).isEqualTo(paymentId);
        assertThat(balance(topup.customer())).isEqualTo(AMOUNT);
        assertThat(fixtures.ledgerBalance(topup.customer().walletId())).isEqualTo(AMOUNT);
    }

    @Test
    void aCapturedPaymentOverTheLimitIsParked() {
        Customer customer = fixtures.customer(WalletTier.MIN, 990_000);
        UUID topupId = fixtures.insertTopup(customer.walletId(), AMOUNT, "PENDING",
                Instant.now().minus(Duration.ofMinutes(20)));
        String orderId = fixtures.topupOrderId(topupId);
        orderPayments(orderId, payment(newPaymentId(), orderId, "captured"));

        assertThat(run().parked()).isEqualTo(1);
        assertThat(fixtures.topupStatus(topupId)).isEqualTo("PARKED");
        assertThat(balance(customer)).isEqualTo(990_000);
    }

    @Test
    void nothingPaidYetIsLeftPending() {
        Pending topup = pendingFor(Duration.ofMinutes(20));
        orderPayments(topup.orderId());

        assertThat(run().stillPending()).isEqualTo(1);
        assertThat(fixtures.topupStatus(topup.topupId())).isEqualTo("PENDING");
    }

    @Test
    void nothingCapturedAfter24HoursExpires() {
        Pending topup = pendingFor(Duration.ofHours(25));
        orderPayments(topup.orderId(), payment(newPaymentId(), topup.orderId(), "failed"));

        assertThat(run().expired()).isEqualTo(1);
        assertThat(fixtures.topupStatus(topup.topupId())).isEqualTo("EXPIRED");
        assertThat(balance(topup.customer())).isZero();
    }

    @Test
    void youngerThan15MinutesIsNotLookedUp() {
        Pending topup = pendingFor(Duration.ofMinutes(5));

        run();

        assertThat(razorpay.findAll(getRequestedFor(urlEqualTo("/v1/orders/" + topup.orderId() + "/payments"))))
                .isEmpty();
        assertThat(fixtures.topupStatus(topup.topupId())).isEqualTo("PENDING");
    }

    @Test
    void aGatewayErrorLeavesTheTopupPendingEvenWhenOld() {
        Pending topup = pendingFor(Duration.ofHours(25));
        razorpay.stubFor(WireMock.get("/v1/orders/" + topup.orderId() + "/payments")
                .willReturn(aResponse().withStatus(500)));

        assertThat(run().skipped()).isEqualTo(1);
        assertThat(fixtures.topupStatus(topup.topupId())).isEqualTo("PENDING");
    }

    @Test
    void aWebhookAndTheReconcilerRacingOnOneTopupCreditItOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            Pending topup = pendingFor(Duration.ofMinutes(20));
            String paymentId = newPaymentId();
            orderPayments(topup.orderId(), payment(paymentId, topup.orderId(), "captured"));
            String body = captured(paymentId, topup.orderId(), AMOUNT);

            List<Callable<Object>> tasks = List.of(
                    () -> mvc.perform(signed(newEventId(), body)).andReturn().getResponse().getStatus(),
                    service::reconcile);
            ConcurrentRun.together(tasks);

            assertThat(fixtures.topupStatus(topup.topupId())).isEqualTo("CAPTURED");
            assertThat(fixtures.topupEntries(topup.topupId())).isEqualTo(1);
            assertThat(balance(topup.customer())).isEqualTo(AMOUNT);
            assertThat(fixtures.ledgerBalance(topup.customer().walletId())).isEqualTo(AMOUNT);
        }
    }
}
