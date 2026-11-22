package com.trustledger.core.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CachePropertiesTest {

    private static final Map<String, Duration> VALID = Map.of(
            CacheNames.WALLET_LIMITS, Duration.ofHours(1),
            CacheNames.LEDGER_ACCOUNTS, Duration.ofHours(24));

    @Test
    void acceptsAPositiveTtlForEveryCache() {
        CacheProperties properties = new CacheProperties(VALID);

        assertThat(properties.ttlOf(CacheNames.WALLET_LIMITS)).isEqualTo(Duration.ofHours(1));
        assertThat(properties.ttlOf(CacheNames.LEDGER_ACCOUNTS)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void refusesAMissingTtl() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CacheProperties(Map.of(CacheNames.WALLET_LIMITS, Duration.ofHours(1))))
                .withMessageContaining("trustledger.cache.ttl.ledger-accounts");
        assertThatIllegalArgumentException().isThrownBy(() -> new CacheProperties(null));
    }

    @Test
    void refusesAZeroOrNegativeTtl() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CacheProperties(Map.of(
                CacheNames.WALLET_LIMITS, Duration.ZERO, CacheNames.LEDGER_ACCOUNTS, Duration.ofHours(1))));
        assertThatIllegalArgumentException().isThrownBy(() -> new CacheProperties(Map.of(
                CacheNames.WALLET_LIMITS, Duration.ofHours(1), CacheNames.LEDGER_ACCOUNTS, Duration.ofSeconds(-1))));
    }

    /** A TTL for an unknown name is a typo or an attempt to add a cache (e.g. balances) through configuration. */
    @Test
    void refusesAnUnknownCache() {
        Map<String, Duration> withBalances = new java.util.HashMap<>(VALID);
        withBalances.put("wallet-balances", Duration.ofMinutes(1));

        assertThatIllegalArgumentException().isThrownBy(() -> new CacheProperties(withBalances))
                .withMessageContaining("wallet-balances");
    }
}
