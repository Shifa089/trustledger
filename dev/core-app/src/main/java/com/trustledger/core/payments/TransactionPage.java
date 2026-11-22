package com.trustledger.core.payments;

import java.util.List;

/** One page of a history read by offset (FR-S-1). An explicit record, not Spring Data's {@code Page}. */
public record TransactionPage(List<WalletTransactionRecord> items, int page, int size, long totalElements,
        int totalPages) {

    public TransactionPage {
        items = List.copyOf(items);
    }
}
