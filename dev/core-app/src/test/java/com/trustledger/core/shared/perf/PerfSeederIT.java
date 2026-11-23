package com.trustledger.core.shared.perf;

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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;

/**
 * M13 load-test data against the real schema (Flyway, constraints, the deferred balanced-entry trigger). Uses the
 * shared test context (the "perf" profile isn't active, so nothing is seeded at startup) and seeds 5 wallets, not
 * 200, to keep the shared database small.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PerfSeederIT {

    private static final int COUNT = 5;
    private static final Money OPENING = Money.parse("100000.00");

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

    PerfSeeder seeder;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        seeder = new PerfSeeder(jdbc, transactions, clock);
        mvc = ApiMockMvc.create(context);
    }

    @Test
    @Order(1)
    void seedsFullActiveWalletsWhoseBalanceMatchesTheLedger() {
        assertThat(seeder.seed(COUNT, OPENING)).isEqualTo(COUNT);

        for (int n = 1; n <= COUNT; n++) {
            WalletView wallet = wallets.findById(PerfSeedIds.wallet(n)).orElseThrow();
            assertThat(wallet.userId()).isEqualTo(PerfSeedIds.user(n));
            assertThat(wallet.tier()).isEqualTo(WalletTier.FULL);
            assertThat(wallet.status()).isEqualTo(WalletStatus.ACTIVE);
            assertThat(wallet.balance()).isEqualTo(OPENING);
            assertBalanceMatchesLedger(n);
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM kyc.kyc_cases
                 WHERE user_id = ? AND status = 'APPROVED' AND level IN ('MIN', 'FULL')""",
                Long.class, PerfSeedIds.user(1))).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ledger.journal_entries WHERE reference_type = ? AND reference_id = ?""",
                Long.class, PerfSeeder.REFERENCE_TYPE, PerfSeedIds.wallet(1))).isEqualTo(1);
    }

    @Test
    @Order(2)
    void aSeededUserSendsMoneyThroughTheApiAndASecondSeedChangesNothing() throws Exception {
        seeder.seed(COUNT, OPENING);
        Money senderBefore = wallets.findById(PerfSeedIds.wallet(1)).orElseThrow().balance();

        // The token's sub is the seeded subject: JIT provisioning finds the seeded user and its seeded wallet.
        mvc.perform(get("/api/v1/wallets/me").with(customer(PerfSeedIds.subject(1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(PerfSeedIds.wallet(1).toString()));
        mvc.perform(post("/api/v1/transfers").with(customer(PerfSeedIds.subject(1)))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientWalletId\":\"" + PerfSeedIds.wallet(2) + "\",\"amount\":\"12.34\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        Money senderAfter = senderBefore.minus(Money.parse("12.34"));
        assertThat(wallets.findById(PerfSeedIds.wallet(1)).orElseThrow().balance()).isEqualTo(senderAfter);

        // A restart of the perf profile: nothing created, the moved balance kept, no second opening entry.
        assertThat(seeder.seed(COUNT, OPENING)).isZero();
        assertThat(wallets.findById(PerfSeedIds.wallet(1)).orElseThrow().balance()).isEqualTo(senderAfter);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ledger.journal_entries WHERE reference_type = ? AND reference_id = ?""",
                Long.class, PerfSeeder.REFERENCE_TYPE, PerfSeedIds.wallet(1))).isEqualTo(1);
        for (int n = 1; n <= COUNT; n++) {
            assertBalanceMatchesLedger(n);
        }
    }

    @Test
    @Order(3)
    void seedingMoreWalletsLaterAddsOnlyTheNewOnes() {
        seeder.seed(COUNT, OPENING);
        assertThat(seeder.seed(COUNT + 1, OPENING)).isEqualTo(1);
        assertBalanceMatchesLedger(COUNT + 1);
    }

    private void assertBalanceMatchesLedger(int n) {
        UUID walletId = PerfSeedIds.wallet(n);
        assertThat(ledger.balanceOf(AccountCodes.wallet(walletId)).toMoney())
                .isEqualTo(wallets.findById(walletId).orElseThrow().balance());
    }
}
