package com.trustledger.core.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.ledger.AccountCodes;
import com.trustledger.core.ledger.AccountType;
import com.trustledger.core.ledger.Direction;
import com.trustledger.core.ledger.LedgerApi;
import com.trustledger.core.ledger.application.LedgerAccountDirectory;
import com.trustledger.core.ledger.application.LedgerAccountRef;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.WalletLimits;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.application.TierLimitsCatalog;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * M11 / ADR 0019 against real Redis: the two reference-data caches work (with TTLs and readable JSON), a missing
 * ledger account is not remembered, and no other cache can be created.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisCacheIT {

    @Autowired
    CacheManager caches;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    LedgerAccountDirectory directory;

    @Autowired
    LedgerApi ledger;

    @Autowired
    TierLimitsCatalog limits;

    @Test
    void onlyTheTwoReferenceDataCachesExist() {
        assertThat(caches.getCacheNames()).containsExactlyInAnyOrder(CacheNames.WALLET_LIMITS,
                CacheNames.LEDGER_ACCOUNTS);
        assertThat(caches.getCache("wallet-balances")).isNull();
        assertThat(caches.getCache("balances")).isNull();
    }

    /**
     * A freshly opened account, so no earlier test in the shared context (and shared Redis) can have touched its
     * entry: the first lookup is a guaranteed miss followed by a put.
     */
    @Test
    void aLedgerAccountLookupIsCachedWithItsTtl() {
        UUID walletId = UUID.randomUUID();
        UUID accountId = ledger.openWalletAccount(walletId, Money.INR);
        String code = AccountCodes.wallet(walletId);
        String key = "tl:cache:ledger-accounts::" + code;
        Cache cache = caches.getCache(CacheNames.LEDGER_ACCOUNTS);
        assertThat(cache.get(code)).as("nothing cached before the first lookup").isNull();

        LedgerAccountRef first = directory.findByCode(code).orElseThrow();

        assertThat(first.id()).isEqualTo(accountId);
        assertThat(first.type()).isEqualTo(AccountType.LIABILITY);
        assertThat(first.normalSide()).isEqualTo(Direction.CREDIT);
        Cache.ValueWrapper cached = cache.get(code);
        assertThat(cached).as("cache entry after the lookup; keys now: %s", redis.keys("tl:cache:*")).isNotNull();
        assertThat(cached.get()).isEqualTo(first);
        assertThat(redis.opsForValue().get(key)).contains(accountId.toString()).doesNotContainIgnoringCase("balance");
        assertThat(redis.getExpire(key)).isPositive().isLessThanOrEqualTo(Duration.ofHours(24).toSeconds());
        assertThat(directory.findByCode(code)).contains(first);
    }

    @Test
    void aPlatformAccountIsServedFromTheCacheAfterAnImmediateEviction() {
        String code = AccountCodes.GATEWAY_CLEARING;
        Cache cache = caches.getCache(CacheNames.LEDGER_ACCOUNTS);
        cache.evictIfPresent(code); // immediate, unlike evict(), which a cache may defer

        LedgerAccountRef clearing = directory.findByCode(code).orElseThrow();

        assertThat(clearing.type()).isEqualTo(AccountType.ASSET);
        assertThat(clearing.normalSide()).isEqualTo(Direction.DEBIT);
        assertThat(cache.get(code)).as("keys now: %s", redis.keys("tl:cache:*")).isNotNull();
    }

    /** No negative caching: an account opened right after a miss is found at once. */
    @Test
    void aMissingAccountIsNotCached() {
        UUID walletId = UUID.randomUUID();
        String code = AccountCodes.wallet(walletId);

        assertThat(directory.findByCode(code)).isEmpty();
        assertThat(redis.hasKey("tl:cache:ledger-accounts::" + code)).isFalse();

        UUID accountId = ledger.openWalletAccount(walletId, Money.INR);

        assertThat(directory.findByCode(code)).map(LedgerAccountRef::id).contains(accountId);
    }

    @Test
    void tierLimitsAreCachedUnderTheConfigurationFingerprint() {
        String cacheKey = "FULL:" + limits.fingerprint();
        String key = "tl:cache:wallet-limits::" + cacheKey;
        Cache cache = caches.getCache(CacheNames.WALLET_LIMITS);
        cache.evictIfPresent(cacheKey); // immediate, unlike evict()

        WalletLimits full = limits.forTier(WalletTier.FULL);

        assertThat(cache.get(cacheKey)).as("keys now: %s", redis.keys("tl:cache:*")).isNotNull();
        assertThat(cache.get(cacheKey).get()).isEqualTo(full);
        assertThat(redis.hasKey(key)).isTrue();
        assertThat(redis.getExpire(key)).isPositive().isLessThanOrEqualTo(Duration.ofHours(1).toSeconds());
        assertThat(limits.forTier(WalletTier.FULL)).isEqualTo(full);
        assertThat(full.maxBalance()).isEqualTo(Money.parse("200000.00"));
    }
}
