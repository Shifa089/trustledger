package com.trustledger.core.shared.perf;

import com.trustledger.core.shared.Money;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code trustledger.perf.seed.*} (profile {@code perf} only, M13).
 *
 * @param wallets        how many FULL-tier wallets to seed, 2..{@link PerfSeedIds#MAX_WALLETS} (a transfer needs two)
 * @param openingBalance each new wallet's balance, posted to the ledger as an opening entry; must be positive
 */
@ConfigurationProperties("trustledger.perf.seed")
public record PerfSeedProperties(int wallets, Money openingBalance) {

    public PerfSeedProperties {
        if (wallets < 2 || wallets > PerfSeedIds.MAX_WALLETS) {
            throw new IllegalArgumentException(
                    "trustledger.perf.seed.wallets must be between 2 and " + PerfSeedIds.MAX_WALLETS);
        }
        if (openingBalance == null || openingBalance.amountMinor() <= 0 || !openingBalance.currency().equals(Money.INR)) {
            throw new IllegalArgumentException("trustledger.perf.seed.opening-balance must be a positive INR amount");
        }
    }
}
