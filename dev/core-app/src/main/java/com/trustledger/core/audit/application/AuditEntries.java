package com.trustledger.core.audit.application;

import com.trustledger.core.audit.domain.AuditAction;
import com.trustledger.core.audit.domain.AuditEntityType;
import com.trustledger.core.audit.domain.AuditEntry;
import com.trustledger.core.kyc.KycLevelUpgraded;
import com.trustledger.core.payments.TopupCaptured;
import com.trustledger.core.payments.TransferCompleted;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.WalletCreated;
import com.trustledger.core.wallet.WalletFrozen;
import com.trustledger.core.wallet.WalletStatus;
import com.trustledger.core.wallet.WalletUnfrozen;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps each public domain event to the audit entry it stands for (static factories, ADR 0016). Before/after hold
 * only what the event proves; a state the event does not tell is left out rather than guessed. Amounts are decimal
 * strings (SPEC §6), so the canonical JSON never contains a fraction.
 */
final class AuditEntries {

    private AuditEntries() {
    }

    static AuditEntry of(KycLevelUpgraded event) {
        return new AuditEntry(AuditAction.KYC_LEVEL_UPGRADED, AuditEntityType.USER, event.userId(), null,
                Map.of("kycLevel", event.level().name()));
    }

    static AuditEntry of(WalletCreated event) {
        return new AuditEntry(AuditAction.WALLET_CREATED, AuditEntityType.WALLET, event.walletId(), null,
                Map.of("userId", event.userId().toString(), "status", WalletStatus.ACTIVE.name()));
    }

    static AuditEntry of(WalletFrozen event) {
        return new AuditEntry(AuditAction.WALLET_FROZEN, AuditEntityType.WALLET, event.walletId(),
                Map.of("status", WalletStatus.ACTIVE.name()),
                Map.of("status", WalletStatus.FROZEN.name(), "freezeSource", event.source().name()));
    }

    static AuditEntry of(WalletUnfrozen event) {
        return new AuditEntry(AuditAction.WALLET_UNFROZEN, AuditEntityType.WALLET, event.walletId(),
                Map.of("status", WalletStatus.FROZEN.name()), Map.of("status", WalletStatus.ACTIVE.name()));
    }

    static AuditEntry of(TopupCaptured event) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "CAPTURED");
        after.put("walletId", event.walletId().toString());
        putAmount(after, event.amount());
        after.put("journalEntryId", event.journalEntryId().toString());
        return new AuditEntry(AuditAction.TOPUP_CAPTURED, AuditEntityType.TOPUP, event.topupId(), null, after);
    }

    static AuditEntry of(TransferCompleted event) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "COMPLETED");
        after.put("senderWalletId", event.senderWalletId().toString());
        after.put("receiverWalletId", event.receiverWalletId().toString());
        putAmount(after, event.amount());
        after.put("journalEntryId", event.journalEntryId().toString());
        return new AuditEntry(AuditAction.TRANSFER_COMPLETED, AuditEntityType.TRANSFER, event.transferId(), null,
                after);
    }

    private static void putAmount(Map<String, Object> state, Money amount) {
        state.put("amount", amount.toDecimalString());
        state.put("currency", amount.currency().getCurrencyCode());
    }
}
