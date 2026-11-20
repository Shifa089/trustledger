package com.trustledger.core.payments;

import static com.trustledger.core.payments.RazorpayWebhooks.captured;
import static com.trustledger.core.payments.RazorpayWebhooks.failed;
import static com.trustledger.core.payments.RazorpayWebhooks.newEventId;
import static com.trustledger.core.payments.RazorpayWebhooks.newPaymentId;
import static com.trustledger.core.payments.RazorpayWebhooks.signed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.ledger.AccountCodes;
import com.trustledger.core.payments.PaymentsFixtures.Customer;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.WalletTier;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code payment.captured} / {@code payment.failed} webhooks settling top-ups end to end (FR-P-4, FR-P-5): signed
 * delivery through the production filter chain, the webhook processor's transaction, the ledger, the wallet and
 * Postgres. Over-limit and frozen-wallet captures are parked in suspense (ADR 0006).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class TopupWebhookSettlementIT {

    /** MIN tier: ₹10,000 balance cap and monthly load (SPEC §2). */
    private static final long AMOUNT = 50_000; // ₹500.00

    @Autowired
    WebApplicationContext context;

    @Autowired
    PaymentsFixtures fixtures;

    @Autowired
    ApplicationEvents events;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
    }

    private record Pending(Customer customer, UUID topupId, String orderId) {
    }

    private Pending pendingTopup(long balanceMinor, long amountMinor) {
        Customer customer = fixtures.customer(WalletTier.MIN, balanceMinor);
        UUID topupId = fixtures.insertTopup(customer.walletId(), amountMinor, "PENDING", Instant.now());
        return new Pending(customer, topupId, fixtures.topupOrderId(topupId));
    }

    private void deliver(String body) throws Exception {
        deliver(newEventId(), body);
    }

    private void deliver(String eventId, String body) throws Exception {
        mvc.perform(signed(eventId, body)).andExpect(status().isOk());
    }

    private long balance(Customer customer) {
        return fixtures.wallet(customer.walletId()).balance().amountMinor();
    }

    private long capturedEvents(UUID topupId) {
        return events.stream(TopupCaptured.class).filter(e -> e.topupId().equals(topupId)).count();
    }

    // --- credited ---

    @Test
    void capturedPaymentCreditsTheWalletThroughTheLedger() throws Exception {
        Pending topup = pendingTopup(100_000, AMOUNT);
        String paymentId = newPaymentId();

        deliver(captured(paymentId, topup.orderId(), AMOUNT));

        assertThat(fixtures.topupStatus(topup.topupId())).isEqualTo("CAPTURED");
        assertThat(fixtures.topupPaymentId(topup.topupId())).isEqualTo(paymentId);
        assertThat(balance(topup.customer())).isEqualTo(100_000 + AMOUNT);
        assertThat(fixtures.ledgerBalance(topup.customer().walletId())).isEqualTo(100_000 + AMOUNT);
        assertThat(fixtures.topupEntryLines(topup.topupId())).containsExactly(
                AccountCodes.GATEWAY_CLEARING + " DEBIT " + AMOUNT,
                AccountCodes.wallet(topup.customer().walletId()) + " CREDIT " + AMOUNT);
        assertThat(events.stream(TopupCaptured.class).filter(e -> e.topupId().equals(topup.topupId())))
                .singleElement().satisfies(e -> {
                    assertThat(e.walletId()).isEqualTo(topup.customer().walletId());
                    assertThat(e.amount()).isEqualTo(Money.of(AMOUNT, Money.INR));
                });
    }

    @Test
    void theSameEventDeliveredTwiceIsCreditedOnce() throws Exception {
        Pending topup = pendingTopup(0, AMOUNT);
        String eventId = newEventId();
        String body = captured(newPaymentId(), topup.orderId(), AMOUNT);

        deliver(eventId, body);
        deliver(eventId, body);

        assertThat(balance(topup.customer())).isEqualTo(AMOUNT);
        assertThat(fixtures.topupEntries(topup.topupId())).isEqualTo(1);
        assertThat(capturedEvents(topup.topupId())).isEqualTo(1);
    }

    @Test
    void theSamePaymentUnderAnotherEventIdIsCreditedOnce() throws Exception {
        Pending topup = pendingTopup(0, AMOUNT);
        String body = captured(newPaymentId(), topup.orderId(), AMOUNT);

        deliver(newEventId(), body);
        deliver(newEventId(), body);

        assertThat(balance(topup.customer())).isEqualTo(AMOUNT);
        assertThat(fixtures.topupEntries(topup.topupId())).isEqualTo(1);
        assertThat(capturedEvents(topup.topupId())).isEqualTo(1);
    }

    // --- parked in suspense (ADR 0006) ---

    @Test
    void captureOverTheBalanceCapIsParkedInSuspense() throws Exception {
        Pending topup = pendingTopup(990_000, AMOUNT); // ₹9,900 + ₹500 > ₹10,000

        deliver(captured(newPaymentId(), topup.orderId(), AMOUNT));

        assertThat(fixtures.topupStatus(topup.topupId())).isEqualTo("PARKED");
        assertThat(balance(topup.customer())).isEqualTo(990_000);
        assertThat(fixtures.ledgerBalance(topup.customer().walletId())).isEqualTo(990_000);
        assertThat(fixtures.topupEntryLines(topup.topupId())).containsExactly(
                AccountCodes.GATEWAY_CLEARING + " DEBIT " + AMOUNT, AccountCodes.SUSPENSE + " CREDIT " + AMOUNT);
        assertThat(capturedEvents(topup.topupId())).isZero();
    }

    @Test
    void captureForAFrozenWalletIsParkedInSuspense() throws Exception {
        Pending topup = pendingTopup(0, AMOUNT);
        fixtures.freeze(topup.customer().walletId());

        deliver(captured(newPaymentId(), topup.orderId(), AMOUNT));

        assertThat(fixtures.topupStatus(topup.topupId())).isEqualTo("PARKED");
        assertThat(balance(topup.customer())).isZero();
        assertThat(capturedEvents(topup.topupId())).isZero();
    }

    @Test
    void aParkedTopupIsNotParkedTwice() throws Exception {
        Pending topup = pendingTopup(990_000, AMOUNT);
        String body = captured(newPaymentId(), topup.orderId(), AMOUNT);

        deliver(body);
        deliver(body);

        assertThat(fixtures.topupEntries(topup.topupId())).isEqualTo(1);
    }

    // --- failures and out-of-order events ---

    @Test
    void failedPaymentFailsThePendingTopup() throws Exception {
        Pending topup = pendingTopup(0, AMOUNT);

        deliver(failed(newPaymentId(), topup.orderId(), AMOUNT));

        assertThat(fixtures.topupStatus(topup.topupId())).isEqualTo("FAILED");
        assertThat(balance(topup.customer())).isZero();
        assertThat(fixtures.topupEntries(topup.topupId())).isZero();
    }

    @Test
    void aRetryCapturedAfterAFailedAttemptIsStillCredited() throws Exception {
        Pending topup = pendingTopup(0, AMOUNT);

        deliver(failed(newPaymentId(), topup.orderId(), AMOUNT));
        deliver(captured(newPaymentId(), topup.orderId(), AMOUNT));

        assertThat(fixtures.topupStatus(topup.topupId())).isEqualTo("CAPTURED");
        assertThat(balance(topup.customer())).isEqualTo(AMOUNT);
        assertThat(fixtures.ledgerBalance(topup.customer().walletId())).isEqualTo(AMOUNT);
    }

    @Test
    void aFailureArrivingAfterTheCaptureChangesNothing() throws Exception {
        Pending topup = pendingTopup(0, AMOUNT);

        deliver(captured(newPaymentId(), topup.orderId(), AMOUNT));
        deliver(failed(newPaymentId(), topup.orderId(), AMOUNT));

        assertThat(fixtures.topupStatus(topup.topupId())).isEqualTo("CAPTURED");
        assertThat(balance(topup.customer())).isEqualTo(AMOUNT);
    }

    @Test
    void aCaptureAfterExpiryIsStillCredited() throws Exception {
        Customer customer = fixtures.customer(WalletTier.MIN, 0);
        UUID topupId = fixtures.insertTopup(customer.walletId(), AMOUNT, "EXPIRED", Instant.now());

        deliver(captured(newPaymentId(), fixtures.topupOrderId(topupId), AMOUNT));

        assertThat(fixtures.topupStatus(topupId)).isEqualTo("CAPTURED");
        assertThat(balance(customer)).isEqualTo(AMOUNT);
    }

    @Test
    void aCaptureForAnUnknownOrderIsAcknowledgedAndIgnored() throws Exception {
        deliver(captured(newPaymentId(), "order_UNKNOWN0000000001", AMOUNT));
        // 200 above: the event is stored and processed; nothing to credit.
    }

    @Test
    void aCaptureWithADifferentAmountIsIgnored() throws Exception {
        Pending topup = pendingTopup(0, AMOUNT);

        deliver(captured(newPaymentId(), topup.orderId(), AMOUNT + 1));

        assertThat(fixtures.topupStatus(topup.topupId())).isEqualTo("PENDING");
        assertThat(balance(topup.customer())).isZero();
    }
}
