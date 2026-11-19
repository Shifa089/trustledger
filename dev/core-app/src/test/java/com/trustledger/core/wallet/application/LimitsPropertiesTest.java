package com.trustledger.core.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.config.MoneyPropertyConverter;
import com.trustledger.core.wallet.WalletLimits;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.application.LimitsProperties.TierLimits;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class LimitsPropertiesTest {

    private static Money rupees(String value) {
        return Money.parse(value);
    }

    private static final TierLimits MIN = new TierLimits(rupees("10000.00"), rupees("10000.00"), false, null);
    private static final TierLimits FULL =
            new TierLimits(rupees("200000.00"), rupees("200000.00"), true, rupees("100000.00"));

    @Test
    void specLimitsTableIsValid() {
        LimitsProperties limits = new LimitsProperties(rupees("50000.00"), MIN, FULL);

        WalletLimits min = limits.forTier(WalletTier.MIN);
        assertThat(min.maxBalance()).isEqualTo(Money.of(1_000_000, Money.INR));
        assertThat(min.monthlyLoad()).isEqualTo(Money.of(1_000_000, Money.INR));
        assertThat(min.p2pAllowed()).isFalse();
        assertThat(min.dailyTransfer()).isNull();
        assertThat(min.highValueThreshold()).isNull();

        WalletLimits full = limits.forTier(WalletTier.FULL);
        assertThat(full.maxBalance()).isEqualTo(Money.of(20_000_000, Money.INR));
        assertThat(full.p2pAllowed()).isTrue();
        assertThat(full.dailyTransfer()).isEqualTo(Money.of(10_000_000, Money.INR));
        assertThat(full.highValueThreshold()).isEqualTo(Money.of(5_000_000, Money.INR));
    }

    @Test
    void minMustNotAllowP2p() {
        TierLimits p2pMin = new TierLimits(rupees("10000.00"), rupees("10000.00"), true, rupees("5000.00"));

        assertThatIllegalArgumentException().isThrownBy(() -> new LimitsProperties(rupees("50000.00"), p2pMin, FULL))
                .withMessageContaining("min.p2p-allowed");
    }

    @Test
    void p2pTierNeedsADailyTransferLimit() {
        TierLimits noDaily = new TierLimits(rupees("200000.00"), rupees("200000.00"), true, null);

        assertThatIllegalArgumentException().isThrownBy(() -> new LimitsProperties(rupees("50000.00"), MIN, noDaily))
                .withMessageContaining("full.daily-transfer");
    }

    @Test
    void dailyTransferWithoutP2pIsAMistake() {
        TierLimits minWithDaily = new TierLimits(rupees("10000.00"), rupees("10000.00"), false, rupees("1.00"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LimitsProperties(rupees("50000.00"), minWithDaily, FULL))
                .withMessageContaining("min.daily-transfer");
    }

    @Test
    void thresholdAboveDailyTransferFails() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LimitsProperties(rupees("100000.01"), MIN, FULL))
                .withMessageContaining("high-value-threshold");
    }

    @Test
    void thresholdEqualToDailyTransferIsAllowed() {
        assertThat(new LimitsProperties(rupees("100000.00"), MIN, FULL).forTier(WalletTier.FULL)
                .highValueThreshold()).isEqualTo(rupees("100000.00"));
    }

    @Test
    void missingOrZeroAmountsFail() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LimitsProperties(null, MIN, FULL))
                .withMessageContaining("high-value-threshold");
        assertThatIllegalArgumentException().isThrownBy(() -> new LimitsProperties(rupees("50000.00"), MIN,
                new TierLimits(rupees("0"), rupees("1.00"), true, rupees("1.00"))))
                .withMessageContaining("full.max-balance");
        assertThatIllegalArgumentException().isThrownBy(() -> new LimitsProperties(rupees("50000.00"),
                new TierLimits(rupees("1.00"), null, false, null), FULL))
                .withMessageContaining("min.monthly-load");
        assertThatIllegalArgumentException().isThrownBy(() -> new LimitsProperties(rupees("50000.00"), null, FULL))
                .withMessageContaining("required");
    }

    @Test
    void minLimitsMustNotExceedFull() {
        TierLimits bigMin = new TierLimits(rupees("300000.00"), rupees("10000.00"), false, null);
        TierLimits bigLoad = new TierLimits(rupees("10000.00"), rupees("300000.00"), false, null);

        assertThatIllegalArgumentException().isThrownBy(() -> new LimitsProperties(rupees("50000.00"), bigMin, FULL))
                .withMessageContaining("min.max-balance");
        assertThatIllegalArgumentException().isThrownBy(() -> new LimitsProperties(rupees("50000.00"), bigLoad, FULL))
                .withMessageContaining("min.monthly-load");
    }

    @Test
    void fullWithoutP2pNeedsNoThreshold() {
        TierLimits noP2p = new TierLimits(rupees("200000.00"), rupees("200000.00"), false, null);

        WalletLimits full = new LimitsProperties(null, MIN, noP2p).forTier(WalletTier.FULL);

        assertThat(full.p2pAllowed()).isFalse();
        assertThat(full.highValueThreshold()).isNull();
    }

    // --- monthly transfer limit (own policy, FR-P-2) ---

    @Test
    void monthlyTransferLimitIsOptional() {
        assertThat(new LimitsProperties(rupees("50000.00"), MIN, FULL).forTier(WalletTier.FULL).monthlyTransfer())
                .isNull();
    }

    @Test
    void monthlyTransferLimitReachesTheWalletLimits() {
        TierLimits monthly = new TierLimits(rupees("200000.00"), rupees("200000.00"), true, rupees("100000.00"),
                rupees("500000.00"));

        WalletLimits full = new LimitsProperties(rupees("50000.00"), MIN, monthly).forTier(WalletTier.FULL);

        assertThat(full.monthlyTransfer()).isEqualTo(Money.of(50_000_000, Money.INR));
        assertThat(new LimitsProperties(rupees("50000.00"), MIN, monthly).forTier(WalletTier.MIN).monthlyTransfer())
                .isNull();
    }

    @Test
    void monthlyTransferBelowDailyFails() {
        TierLimits tooSmall = new TierLimits(rupees("200000.00"), rupees("200000.00"), true, rupees("100000.00"),
                rupees("99999.99"));

        assertThatIllegalArgumentException().isThrownBy(() -> new LimitsProperties(rupees("50000.00"), MIN, tooSmall))
                .withMessageContaining("full.monthly-transfer");
    }

    @Test
    void monthlyTransferEqualToDailyIsAllowed() {
        TierLimits equal = new TierLimits(rupees("200000.00"), rupees("200000.00"), true, rupees("100000.00"),
                rupees("100000.00"));

        assertThat(new LimitsProperties(rupees("50000.00"), MIN, equal).forTier(WalletTier.FULL).monthlyTransfer())
                .isEqualTo(rupees("100000.00"));
    }

    @Test
    void monthlyTransferWithoutP2pOrZeroFails() {
        TierLimits minWithMonthly = new TierLimits(rupees("10000.00"), rupees("10000.00"), false, null,
                rupees("1.00"));
        TierLimits zero = new TierLimits(rupees("200000.00"), rupees("200000.00"), true, rupees("100000.00"),
                rupees("0"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LimitsProperties(rupees("50000.00"), minWithMonthly, FULL))
                .withMessageContaining("min.monthly-transfer");
        assertThatIllegalArgumentException().isThrownBy(() -> new LimitsProperties(rupees("50000.00"), MIN, zero))
                .withMessageContaining("full.monthly-transfer");
    }

    @Test
    void walletLimitsRefuseAMonthlyLimitWithoutP2p() {
        assertThatIllegalArgumentException().isThrownBy(() -> new WalletLimits(WalletTier.MIN, rupees("1.00"),
                rupees("1.00"), false, null, null, rupees("1.00")));
    }

    // --- binding from configuration, as at startup ---

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LimitsProperties.class)
    @Import(MoneyPropertyConverter.class)
    static class Binding {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Binding.class)
            .withPropertyValues(
                    "trustledger.limits.high-value-threshold=50000.00",
                    "trustledger.limits.min.max-balance=10000.00",
                    "trustledger.limits.min.monthly-load=10000.00",
                    "trustledger.limits.min.p2p-allowed=false",
                    "trustledger.limits.full.max-balance=200000.00",
                    "trustledger.limits.full.monthly-load=200000.00",
                    "trustledger.limits.full.p2p-allowed=true",
                    "trustledger.limits.full.daily-transfer=100000.00");

    @Test
    void bindsDecimalStringsToPaise() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            LimitsProperties limits = context.getBean(LimitsProperties.class);
            assertThat(limits.highValueThreshold().amountMinor()).isEqualTo(5_000_000);
            assertThat(limits.full().dailyTransfer().amountMinor()).isEqualTo(10_000_000);
        });
    }

    @Test
    void bindsTheOptionalMonthlyTransferLimit() {
        runner.withPropertyValues("trustledger.limits.full.monthly-transfer=500000.00").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LimitsProperties.class).full().monthlyTransfer().amountMinor())
                    .isEqualTo(50_000_000);
        });
    }

    @Test
    void moreThanTwoDecimalsFailsStartupInsteadOfRounding() {
        runner.withPropertyValues("trustledger.limits.min.max-balance=10000.005")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void unsafeConfigurationFailsStartup() {
        runner.withPropertyValues("trustledger.limits.min.p2p-allowed=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
