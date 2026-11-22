package com.trustledger.core.payments.application;

import com.trustledger.core.payments.TransactionCursor;
import com.trustledger.core.payments.TransactionFilter;
import com.trustledger.core.payments.TransactionPage;
import com.trustledger.core.payments.TransactionSlice;
import com.trustledger.core.payments.TransactionSort;
import com.trustledger.core.payments.WalletTransactionRecord;
import com.trustledger.core.payments.domain.WalletTransaction;
import com.trustledger.core.payments.domain.WalletTransactionRepository;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a wallet's transaction history (FR-S-1) from the {@code payments.wallet_transactions} view: by offset (page
 * and total) or by cursor (keyset, newest first, no count). The caller has already checked who owns the wallet and
 * the page size bounds.
 */
@Service
public class WalletTransactionQueryService {

    private final WalletTransactionRepository rows;

    public WalletTransactionQueryService(WalletTransactionRepository rows) {
        this.rows = rows;
    }

    @Transactional(readOnly = true)
    public TransactionPage page(TransactionFilter filter, TransactionSort sort, int page, int size) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(sort, "sort");
        requireBounds(page, size);
        Page<WalletTransaction> result = rows.findAll(WalletTransactionSpecs.matching(filter),
                PageRequest.of(page, size, WalletTransactionSpecs.orderOf(sort)));
        return new TransactionPage(result.getContent().stream().map(WalletTransactionQueryService::record).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    /** Fetches one row more than asked: its presence is {@code hasNext}, without a count query. */
    @Transactional(readOnly = true)
    public TransactionSlice after(TransactionFilter filter, TransactionCursor cursor, int size) {
        Objects.requireNonNull(filter, "filter");
        requireBounds(0, size);
        Specification<WalletTransaction> spec = WalletTransactionSpecs.matching(filter);
        if (cursor != null) {
            spec = spec.and(WalletTransactionSpecs.olderThan(cursor));
        }
        List<WalletTransaction> found = rows.findFirst(spec,
                WalletTransactionSpecs.orderOf(TransactionSort.NEWEST_FIRST), size + 1);
        boolean hasNext = found.size() > size;
        return new TransactionSlice(found.stream().limit(size).map(WalletTransactionQueryService::record).toList(),
                hasNext);
    }

    static WalletTransactionRecord record(WalletTransaction row) {
        return new WalletTransactionRecord(row.getSourceId(), row.getType(), row.getStatus(), row.amount(),
                row.getCounterpartyWalletId(), row.getNote(), row.getCreatedAt());
    }

    private static void requireBounds(int page, int size) {
        if (page < 0 || size < 1) {
            throw new IllegalArgumentException("page must be >= 0 and size >= 1");
        }
    }
}
