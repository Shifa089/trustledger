package com.trustledger.core.wallet;

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
import java.time.LocalDate;
import java.util.Arrays;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FR-W-1 end to end: KYC approval publishes {@link KycLevelUpgraded}; the wallet module's listener opens or
 * upgrades the wallet after the KYC transaction commits, and publishes {@link WalletCreated}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@EnableScenarios
@RecordApplicationEvents
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

    @Test
    void theSameEventTwiceLeavesOneWalletAndOneWalletCreated(ApplicationEvents events) {
        UUID userId = fixtures.user();

        publishCommitted(new KycLevelUpgraded(userId, KycLevel.MIN));
        UUID first = wallets.findByUser(userId).orElseThrow().walletId();
        publishCommitted(new KycLevelUpgraded(userId, KycLevel.MIN));

        assertThat(wallets.findByUser(userId).orElseThrow().walletId()).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from wallet.wallets where user_id = ?", Integer.class, userId))
                .isEqualTo(1);
        assertThat(events.stream(WalletCreated.class).filter(e -> e.userId().equals(userId))).hasSize(1);
    }

    @Test
    void fullKycApprovalUpgradesTheWalletKeepingItsId(Scenario scenario) {
        UUID userId = fixtures.user();
        minKyc.submit(minCommand(userId));
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
        publishCommitted(new KycLevelUpgraded(userId, KycLevel.FULL));

        publishCommitted(new KycLevelUpgraded(userId, KycLevel.MIN));

        assertThat(wallets.findByUser(userId).orElseThrow().tier()).isEqualTo(WalletTier.FULL);
    }

    @Test
    void eventRolledBackWithItsTransactionCreatesNoWallet() {
        UUID userId = fixtures.user();

        tx.executeWithoutResult(status -> {
            publisher.publishEvent(new KycLevelUpgraded(userId, KycLevel.MIN));
            status.setRollbackOnly();
        });

        assertThat(wallets.findByUser(userId)).isEmpty();
    }

    /** {@code @ApplicationModuleListener} only reacts after a commit, as in production. */
    private void publishCommitted(KycLevelUpgraded event) {
        tx.executeWithoutResult(status -> publisher.publishEvent(event));
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
