package com.trustledger.core.shared.demo;

import static com.trustledger.core.ApiMockMvc.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.ledger.AccountCodes;
import com.trustledger.core.ledger.LedgerApi;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletStatus;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.WalletView;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;

/**
 * M16 demo data against the real schema (Flyway, constraints, the deferred balanced-entry trigger). Uses the shared
 * test context (the "demo" profile isn't active, so nothing is seeded at startup) and calls the seeder directly.
 * Idempotent, so the test order doesn't matter: every test seeds first.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DemoSeederIT {

    private static final DemoSeedProperties BALANCES =
            new DemoSeedProperties(Money.parse("50000.00"), Money.parse("10000.00"));

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    Clock clock;

    @Autowired
    WalletApi wallets;

    @Autowired
    LedgerApi ledger;

    @Autowired
    WebApplicationContext context;

    DemoSeeder seeder;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        seeder = new DemoSeeder(jdbc, transactions, clock);
        mvc = ApiMockMvc.create(context);
        seeder.seed(BALANCES);
    }

    @Test
    void bothCustomersHaveFullActiveWalletsBackedByTheLedgerAndApprovedFullKyc() {
        for (int n = 1; n <= DemoSeedIds.CUSTOMERS; n++) {
            WalletView wallet = wallets.findById(DemoSeedIds.wallet(n)).orElseThrow();
            assertThat(wallet.userId()).isEqualTo(DemoSeedIds.user(n));
            assertThat(wallet.tier()).isEqualTo(WalletTier.FULL);
            assertThat(wallet.status()).isEqualTo(WalletStatus.ACTIVE);
            assertBalanceMatchesLedger(n);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM kyc.kyc_cases
                     WHERE user_id = ? AND status = 'APPROVED' AND level = 'FULL' AND verified_by <> approved_by""",
                    Long.class, DemoSeedIds.user(n))).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM ledger.journal_entries WHERE reference_type = ? AND reference_id = ?""",
                    Long.class, DemoSeedIds.REFERENCE_TYPE, DemoSeedIds.wallet(n))).isEqualTo(1);
        }
    }

    @Test
    void customer1SendsToCustomer2ThroughTheApiAndASecondSeedKeepsTheMovedBalances() throws Exception {
        Money senderBefore = balance(1);
        Money receiverBefore = balance(2);

        // The token's sub is the seeded subject (the realm file's customer1 ID): JIT provisioning finds the seeded user.
        mvc.perform(get("/api/v1/kyc/me").with(customer(DemoSeedIds.subject(1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentLevel").value("FULL"));
        mvc.perform(get("/api/v1/wallets/me").with(customer(DemoSeedIds.subject(1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(DemoSeedIds.wallet(1).toString()))
                .andExpect(jsonPath("$.tier").value("FULL"));
        mvc.perform(post("/api/v1/transfers").with(customer(DemoSeedIds.subject(1)))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientWalletId\":\"" + DemoSeedIds.wallet(2) + "\",\"amount\":\"250.00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        Money amount = Money.parse("250.00");
        assertThat(balance(1)).isEqualTo(senderBefore.minus(amount));
        assertThat(balance(2)).isEqualTo(receiverBefore.plus(amount));

        // Restarting with the demo profile: nothing created, nothing reset, no second opening entry.
        assertThat(seeder.seed(BALANCES)).isZero();
        assertThat(balance(1)).isEqualTo(senderBefore.minus(amount));
        for (int n = 1; n <= DemoSeedIds.CUSTOMERS; n++) {
            assertBalanceMatchesLedger(n);
        }
    }

    private Money balance(int n) {
        return wallets.findById(DemoSeedIds.wallet(n)).orElseThrow().balance();
    }

    private void assertBalanceMatchesLedger(int n) {
        UUID walletId = DemoSeedIds.wallet(n);
        assertThat(ledger.balanceOf(AccountCodes.wallet(walletId)).toMoney()).isEqualTo(balance(n));
    }
}
