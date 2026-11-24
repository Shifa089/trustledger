package com.trustledger.core.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trustledger.core.payments.domain.TransferRepository;
import com.trustledger.core.payments.domain.TransferStatus;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletLimits;
import com.trustledger.core.wallet.WalletStatus;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.WalletView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class LimitCheckerTest {

    private static final UUID SENDER = UUID.randomUUID();

    /** Daily ₹1,00,000, monthly ₹5,00,000, cap ₹2,00,000 (SPEC §2 FULL tier plus the own-policy monthly limit). */
    private static final WalletLimits FULL = new WalletLimits(WalletTier.FULL, Money.parse("200000.00"),
            Money.parse("200000.00"), true, Money.parse("100000.00"), Money.parse("50000.00"),
            Money.parse("500000.00"));
    private static final WalletLimits FULL_NO_MONTHLY = new WalletLimits(WalletTier.FULL, Money.parse("200000.00"),
            Money.parse("200000.00"), true, Money.parse("100000.00"), Money.parse("50000.00"), null);
    private static final WalletLimits MIN = new WalletLimits(WalletTier.MIN, Money.parse("10000.00"),
            Money.parse("10000.00"), false, null, null, null);

    private final TransferRepository transfers = mock(TransferRepository.class);

    private LimitChecker at(String instant) {
        return new LimitChecker(transfers, IdempotencyServiceTest.properties(),
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static void refused(Executable call, ErrorCode code) {
        assertThatThrownBy(call::execute).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(code);
    }

    private void used(String from, long minor) {
        when(transfers.sumOutgoingSince(eq(SENDER), eq(Instant.parse(from)), any())).thenReturn(minor);
    }

    // --- day and month windows in Asia/Kolkata (UTC+05:30) ---

    @Test
    void dayStartsAtMidnightInIndiaNotUtc() {
        // 18:29:59Z is 23:59:59 IST on the 19th; 18:30:00Z is 00:00 IST on the 20th.
        assertThat(at("2026-11-19T18:29:59Z").windowsAt(Instant.parse("2026-11-19T18:29:59Z")).dayStart())
                .isEqualTo(Instant.parse("2026-11-18T18:30:00Z"));
        assertThat(at("2026-11-19T18:30:00Z").windowsAt(Instant.parse("2026-11-19T18:30:00Z")).dayStart())
                .isEqualTo(Instant.parse("2026-11-19T18:30:00Z"));
    }

    @Test
    void monthStartsOnTheFirstInIndia() {
        LimitChecker checker = at("2026-11-19T10:00:00Z");

        assertThat(checker.windowsAt(Instant.parse("2026-11-19T10:00:00Z")).monthStart())
                .isEqualTo(Instant.parse("2026-10-31T18:30:00Z"));
        // 30 Nov 18:30Z is already 1 December in India.
        assertThat(checker.windowsAt(Instant.parse("2026-11-30T18:30:00Z")).monthStart())
                .isEqualTo(Instant.parse("2026-11-30T18:30:00Z"));
        assertThat(checker.windowsAt(Instant.parse("2026-11-30T18:29:59Z")).monthStart())
                .isEqualTo(Instant.parse("2026-10-31T18:30:00Z"));
    }

    @Test
    void sumsAreQueriedFromTheIndianDayAndMonthStart() {
        at("2026-11-19T18:45:00Z").checkSender(SENDER, FULL, Money.parse("1.00"));

        // 00:15 IST on 20 November: the day began 15 minutes ago, the month on 1 November IST.
        verify(transfers).sumOutgoingSince(eq(SENDER), eq(Instant.parse("2026-11-19T18:30:00Z")), any());
        verify(transfers).sumOutgoingSince(eq(SENDER), eq(Instant.parse("2026-10-31T18:30:00Z")), any());
    }

    @Test
    void completedAndHeldTransfersCount() {
        at("2026-11-19T10:00:00Z").checkSender(SENDER, FULL, Money.parse("1.00"));

        verify(transfers).sumOutgoingSince(eq(SENDER), eq(Instant.parse("2026-11-18T18:30:00Z")),
                eq(EnumSet.of(TransferStatus.COMPLETED, TransferStatus.HELD_FOR_REVIEW)));
    }

    // --- daily limit ---

    @Test
    void exactlyReachingTheDailyLimitIsAllowed() {
        used("2026-11-18T18:30:00Z", 9_000_000);

        assertThatCode(() -> at("2026-11-19T10:00:00Z").checkSender(SENDER, FULL, Money.parse("10000.00")))
                .doesNotThrowAnyException();
    }

    @Test
    void onePaisaOverTheDailyLimitIsRefused() {
        used("2026-11-18T18:30:00Z", 9_000_000);

        refused(() -> at("2026-11-19T10:00:00Z").checkSender(SENDER, FULL, Money.parse("10000.01")),
                ErrorCode.LIMIT_EXCEEDED);
    }

    @Test
    void aSingleTransferAboveTheDailyLimitIsRefused() {
        refused(() -> at("2026-11-19T10:00:00Z").checkSender(SENDER, FULL, Money.parse("100000.01")),
                ErrorCode.LIMIT_EXCEEDED);
    }

    @Test
    void yesterdayInIndiaDoesNotCountToday() {
        // At 18:30Z a new Indian day starts; the sum for "today" is queried from then and is empty.
        used("2026-11-18T18:30:00Z", 10_000_000);

        refused(() -> at("2026-11-19T18:29:59Z").checkSender(SENDER, FULL, Money.parse("0.01")),
                ErrorCode.LIMIT_EXCEEDED);
        assertThatCode(() -> at("2026-11-19T18:30:00Z").checkSender(SENDER, FULL, Money.parse("0.01")))
                .doesNotThrowAnyException();
    }

    // --- monthly limit ---

    @Test
    void monthlyLimitIsEnforced() {
        used("2026-10-31T18:30:00Z", 49_000_000);

        assertThatCode(() -> at("2026-11-19T10:00:00Z").checkSender(SENDER, FULL, Money.parse("10000.00")))
                .doesNotThrowAnyException();
        refused(() -> at("2026-11-19T10:00:00Z").checkSender(SENDER, FULL, Money.parse("10000.01")),
                ErrorCode.LIMIT_EXCEEDED);
    }

    @Test
    void noMonthlyLimitMeansNoMonthlyQuery() {
        at("2026-11-19T10:00:00Z").checkSender(SENDER, FULL_NO_MONTHLY, Money.parse("1.00"));

        verify(transfers, never()).sumOutgoingSince(eq(SENDER), eq(Instant.parse("2026-10-31T18:30:00Z")), any());
    }

    @Test
    void limitMessageDoesNotRevealTheUsedAmount() {
        used("2026-11-18T18:30:00Z", 9_999_999);

        assertThatThrownBy(() -> at("2026-11-19T10:00:00Z").checkSender(SENDER, FULL, Money.parse("1.00")))
                .hasMessageContaining("daily transfer limit of 100000.00")
                .hasMessageNotContaining("99999.99");
    }

    // --- tier ---

    @Test
    void minTierCannotSendAtAll() {
        refused(() -> at("2026-11-19T10:00:00Z").checkSender(SENDER, MIN, Money.parse("1.00")),
                ErrorCode.KYC_REQUIRED);
        verifyNoInteractions(transfers);
    }

    // --- receiver cap ---

    private static WalletView receiverWithBalance(String balance) {
        Money zero = Money.zero(Money.INR);
        return new WalletView(UUID.randomUUID(), UUID.randomUUID(), WalletTier.FULL, WalletStatus.ACTIVE,
                Money.parse(balance), zero, Money.parse(balance));
    }

    @Test
    void receiverMayReachItsCapExactly() {
        assertThatCode(() -> at("2026-11-19T10:00:00Z").checkReceiverCap(receiverWithBalance("150000.00"), FULL,
                Money.parse("50000.00"))).doesNotThrowAnyException();
    }

    @Test
    void receiverCapIsEnforcedWithoutRevealingTheBalance() {
        assertThatThrownBy(() -> at("2026-11-19T10:00:00Z").checkReceiverCap(receiverWithBalance("150000.00"), FULL,
                Money.parse("50000.01")))
                .isInstanceOf(TrustLedgerException.class)
                .hasMessageNotContaining("150000")
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(ErrorCode.LIMIT_EXCEEDED);
    }

    @Test
    void minTierReceiverHasTheSmallerCap() {
        refused(() -> at("2026-11-19T10:00:00Z").checkReceiverCap(receiverWithBalance("9000.00"), MIN,
                Money.parse("1000.01")), ErrorCode.LIMIT_EXCEEDED);
    }

    // --- usage for display (GET /wallets/me/limit-usage) ---

    @Test
    void usageSumsTheSameWindowsAndStatusesAsTheCheck() {
        // 19 Nov 15:30 IST: the day started 18 Nov 18:30Z, the month 31 Oct 18:30Z.
        when(transfers.sumOutgoingSince(SENDER, Instant.parse("2026-11-18T18:30:00Z"), LimitChecker.COUNTED))
                .thenReturn(25_000L);
        when(transfers.sumOutgoingSince(SENDER, Instant.parse("2026-10-31T18:30:00Z"), LimitChecker.COUNTED))
                .thenReturn(400_000L);

        LimitChecker.TransferUsage usage = at("2026-11-19T10:00:00Z").usage(SENDER);

        assertThat(usage.todayMinor()).isEqualTo(25_000L);
        assertThat(usage.thisMonthMinor()).isEqualTo(400_000L);
        assertThat(LimitChecker.COUNTED)
                .containsExactlyInAnyOrder(TransferStatus.COMPLETED, TransferStatus.HELD_FOR_REVIEW);
    }

    @Test
    void usageNeedsAWallet() {
        assertThatThrownBy(() -> at("2026-11-19T10:00:00Z").usage(null)).isInstanceOf(NullPointerException.class);
    }
}
