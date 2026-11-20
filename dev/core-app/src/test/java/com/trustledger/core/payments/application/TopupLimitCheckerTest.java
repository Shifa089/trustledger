package com.trustledger.core.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.payments.domain.TopupRepository;
import com.trustledger.core.payments.domain.TopupStatus;
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

class TopupLimitCheckerTest {

    private static final UUID WALLET = UUID.randomUUID();

    /** MIN tier (SPEC §2): ₹10,000 balance cap and ₹10,000 loaded per month. */
    private static final WalletLimits MIN = new WalletLimits(WalletTier.MIN, Money.parse("10000.00"),
            Money.parse("10000.00"), false, null, null, null);

    /** 19 Nov 2026 15:30 IST; the month started 1 Nov 00:00 IST = 31 Oct 18:30 UTC. */
    private static final String NOW = "2026-11-19T10:00:00Z";
    private static final Instant MONTH_START = Instant.parse("2026-10-31T18:30:00Z");

    private final TopupRepository topups = mock(TopupRepository.class);

    private TopupLimitChecker at(String instant) {
        return new TopupLimitChecker(topups, IdempotencyServiceTest.properties(),
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static WalletView wallet(String balance) {
        Money money = Money.parse(balance);
        return new WalletView(WALLET, UUID.randomUUID(), WalletTier.MIN, WalletStatus.ACTIVE, money,
                Money.zero(Money.INR), money);
    }

    private void loaded(long minor) {
        when(topups.sumCreatedSince(eq(WALLET), eq(MONTH_START), eq(TopupStatus.COUNTED_FOR_LOAD))).thenReturn(minor);
    }

    private void open(long minor) {
        when(topups.sumByStatus(eq(WALLET), eq(TopupStatus.OPEN))).thenReturn(minor);
    }

    private static void limitExceeded(Runnable call, String message) {
        assertThatThrownBy(call::run).isInstanceOf(TrustLedgerException.class)
                .hasMessageContaining(message)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(ErrorCode.LIMIT_EXCEEDED);
    }

    // --- monthly load ---

    @Test
    void landingExactlyOnTheMonthlyLoadIsAllowed() {
        loaded(600_000); // ₹6,000 already loaded (or open) this month
        assertThatCode(() -> at(NOW).check(wallet("0.00"), MIN, Money.parse("4000.00"))).doesNotThrowAnyException();
    }

    @Test
    void onePaisaOverTheMonthlyLoadIsRefused() {
        loaded(600_000);
        limitExceeded(() -> at(NOW).check(wallet("0.00"), MIN, Money.parse("4000.01")), "monthly limit of 10000.00");
    }

    @Test
    void monthlyLoadCountsOpenAndCapturedTopupsOnly() {
        loaded(0);
        at(NOW).check(wallet("0.00"), MIN, Money.parse("1.00"));
        // FAILED and EXPIRED are not in the set: the money never arrived.
        verify(topups).sumCreatedSince(WALLET, MONTH_START, TopupStatus.COUNTED_FOR_LOAD);
        verify(topups).sumByStatus(WALLET, TopupStatus.OPEN);
    }

    @Test
    void theMonthStartsAtMidnightIndianTimeNotUtc() {
        // 30 Nov 2026 19:00 UTC is already 1 Dec 00:30 IST: a new month.
        TopupLimitChecker checker = at("2026-11-30T19:00:00Z");
        assertThat(checker.monthStart(Instant.parse("2026-11-30T19:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-30T18:30:00Z"));
        // 30 Nov 2026 18:00 UTC is still 30 Nov 23:30 IST: November.
        assertThat(checker.monthStart(Instant.parse("2026-11-30T18:00:00Z"))).isEqualTo(MONTH_START);
    }

    @Test
    void theRefusalLooksAtTheCurrentMonthWindow() {
        when(topups.sumCreatedSince(eq(WALLET), eq(Instant.parse("2026-11-30T18:30:00Z")), any()))
                .thenReturn(1_000_000L);
        limitExceeded(() -> at("2026-11-30T19:00:00Z").check(wallet("0.00"), MIN, Money.parse("0.01")),
                "monthly limit");
    }

    // --- balance cap ---

    @Test
    void landingExactlyOnTheBalanceCapIsAllowed() {
        loaded(0);
        open(100_000); // ₹1,000 still open
        assertThatCode(() -> at(NOW).check(wallet("8000.00"), MIN, Money.parse("1000.00")))
                .doesNotThrowAnyException();
    }

    @Test
    void openTopupsCountTowardsTheBalanceCap() {
        loaded(0);
        open(100_000);
        limitExceeded(() -> at(NOW).check(wallet("8000.00"), MIN, Money.parse("1000.01")),
                "balance above the maximum of 10000.00");
    }

    @Test
    void balanceAloneCanHitTheCap() {
        loaded(0);
        open(0);
        limitExceeded(() -> at(NOW).check(wallet("9999.99"), MIN, Money.parse("0.02")), "maximum");
    }

    // --- re-check on capture (M7 part 3b) ---

    private void capturedThisMonth(long minor) {
        when(topups.sumCreatedSince(eq(WALLET), eq(MONTH_START), eq(EnumSet.of(TopupStatus.CAPTURED))))
                .thenReturn(minor);
    }

    private static WalletView wallet(String balance, WalletStatus status) {
        Money money = Money.parse(balance);
        return new WalletView(WALLET, UUID.randomUUID(), WalletTier.MIN, status, money, Money.zero(Money.INR), money);
    }

    @Test
    void captureFitsWhenBalanceAndCapturedLoadLandExactlyOnTheLimits() {
        capturedThisMonth(500_000);
        assertThat(at(NOW).checkCapture(wallet("5000.00"), MIN, Money.parse("5000.00"))).isEmpty();
    }

    @Test
    void captureOverTheBalanceCapIsParked() {
        capturedThisMonth(0);
        assertThat(at(NOW).checkCapture(wallet("9000.00"), MIN, Money.parse("1000.01")))
                .contains(ParkReason.MAX_BALANCE);
    }

    @Test
    void captureOverTheMonthlyLoadIsParked() {
        capturedThisMonth(900_000);
        assertThat(at(NOW).checkCapture(wallet("0.00"), MIN, Money.parse("1000.01")))
                .contains(ParkReason.MONTHLY_LOAD);
    }

    @Test
    void captureIgnoresOpenTopupsBecauseTheyReservedTheirOwnRoom() {
        capturedThisMonth(0);
        open(1_000_000); // even the whole cap reserved by open top-ups (this one included) doesn't matter
        assertThat(at(NOW).checkCapture(wallet("0.00"), MIN, Money.parse("10000.00"))).isEmpty();
    }

    @Test
    void captureToAFrozenOrClosedWalletIsParked() {
        assertThat(at(NOW).checkCapture(wallet("0.00", WalletStatus.FROZEN), MIN, Money.parse("1.00")))
                .contains(ParkReason.WALLET_NOT_ACTIVE);
        assertThat(at(NOW).checkCapture(wallet("0.00", WalletStatus.CLOSED), MIN, Money.parse("1.00")))
                .contains(ParkReason.WALLET_NOT_ACTIVE);
    }

    @Test
    void captureCountsTheCurrentIndianMonth() {
        // 31 Oct 2026 19:00 UTC is already 1 Nov in India: the month starts 31 Oct 18:30 UTC.
        capturedThisMonth(1_000_000);
        assertThat(at("2026-10-31T19:00:00Z").checkCapture(wallet("0.00"), MIN, Money.parse("1.00")))
                .contains(ParkReason.MONTHLY_LOAD);
    }

    @Test
    void argumentsAreRequired() {
        TopupLimitChecker checker = at(NOW);
        assertThatThrownBy(() -> checker.check(null, MIN, Money.parse("1.00"))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> checker.check(wallet("0.00"), null, Money.parse("1.00")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> checker.check(wallet("0.00"), MIN, null)).isInstanceOf(NullPointerException.class);
    }
}
