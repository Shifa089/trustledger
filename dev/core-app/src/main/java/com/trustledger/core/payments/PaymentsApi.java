package com.trustledger.core.payments;

import java.util.List;
import java.util.UUID;

/**
 * The payments module's public read API (Spring Modulith: only types in this base package are visible to other
 * modules). The {@code Transfer} and {@code Topup} entities never leave the module.
 */
public interface PaymentsApi {

    /**
     * Every transfer the wallet sent or received, oldest first, in any status (held, rejected and failed ones
     * too), for the owner's data export (FR-PR-2). Empty for an unknown wallet.
     */
    List<TransferRecord> transfersOf(UUID walletId);

    /** Every top-up of the wallet, oldest first, in any status, for the owner's data export (FR-PR-2). */
    List<TopupRecord> topupsOf(UUID walletId);

    /**
     * Whether money may still move to or from the wallet: a transfer it sends or receives that is not finished
     * (INITIATED or HELD_FOR_REVIEW), or a top-up that may still be paid (CREATED or PENDING). Erasure (FR-PR-2) waits
     * for these, so it never closes a wallet that money is about to reach.
     */
    boolean hasMoneyInFlight(UUID walletId);
}
