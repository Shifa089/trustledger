package com.trustledger.core.reporting.application;

import com.trustledger.core.payments.TransactionCursor;
import com.trustledger.core.payments.TransactionPage;
import com.trustledger.core.payments.TransactionSlice;
import com.trustledger.core.payments.WalletTransactionRecord;
import java.util.List;

/**
 * A history result before JSON mapping. Offset mode fills {@code page}, {@code totalElements} and
 * {@code totalPages}; cursor mode fills {@code nextCursor} (absent on the last slice) and leaves those {@code null}.
 */
public record TransactionHistoryView(List<WalletTransactionRecord> items, Integer page, int size, Long totalElements,
        Integer totalPages, String nextCursor) {

    static TransactionHistoryView of(TransactionPage page) {
        return new TransactionHistoryView(page.items(), page.page(), page.size(), page.totalElements(),
                page.totalPages(), null);
    }

    static TransactionHistoryView of(TransactionSlice slice, int size) {
        String next = slice.hasNext() ? HistoryCursor.encode(TransactionCursor.of(slice.items().getLast())) : null;
        return new TransactionHistoryView(slice.items(), null, size, null, null, next);
    }
}
