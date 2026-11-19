package com.trustledger.core.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.payments.PaymentsFixtures.Customer;
import com.trustledger.core.payments.application.TopupResult;
import com.trustledger.core.payments.application.TopupService;
import com.trustledger.core.payments.domain.PaymentGateway;
import com.trustledger.core.payments.domain.TopupStatus;
import com.trustledger.core.payments.infrastructure.GatewayCircuit;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletTier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link TopupService#create} against the real database and WireMock: the gateway call happens with no transaction
 * open and no lock held, the method refuses to run inside a transaction, and concurrent top-ups can't together
 * exceed the monthly load.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TopupServiceIT {

    @Autowired
    TopupService service;

    @Autowired
    PaymentsFixtures fixtures;

    @Autowired
    WireMockServer razorpay;

    @Autowired
    PaymentGateway gateway;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void resetCircuit() {
        GatewayCircuit.reset(gateway);
    }

    @AfterEach
    void reset() {
        razorpay.resetAll();
        GatewayCircuit.reset(gateway);
    }

    /** Every order request gets a fresh order ID (WireMock response templating), after {@code delayMs}. */
    private void stubOrders(int delayMs) {
        razorpay.stubFor(WireMock.post("/v1/orders").willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id": "order_{{randomValue length=20 type='ALPHANUMERIC'}}", "entity": "order",
                         "amount": {{jsonPath request.body '$.amount'}}, "currency": "INR",
                         "receipt": "{{jsonPath request.body '$.receipt'}}", "status": "created"}""")
                .withTransformers("response-template")
                .withFixedDelay(delayMs)));
    }

    private int orderRequests() {
        return razorpay.findAll(postRequestedFor(urlEqualTo("/v1/orders"))).size();
    }

    @Test
    void theGatewayIsCalledWithNoTransactionOpenAndNoLockHeld() throws Exception {
        Customer customer = fixtures.customer(WalletTier.FULL, 0);
        stubOrders(1_500);

        CompletableFuture<TopupResult> call = CompletableFuture.supplyAsync(
                () -> service.create(customer.userId(), Money.parse("700.00")));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (orderRequests() == 0) {
            assertThat(System.nanoTime()).as("gateway request never arrived").isLessThan(deadline);
            Thread.onSpinWait();
        }

        // The request is now in flight at the "gateway". The CREATED row is already committed and visible...
        assertThat(jdbc.queryForObject("select status from payments.topups where wallet_id = ?", String.class,
                customer.walletId())).isEqualTo("CREATED");
        // ...and the wallet row is not locked: NOWAIT would fail at once if the creating transaction still held it.
        UUID locked = tx.execute(status -> jdbc.queryForObject(
                "select id from wallet.wallets where id = ? for update nowait", UUID.class, customer.walletId()));
        assertThat(locked).isEqualTo(customer.walletId());
        assertThat(call).isNotDone();

        TopupResult result = call.get(10, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TopupStatus.PENDING);
        assertThat(fixtures.topupStatus(result.topupId())).isEqualTo("PENDING");
    }

    @Test
    void createRefusesToRunInsideACallersTransaction() {
        Customer customer = fixtures.customer(WalletTier.FULL, 0);
        stubOrders(0);

        assertThatThrownBy(() -> tx.executeWithoutResult(
                status -> service.create(customer.userId(), Money.parse("100.00"))))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(orderRequests()).isZero();
        assertThat(fixtures.topupsOf(customer.walletId())).isZero();
    }

    @Test
    void concurrentTopupsCannotTogetherExceedTheMonthlyLoad() throws Exception {
        Customer customer = fixtures.customer(WalletTier.MIN, 0); // ₹10,000 per month, ₹10,000 cap
        stubOrders(50);

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> {
                try {
                    return service.create(customer.userId(), Money.parse("2000.00"));
                } catch (TrustLedgerException e) {
                    return e.errorCode();
                }
            });
        }
        List<Object> results = ConcurrentRun.together(tasks);

        assertThat(results).filteredOn(TopupResult.class::isInstance).hasSize(5);
        assertThat(results).filteredOn(ErrorCode.LIMIT_EXCEEDED::equals).hasSize(5);
        assertThat(orderRequests()).isEqualTo(5);
        assertThat(fixtures.topupsIn(customer.walletId(), "PENDING")).isEqualTo(5);
        assertThat(fixtures.topupsOf(customer.walletId())).isEqualTo(5);
    }
}
