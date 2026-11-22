package com.trustledger.core.wallet.application;

import com.trustledger.core.shared.cache.CacheNames;
import com.trustledger.core.wallet.WalletLimits;
import com.trustledger.core.wallet.WalletTier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * The limits of a tier (FR-W-2), cached in Redis ({@link CacheNames#WALLET_LIMITS}, M11, ADR 0019).
 * <p>
 * The limits come from {@code trustledger.limits.*}, so they change only with a redeploy. A plain TTL would then keep
 * serving the <em>old</em> limits from Redis after a deploy with new ones, until the entries expire. So the cache key
 * carries a {@link #fingerprint()} of the configuration: new limits mean new keys, and the old entries simply age
 * out.
 * <p>
 * Used only on read paths that hold no locks ({@code GET /wallets/me}). Money movement takes the limits from
 * {@link WalletApiImpl#limitsFor} inside its transaction, under the wallet locks, without a Redis round trip
 * (CLAUDE.md: no remote calls while holding locks). These are limits, not balances: balances are never cached.
 */
@Service
public class TierLimitsCatalog {

    private final LimitsProperties limits;
    private final String fingerprint;

    public TierLimitsCatalog(LimitsProperties limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.fingerprint = fingerprintOf(limits);
    }

    @Cacheable(cacheNames = CacheNames.WALLET_LIMITS, key = "#tier.name() + ':' + #root.target.fingerprint()")
    public WalletLimits forTier(WalletTier tier) {
        Objects.requireNonNull(tier, "tier");
        return limits.forTier(tier);
    }

    /** First 16 hex characters of the SHA-256 of the limits configuration; equal configurations, equal prints. */
    public String fingerprint() {
        return fingerprint;
    }

    static String fingerprintOf(LimitsProperties limits) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(limits.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
