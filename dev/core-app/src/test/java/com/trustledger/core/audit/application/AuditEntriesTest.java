package com.trustledger.core.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.audit.domain.AuditAction;
import com.trustledger.core.audit.domain.AuditEntityType;
import com.trustledger.core.audit.domain.AuditEntry;
import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.kyc.KycLevelUpgraded;
import com.trustledger.core.payments.TopupCaptured;
import com.trustledger.core.payments.TransferCompleted;
import com.trustledger.core.shared.EventMetadata;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.FreezeSource;
import com.trustledger.core.wallet.WalletCreated;
import com.trustledger.core.wallet.WalletFrozen;
import com.trustledger.core.wallet.WalletUnfrozen;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Every public domain event maps to one audit entry with what the event proves, and nothing personal. */
class AuditEntriesTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();
    private final UUID d = UUID.randomUUID();
    private final EventMetadata metadata = new EventMetadata(UUID.randomUUID(), "corr");

    private static void is(AuditEntry entry, AuditAction action, AuditEntityType type, UUID id) {
        assertThat(entry.action()).isEqualTo(action);
        assertThat(entry.entityType()).isEqualTo(type);
        assertThat(entry.entityId()).isEqualTo(id);
    }

    @Test
    void kycLevelUpgraded() {
        AuditEntry entry = AuditEntries.of(new KycLevelUpgraded(a, KycLevel.FULL));

        is(entry, AuditAction.KYC_LEVEL_UPGRADED, AuditEntityType.USER, a);
        assertThat(entry.before()).isNull();
        assertThat(entry.after()).isEqualTo(Map.of("kycLevel", "FULL"));
    }

    @Test
    void walletCreated() {
        AuditEntry entry = AuditEntries.of(new WalletCreated(a, b));

        is(entry, AuditAction.WALLET_CREATED, AuditEntityType.WALLET, a);
        assertThat(entry.before()).isNull();
        assertThat(entry.after()).isEqualTo(Map.of("userId", b.toString(), "status", "ACTIVE"));
    }

    @Test
    void walletFrozen() {
        AuditEntry entry = AuditEntries.of(new WalletFrozen(a, b, FreezeSource.AML, Instant.EPOCH, metadata));

        is(entry, AuditAction.WALLET_FROZEN, AuditEntityType.WALLET, a);
        assertThat(entry.before()).isEqualTo(Map.of("status", "ACTIVE"));
        assertThat(entry.after()).isEqualTo(Map.of("status", "FROZEN", "freezeSource", "AML"));
    }

    @Test
    void walletUnfrozen() {
        AuditEntry entry = AuditEntries.of(new WalletUnfrozen(a, b));

        is(entry, AuditAction.WALLET_UNFROZEN, AuditEntityType.WALLET, a);
        assertThat(entry.before()).isEqualTo(Map.of("status", "FROZEN"));
        assertThat(entry.after()).isEqualTo(Map.of("status", "ACTIVE"));
    }

    @Test
    void topupCaptured() {
        AuditEntry entry = AuditEntries.of(new TopupCaptured(a, b, Money.of(150_050, Money.INR), c, Instant.EPOCH,
                metadata));

        is(entry, AuditAction.TOPUP_CAPTURED, AuditEntityType.TOPUP, a);
        assertThat(entry.before()).isNull();
        assertThat(entry.after()).isEqualTo(Map.of("status", "CAPTURED", "walletId", b.toString(),
                "amount", "1500.50", "currency", "INR", "journalEntryId", c.toString()));
    }

    @Test
    void transferCompleted() {
        AuditEntry entry = AuditEntries.of(new TransferCompleted(a, b, c, Money.of(5, Money.INR), d, Instant.EPOCH,
                metadata));

        is(entry, AuditAction.TRANSFER_COMPLETED, AuditEntityType.TRANSFER, a);
        assertThat(entry.before()).isNull();
        assertThat(entry.after()).isEqualTo(Map.of("status", "COMPLETED", "senderWalletId", b.toString(),
                "receiverWalletId", c.toString(), "amount", "0.05", "currency", "INR",
                "journalEntryId", d.toString()));
    }
}
