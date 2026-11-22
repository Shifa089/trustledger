package com.trustledger.core.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.application.LimitsProperties.TierLimits;
import org.junit.jupiter.api.Test;

/** The configuration fingerprint in the cache key: new limits after a redeploy must never be served stale. */
class TierLimitsCatalogTest {

    private static final TierLimits MIN = new TierLimits(Money.parse("10000.00"), Money.parse("10000.00"), false, null);

    private static LimitsProperties limits(String fullMaxBalance) {
        return new LimitsProperties(Money.parse("50000.00"), MIN, new TierLimits(Money.parse(fullMaxBalance),
                Money.parse("200000.00"), true, Money.parse("100000.00")));
    }

    @Test
    void returnsTheTiersLimits() {
        TierLimitsCatalog catalog = new TierLimitsCatalog(limits("200000.00"));

        assertThat(catalog.forTier(WalletTier.FULL).maxBalance()).isEqualTo(Money.parse("200000.00"));
        assertThat(catalog.forTier(WalletTier.MIN).p2pAllowed()).isFalse();
    }

    @Test
    void equalConfigurationsHaveTheSameFingerprint() {
        assertThat(new TierLimitsCatalog(limits("200000.00")).fingerprint())
                .isEqualTo(new TierLimitsCatalog(limits("200000.00")).fingerprint())
                .hasSize(16);
    }

    @Test
    void changedLimitsChangeTheFingerprintAndSoTheCacheKey() {
        assertThat(new TierLimitsCatalog(limits("200000.00")).fingerprint())
                .isNotEqualTo(new TierLimitsCatalog(limits("150000.00")).fingerprint());
    }
}
