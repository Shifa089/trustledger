package com.trustledger.core.payments.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** The key of a {@link WalletTransaction} row: a transfer appears once for its sender and once for its receiver. */
public class WalletTransactionId implements Serializable {

    private UUID walletId;
    private UUID sourceId;

    protected WalletTransactionId() {
    }

    public WalletTransactionId(UUID walletId, UUID sourceId) {
        this.walletId = walletId;
        this.sourceId = sourceId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WalletTransactionId that && Objects.equals(walletId, that.walletId)
                && Objects.equals(sourceId, that.sourceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(walletId, sourceId);
    }
}
