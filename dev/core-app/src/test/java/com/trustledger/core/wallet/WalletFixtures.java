package com.trustledger.core.wallet;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.identity.application.TokenIdentity;
import com.trustledger.core.identity.application.UserProvisioningService;
import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.application.WalletProvisioningService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test support: users, wallets and money for wallet ITs, through the real services (no SQL shortcuts), so
 * every wallet in a test went through the same invariants as in production.
 */
@Component
class WalletFixtures {

    private final UserProvisioningService users;
    private final WalletProvisioningService provisioning;
    private final WalletApi api;
    private final TransactionTemplate tx;

    WalletFixtures(UserProvisioningService users, WalletProvisioningService provisioning, WalletApi api,
            TransactionTemplate tx) {
        this.users = users;
        this.provisioning = provisioning;
        this.api = api;
        this.tx = tx;
    }

    /** Provisions the user for a token subject, as the JIT filter would on their first request. */
    UUID user(String subject) {
        return users.ensureUser(new TokenIdentity(subject, null, "Wallet Test User"));
    }

    UUID user() {
        return user(ApiMockMvc.newSubject());
    }

    /** Opens (or upgrades) the user's wallet as the KycLevelUpgraded listener would. */
    UUID wallet(UUID userId, WalletTier tier) {
        provisioning.apply(userId, tier == WalletTier.FULL ? KycLevel.FULL : KycLevel.MIN);
        return api.findByUser(userId).orElseThrow().walletId();
    }

    UUID wallet(WalletTier tier) {
        return wallet(user(), tier);
    }

    /** Credits through the API, in its own transaction, with the lock taken first. */
    WalletView fund(UUID walletId, long minor) {
        return tx.execute(status -> {
            api.lockForUpdate(List.of(walletId));
            return api.credit(walletId, Money.of(minor, Money.INR));
        });
    }

    WalletView hold(UUID walletId, long minor) {
        return tx.execute(status -> {
            api.lockForUpdate(List.of(walletId));
            return api.hold(walletId, Money.of(minor, Money.INR));
        });
    }
}
