package com.trustledger.core.wallet;

import static com.trustledger.core.Eventually.eventually;
import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.kyc.KycLevelUpgraded;
import com.trustledger.core.kyc.application.FullKycService;
import com.trustledger.core.kyc.application.KycDocumentService;
import com.trustledger.core.kyc.application.KycReviewService;
import com.trustledger.core.kyc.application.MinKycCommand;
import com.trustledger.core.kyc.application.MinKycService;
import com.trustledger.core.kyc.domain.DocumentType;
import com.trustledger.core.ledger.AccountCodes;
import com.trustledger.core.ledger.LedgerApi;
import com.trustledger.core.wallet.application.WalletLedgerLinkService;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FR-W-1 end to end: KYC approval publishes {@link KycLevelUpgraded}; the wallet module's listener opens or
 * upgrades the wallet after the KYC transaction commits, and publishes {@link WalletCreated}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@EnableScenarios
class WalletCreationScenarioIT {

    /** Fake documents with the right magic bytes; never real documents. */
    private static final byte[] PNG = fake(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
    private static final byte[] PDF = fake(new byte[] {0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37, 0x0A});

    @Autowired
    WalletFixtures fixtures;

    @Autowired
    WalletApi wallets;

    @Autowired
    MinKycService minKyc;

    @Autowired
    FullKycService fullKyc;

    @Autowired
    KycDocumentService documents;

    @Autowired
    KycReviewService review;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LedgerApi ledger;

    @Autowired
    WalletLedgerLinkService links;

    @Test
    void minimumKycOpensAnEmptyMinWalletAndPublishesWalletCreated(Scenario scenario) {
        UUID userId = fixtures.user();

        scenario.stimulate(() -> minKyc.submit(minCommand(userId)))
                .andWaitForEventOfType(WalletCreated.class)
                .matching(event -> event.userId().equals(userId))
                .toArriveAndVerify(event -> {
                    WalletView wallet = wallets.findByUser(userId).orElseThrow();
                    assertThat(wallet.walletId()).isEqualTo(event.walletId());
                    assertThat(wallet.tier()).isEqualTo(WalletTier.MIN);
                    assertThat(wallet.status()).isEqualTo(WalletStatus.ACTIVE);
                    assertThat(wallet.balance().amountMinor()).isZero();
                });
    }

    /** M5: WalletCreated → the wallet module opens WALLET:<id> through LedgerApi and stores the account's ID. */
    @Test
    void newWalletGetsItsLedgerAccount(Scenario scenario) {
        UUID userId = fixtures.user();

        scenario.stimulate(() -> minKyc.submit(minCommand(userId)))
                .andWaitForStateChange(() -> ledgerAccountOf(userId), Objects::nonNull)
                .andVerify(accountId -> {
                    UUID walletId = wallets.findByUser(userId).orElseThrow().walletId();
                    Map<String, Object> account = jdbc.queryForMap(
                            "select code, type, normal_side, currency from ledger.ledger_accounts where id = ?",
                            accountId);
                    assertThat(account).containsEntry("code", AccountCodes.wallet(walletId))
                            .containsEntry("type", "LIABILITY").containsEntry("normal_side", "CREDIT")
                            .containsEntry("currency", "INR");
                    assertThat(ledger.balanceOf(AccountCodes.wallet(walletId)).balanceMinor()).isZero();
                });
    }

    @Test
    void linkingAgainKeepsOneLedgerAccount() {
        UUID walletId = fixtures.wallet(WalletTier.MIN);
        // The WalletCreated listener links the account asynchronously (M8): wait for the first link.
        eventually(() -> assertThat(ledgerAccountOfWallet(walletId)).isNotNull());
        UUID linked = ledgerAccountOfWallet(walletId);

        assertThat(links.link(walletId)).isEqualTo(linked).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from ledger.ledger_accounts where code = ?", Integer.class,
                AccountCodes.wallet(walletId))).isEqualTo(1);
    }

    private UUID ledgerAccountOfWallet(UUID walletId) {
        return jdbc.queryForObject("select ledger_account_id from wallet.wallets where id = ?", UUID.class, walletId);
    }

    private UUID ledgerAccountOf(UUID userId) {
        return jdbc.queryForList("select ledger_account_id from wallet.wallets where user_id = ?", UUID.class, userId)
                .stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    @Test
    void theSameEventTwiceLeavesOneWalletAndOneWalletCreated() {
        UUID userId = fixtures.user();

        publishCommittedAndWait(new KycLevelUpgraded(userId, KycLevel.MIN));
        UUID first = wallets.findByUser(userId).orElseThrow().walletId();
        publishCommittedAndWait(new KycLevelUpgraded(userId, KycLevel.MIN));

        assertThat(wallets.findByUser(userId).orElseThrow().walletId()).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from wallet.wallets where user_id = ?", Integer.class, userId))
                .isEqualTo(1);
        // WalletCreated is published on the listener's thread now (M8), which ApplicationEvents (thread-bound) doesn't
        // see. The outbox does: one WalletCreated publication per listener of it, i.e. it was published once.
        assertThat(jdbc.queryForObject("""
                select count(distinct serialized_event) from shared.event_publication
                where event_type = ? and serialized_event like ?
                """, Integer.class, WalletCreated.class.getName(), "%" + userId + "%")).isEqualTo(1);
    }

    @Test
    void fullKycApprovalUpgradesTheWalletKeepingItsId(Scenario scenario) {
        UUID userId = fixtures.user();
        minKyc.submit(minCommand(userId));
        awaitListenersFor(userId);
        UUID walletId = wallets.findByUser(userId).orElseThrow().walletId();
        UUID caseId = fullKyc.submit(userId, "12 Wallet Street, Pune").id();
        documents.upload(userId, caseId, DocumentType.PAN_CARD, PNG);
        documents.upload(userId, caseId, DocumentType.ADDRESS_PROOF, PDF);
        review.verify(caseId, fixtures.user());

        scenario.stimulate(() -> review.approve(caseId, fixtures.user()))
                .andWaitForStateChange(() -> wallets.findByUser(userId).orElseThrow().tier(),
                        tier -> tier == WalletTier.FULL)
                .andVerify(tier -> {
                    assertThat(wallets.findByUser(userId).orElseThrow().walletId()).isEqualTo(walletId);
                    assertThat(wallets.limitsFor(walletId).p2pAllowed()).isTrue();
                });
    }

    @Test
    void lateMinEventNeverDowngradesAFullWallet() {
        UUID userId = fixtures.user();
        publishCommittedAndWait(new KycLevelUpgraded(userId, KycLevel.FULL));

        publishCommittedAndWait(new KycLevelUpgraded(userId, KycLevel.MIN));

        assertThat(wallets.findByUser(userId).orElseThrow().tier()).isEqualTo(WalletTier.FULL);
    }

    @Test
    void eventRolledBackWithItsTransactionCreatesNoWallet() {
        UUID userId = fixtures.user();

        tx.executeWithoutResult(status -> {
            publisher.publishEvent(new KycLevelUpgraded(userId, KycLevel.MIN));
            status.setRollbackOnly();
        });

        // Listeners are asynchronous (M8), so "no wallet yet" alone proves little; the outbox proves it: the
        // rolled-back event left no publication, so no listener will ever run for it.
        assertThat(publicationsFor(userId)).isZero();
        assertThat(wallets.findByUser(userId)).isEmpty();
    }

    /** {@code @ApplicationModuleListener} only reacts after a commit, as in production. */
    private void publishCommittedAndWait(KycLevelUpgraded event) {
        tx.executeWithoutResult(status -> publisher.publishEvent(event));
        awaitListenersFor(event.userId());
    }

    /**
     * Since M8 module listeners run asynchronously after the commit. Waits until every publication that mentions the
     * user (KycLevelUpgraded, then the WalletCreated it causes) is completed. The chain can't look finished early:
     * the WalletCreated row is committed before KycLevelUpgraded is marked completed.
     */
    private void awaitListenersFor(UUID userId) {
        eventually(() -> assertThat(jdbc.queryForObject("""
                select count(*) from shared.event_publication
                where completion_date is null and serialized_event like ?
                """, Integer.class, "%" + userId + "%")).isZero());
    }

    private int publicationsFor(UUID userId) {
        return jdbc.queryForObject("select count(*) from shared.event_publication where serialized_event like ?",
                Integer.class, "%" + userId + "%");
    }

    private static MinKycCommand minCommand(UUID userId) {
        String phone = "6" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
        return new MinKycCommand(userId, "Wallet Scenario User", LocalDate.of(1990, 1, 1), phone, "ABCPE1234F");
    }

    private static byte[] fake(byte[] head) {
        byte[] out = Arrays.copyOf(head, 64);
        Arrays.fill(out, head.length, out.length, (byte) 'x');
        return out;
    }
}
