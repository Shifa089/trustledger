package com.trustledger.aml.rules.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Port for the rules' windowed counts over {@code aml.transaction_facts}. Every window is "{@code occurred_at >=
 * since}" with no upper bound, so a fact stamped a little ahead of this service's clock is still counted.
 */
public interface FactWindowQueries {

    /** Outgoing transfers of the wallet since {@code since} (R1). */
    long countOutgoingTransfersSince(UUID walletId, Instant since);

    /**
     * Facts the wallet's owner initiated (outgoing transfers and top-ups) with an amount in
     * [{@code minAmountMinor}, {@code maxAmountMinor}], both ends included, since {@code since} (R2).
     */
    long countInitiatedInAmountRangeSince(UUID walletId, long minAmountMinor, long maxAmountMinor, Instant since);

    /** Distinct receiver wallets of the wallet's outgoing transfers since {@code since} (R4). */
    long countDistinctRecipientsSince(UUID walletId, Instant since);
}
