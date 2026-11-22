package com.trustledger.core.shared.cache;

import static com.trustledger.core.ApiMockMvc.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.identity.application.TokenIdentity;
import com.trustledger.core.identity.application.UserProvisioningService;
import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.ledger.AccountCodes;
import com.trustledger.core.ledger.EntryType;
import com.trustledger.core.ledger.LedgerApi;
import com.trustledger.core.ledger.PostingCommand;
import com.trustledger.core.ledger.PostingLine;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.application.WalletLedgerLinkService;
import com.trustledger.core.wallet.application.WalletProvisioningService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M11 / ADR 0019 at runtime: <b>balances are never cached</b>. Every read path that shows a balance is read, money
 * moves, and the next read shows the new balance at once; Redis holds no balance afterwards. The build-time half is
 * {@link NoBalanceCachingTest}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BalanceNotCachedIT {

    @Autowired
    WebApplicationContext context;

    @Autowired
    UserProvisioningService users;

    @Autowired
    WalletProvisioningService provisioning;

    @Autowired
    WalletLedgerLinkService links;

    @Autowired
    WalletApi wallets;

    @Autowired
    LedgerApi ledger;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    StringRedisTemplate redis;

    /** A top-up as settlement books it: GATEWAY_CLEARING -> WALLET plus the wallet credit, in one transaction. */
    private void topUp(UUID walletId, long minor) {
        Money amount = Money.of(minor, Money.INR);
        tx.executeWithoutResult(status -> {
            ledger.post(PostingCommand.of(EntryType.TOPUP, "TOPUP", UUID.randomUUID(), List.of(
                    PostingLine.debit(AccountCodes.GATEWAY_CLEARING, amount),
                    PostingLine.credit(AccountCodes.wallet(walletId), amount))));
            wallets.lockForUpdate(List.of(walletId));
            wallets.credit(walletId, amount);
        });
    }

    @Test
    void theWalletAndLedgerBalancesChangeOnTheVeryNextRead() throws Exception {
        MockMvc mvc = ApiMockMvc.create(context);
        String subject = ApiMockMvc.newSubject();
        UUID userId = users.ensureUser(new TokenIdentity(subject, null, "Cache Test User"));
        provisioning.apply(userId, KycLevel.MIN);
        UUID walletId = wallets.findByUser(userId).orElseThrow().walletId();
        links.link(walletId);
        String walletCode = AccountCodes.wallet(walletId);

        mvc.perform(get("/api/v1/wallets/me").with(customer(subject))).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value("0.00"));
        assertThat(ledger.balanceOf(walletCode).balanceMinor()).isZero();
        long clearingBefore = ledger.balanceOf(AccountCodes.GATEWAY_CLEARING).balanceMinor();

        topUp(walletId, 150_000);

        // GET /wallets/me went through the limits cache, the balances through the account cache: neither is stale.
        mvc.perform(get("/api/v1/wallets/me").with(customer(subject))).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value("1500.00"))
                .andExpect(jsonPath("$.available").value("1500.00"));
        assertThat(ledger.balanceOf(walletCode).balanceMinor()).isEqualTo(150_000);
        assertThat(ledger.balanceOf(AccountCodes.GATEWAY_CLEARING).balanceMinor())
                .isEqualTo(clearingBefore + 150_000);
        assertThat(ledger.balancesOf(List.of(walletCode)).get(walletCode).balanceMinor()).isEqualTo(150_000);

        topUp(walletId, 50);

        mvc.perform(get("/api/v1/wallets/me").with(customer(subject)))
                .andExpect(jsonPath("$.balance").value("1500.50"));
        assertThat(ledger.balanceOf(walletCode).balanceMinor()).isEqualTo(150_050);

        // The reads above did fill the caches, and nothing in them is a balance. (A tier's "maxBalance" is a limit
        // from configuration, not a balance; the balance-bearing fields are AccountBalance's and the wallet views'.)
        Set<String> keys = redis.keys("tl:cache:*");
        assertThat(keys).contains("tl:cache:ledger-accounts::" + walletCode)
                .allMatch(key -> key.startsWith("tl:cache:wallet-limits::")
                        || key.startsWith("tl:cache:ledger-accounts::"))
                .noneMatch(key -> key.toLowerCase().contains("balance"));
        for (String key : keys) {
            assertThat(redis.opsForValue().get(key))
                    .doesNotContain("\"balanceMinor\"", "\"balance\"", "\"held\"", "\"available\"")
                    .doesNotContain("150000").doesNotContain("150050");
        }
    }
}
