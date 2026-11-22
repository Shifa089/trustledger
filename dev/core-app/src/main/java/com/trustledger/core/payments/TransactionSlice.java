package com.trustledger.core.payments;

import java.util.List;

/**
 * One slice of a history read by cursor (keyset), newest first. No total count: counting a long history is exactly
 * the cost keyset pagination avoids.
 *
 * @param hasNext whether older rows exist after the last item
 */
public record TransactionSlice(List<WalletTransactionRecord> items, boolean hasNext) {

    public TransactionSlice {
        items = List.copyOf(items);
    }
}
