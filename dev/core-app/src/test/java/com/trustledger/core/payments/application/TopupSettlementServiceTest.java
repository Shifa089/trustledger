package com.trustledger.core.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trustledger.core.ledger.AccountCodes;
import com.trustledger.core.ledger.Direction;
import com.trustledger.core.ledger.EntryType;
import com.trustledger.core.ledger.LedgerApi;
import com.trustledger.core.ledger.PostingCommand;
import com.trustledger.core.payments.TopupCaptured;
import com.trustledger.core.payments.domain.GatewayPayment;
import com.trustledger.core.payments.domain.GatewayPaymentStatus;
import com.trustledger.core.payments.domain.Topup;
import com.trustledger.core.payments.domain.TopupRepository;
import com.trustledger.core.payments.domain.TopupStatus;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletLimits;
import com.trustledger.core.wallet.WalletStatus;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.WalletView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class TopupSettlementServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final UUID WALLET = UUID.randomUUID();
    private static final UUID TOPUP = UUID.randomUUID();
    private static final UUID ENTRY = UUID.randomUUID();
    private static final String ORDER = "order_TEST00000001";
    private static final String PAYMENT = "pay_TEST00000001";
    private static final Money AMOUNT = Money.parse("500.00");
    private static final WalletLimits LIMITS = new WalletLimits(WalletTier.MIN, Money.parse("10000.00"),
            Money.parse("10000.00"), false, null, null, null);

    private final TopupRepository topups = mock(TopupRepository.class);
    private final WalletApi wallets = mock(WalletApi.class);
    private final LedgerApi ledger = mock(LedgerApi.class);
    private final TopupLimitChecker limits = mock(TopupLimitChecker.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final TopupSettlementService service = new TopupSettlementService(topups, wallets, ledger, limits,
            events, Clock.fixed(NOW, ZoneOffset.UTC));

    private final WalletView wallet = new WalletView(WALLET, UUID.randomUUID(), WalletTier.MIN, WalletStatus.ACTIVE,
            Money.zero(Money.INR), Money.zero(Money.INR), Money.zero(Money.INR));

    private static Topup pending() {
        Topup topup = Topup.create(WALLET, AMOUNT, NOW.minusSeconds(600));
        ReflectionTestUtils.setField(topup, "id", TOPUP);
        topup.markPending(ORDER, NOW.minusSeconds(600));
        return topup;
    }

    private static GatewayPayment captured(String paymentId, Money amount) {
        return new GatewayPayment(paymentId, ORDER, amount, GatewayPaymentStatus.CAPTURED, null);
    }

    private static GatewayPayment failed() {
        return new GatewayPayment("pay_TEST00000002", ORDER, AMOUNT, GatewayPaymentStatus.FAILED,
                "BAD_REQUEST_ERROR");
    }

    private Topup stored(Topup topup) {
        when(topups.findByGatewayOrderIdForUpdate(ORDER)).thenReturn(Optional.of(topup));
        when(topups.findByIdForUpdate(TOPUP)).thenReturn(Optional.of(topup));
        return topup;
    }

    @BeforeEach
    void walletCanBeLocked() {
        when(wallets.lockForUpdate(List.of(WALLET))).thenReturn(List.of(wallet));
        when(wallets.limitsFor(WALLET)).thenReturn(LIMITS);
        when(ledger.post(any())).thenReturn(ENTRY);
    }

    private PostingCommand posted() {
        ArgumentCaptor<PostingCommand> captor = ArgumentCaptor.forClass(PostingCommand.class);
        verify(ledger).post(captor.capture());
        return captor.getValue();
    }

    // --- capture ---

    @Test
    void captureCreditsTheWalletPostsTheEntryAndPublishes() {
        Topup topup = stored(pending());
        when(limits.checkCapture(wallet, LIMITS, AMOUNT)).thenReturn(Optional.empty());

        assertThat(service.capture(captured(PAYMENT, AMOUNT))).isEqualTo(SettlementOutcome.CAPTURED);

        PostingCommand posting = posted();
        assertThat(posting.entryType()).isEqualTo(EntryType.TOPUP);
        assertThat(posting.referenceType()).isEqualTo("TOPUP");
        assertThat(posting.referenceId()).isEqualTo(TOPUP);
        assertThat(posting.lines()).extracting(l -> l.accountCode() + " " + l.direction() + " " + l.amount())
                .containsExactly(AccountCodes.GATEWAY_CLEARING + " " + Direction.DEBIT + " " + AMOUNT,
                        AccountCodes.wallet(WALLET) + " " + Direction.CREDIT + " " + AMOUNT);
        verify(wallets).credit(WALLET, AMOUNT);
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.CAPTURED);
        assertThat(topup.getGatewayPaymentId()).isEqualTo(PAYMENT);
        assertThat(topup.getJournalEntryId()).isEqualTo(ENTRY);
        verify(topups).saveAndFlush(topup);
        verify(events).publishEvent(new TopupCaptured(TOPUP, WALLET, AMOUNT, ENTRY, NOW));
    }

    @Test
    void theTopupIsLockedBeforeTheWalletAndLimitsAreCheckedUnderTheWalletLock() {
        stored(pending());
        when(limits.checkCapture(wallet, LIMITS, AMOUNT)).thenReturn(Optional.empty());

        service.capture(captured(PAYMENT, AMOUNT));

        InOrder order = inOrder(topups, wallets, limits, ledger);
        order.verify(topups).findByGatewayOrderIdForUpdate(ORDER);
        order.verify(wallets).lockForUpdate(List.of(WALLET));
        order.verify(limits).checkCapture(wallet, LIMITS, AMOUNT);
        order.verify(ledger).post(any());
        order.verify(wallets).credit(WALLET, AMOUNT);
    }

    @Test
    void overTheLimitIsParkedInSuspenseWithoutCreditOrEvent() {
        Topup topup = stored(pending());
        when(limits.checkCapture(wallet, LIMITS, AMOUNT)).thenReturn(Optional.of(ParkReason.MAX_BALANCE));

        assertThat(service.capture(captured(PAYMENT, AMOUNT))).isEqualTo(SettlementOutcome.PARKED);

        assertThat(posted().lines()).extracting(l -> l.accountCode() + " " + l.direction())
                .containsExactly(AccountCodes.GATEWAY_CLEARING + " DEBIT", AccountCodes.SUSPENSE + " CREDIT");
        verify(wallets, never()).credit(any(), any());
        verifyNoInteractions(events);
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.PARKED);
        assertThat(topup.getJournalEntryId()).isEqualTo(ENTRY);
        verify(topups).saveAndFlush(topup);
    }

    @Test
    void aFrozenWalletIsParked() {
        Topup topup = stored(pending());
        when(limits.checkCapture(wallet, LIMITS, AMOUNT)).thenReturn(Optional.of(ParkReason.WALLET_NOT_ACTIVE));

        assertThat(service.capture(captured(PAYMENT, AMOUNT))).isEqualTo(SettlementOutcome.PARKED);
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.PARKED);
    }

    @Test
    void lateCaptureOfAFailedTopupIsCredited() {
        Topup topup = pending();
        topup.fail(NOW.minusSeconds(60));
        stored(topup);
        when(limits.checkCapture(wallet, LIMITS, AMOUNT)).thenReturn(Optional.empty());

        assertThat(service.capture(captured(PAYMENT, AMOUNT))).isEqualTo(SettlementOutcome.CAPTURED);
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.CAPTURED);
    }

    @Test
    void lateCaptureOfAnExpiredTopupIsCredited() {
        Topup topup = pending();
        topup.expire(NOW.minusSeconds(60));
        stored(topup);
        when(limits.checkCapture(wallet, LIMITS, AMOUNT)).thenReturn(Optional.empty());

        assertThat(service.capture(captured(PAYMENT, AMOUNT))).isEqualTo(SettlementOutcome.CAPTURED);
    }

    @Test
    void theSamePaymentAgainIsIgnored() {
        Topup topup = pending();
        topup.capture(PAYMENT, ENTRY, NOW);
        stored(topup);

        assertThat(service.capture(captured(PAYMENT, AMOUNT))).isEqualTo(SettlementOutcome.IGNORED);
        nothingBooked();
    }

    @Test
    void aSecondPaymentForASettledTopupIsIgnored() {
        Topup topup = pending();
        topup.park(PAYMENT, ENTRY, NOW);
        stored(topup);

        assertThat(service.capture(captured("pay_TEST00000009", AMOUNT))).isEqualTo(SettlementOutcome.IGNORED);
        assertThat(topup.getGatewayPaymentId()).isEqualTo(PAYMENT);
        nothingBooked();
    }

    @Test
    void anUnknownOrderIsIgnored() {
        when(topups.findByGatewayOrderIdForUpdate(ORDER)).thenReturn(Optional.empty());
        assertThat(service.capture(captured(PAYMENT, AMOUNT))).isEqualTo(SettlementOutcome.IGNORED);
        nothingBooked();
    }

    @Test
    void anAmountThatDoesNotMatchTheTopupIsIgnored() {
        Topup topup = stored(pending());
        assertThat(service.capture(captured(PAYMENT, Money.parse("499.99")))).isEqualTo(SettlementOutcome.IGNORED);
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.PENDING);
        nothingBooked();
    }

    @Test
    void captureNeedsACapturedPaymentOnAnOrder() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.capture(failed()));
        assertThatIllegalArgumentException().isThrownBy(() -> service.capture(
                new GatewayPayment(PAYMENT, null, AMOUNT, GatewayPaymentStatus.CAPTURED, null)));
    }

    private void nothingBooked() {
        verify(ledger, never()).post(any());
        verify(wallets, never()).lockForUpdate(any());
        verify(wallets, never()).credit(any(), any());
        verify(topups, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    // --- fail ---

    @Test
    void failedPaymentFailsAPendingTopup() {
        Topup topup = stored(pending());
        assertThat(service.fail(failed())).isEqualTo(SettlementOutcome.FAILED);
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.FAILED);
        verify(topups).saveAndFlush(topup);
        verifyNoInteractions(ledger);
    }

    @Test
    void failedPaymentAfterTheCaptureChangesNothing() {
        Topup topup = pending();
        topup.capture(PAYMENT, ENTRY, NOW);
        stored(topup);

        assertThat(service.fail(failed())).isEqualTo(SettlementOutcome.IGNORED);
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.CAPTURED);
        verify(topups, never()).saveAndFlush(any());
    }

    @Test
    void failedPaymentForAnUnknownOrderIsIgnored() {
        when(topups.findByGatewayOrderIdForUpdate(ORDER)).thenReturn(Optional.empty());
        assertThat(service.fail(failed())).isEqualTo(SettlementOutcome.IGNORED);
    }

    // --- expire ---

    @Test
    void expireExpiresAPendingTopup() {
        Topup topup = stored(pending());
        assertThat(service.expire(TOPUP)).isEqualTo(SettlementOutcome.EXPIRED);
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.EXPIRED);
        verify(topups).saveAndFlush(topup);
    }

    @Test
    void expireLeavesASettledTopupAlone() {
        Topup topup = pending();
        topup.capture(PAYMENT, ENTRY, NOW);
        stored(topup);

        assertThat(service.expire(TOPUP)).isEqualTo(SettlementOutcome.IGNORED);
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.CAPTURED);
    }
}
