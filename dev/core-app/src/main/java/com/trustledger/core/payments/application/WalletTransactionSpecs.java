package com.trustledger.core.payments.application;

import com.trustledger.core.payments.TransactionCursor;
import com.trustledger.core.payments.TransactionFilter;
import com.trustledger.core.payments.TransactionSort;
import com.trustledger.core.payments.domain.WalletTransaction;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * JPA Specifications and sort orders for the transaction history (FR-S-1, M11). Every query is limited to one
 * wallet first, which is what lets PostgreSQL use the per-wallet indexes (V26) in each branch of the view.
 */
final class WalletTransactionSpecs {

    static final String WALLET_ID = "walletId";
    static final String SOURCE_ID = "sourceId";
    static final String TYPE = "type";
    static final String AMOUNT = "amountMinor";
    static final String CREATED_AT = "createdAt";
    static final String VISIBLE = "visible";

    private WalletTransactionSpecs() {
    }

    /**
     * The filter's wallet, time window and type, and always only rows the owner may see ({@code visible}, V25): every
     * history query starts here, so the visibility rule can't be forgotten.
     */
    static Specification<WalletTransaction> matching(TransactionFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get(WALLET_ID), filter.walletId()));
            predicates.add(cb.isTrue(root.get(VISIBLE)));
            if (filter.fromInclusive() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get(CREATED_AT), filter.fromInclusive()));
            }
            if (filter.toExclusive() != null) {
                predicates.add(cb.lessThan(root.get(CREATED_AT), filter.toExclusive()));
            }
            if (filter.type() != null) {
                predicates.add(cb.equal(root.get(TYPE), filter.type()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * Keyset: rows strictly older than the cursor in (createdAt DESC, sourceId DESC) order, i.e.
     * {@code created_at < c OR (created_at = c AND source_id < id)}. The redundant {@code created_at <= c} in front
     * becomes an index condition, so the index scan starts at the cursor instead of filtering every newer row.
     */
    static Specification<WalletTransaction> olderThan(TransactionCursor cursor) {
        return (root, query, cb) -> {
            Instant at = cursor.createdAt();
            UUID id = cursor.id();
            return cb.and(cb.lessThanOrEqualTo(root.get(CREATED_AT), at),
                    cb.or(cb.lessThan(root.get(CREATED_AT), at),
                            cb.and(cb.equal(root.get(CREATED_AT), at), cb.lessThan(root.<UUID>get(SOURCE_ID), id))));
        };
    }

    /** The requested order, then newest first and the row ID as tie-breakers (stable pages). */
    static Sort orderOf(TransactionSort sort) {
        Sort newestFirst = Sort.by(Sort.Order.desc(CREATED_AT), Sort.Order.desc(SOURCE_ID));
        return switch (sort.field()) {
            case CREATED_AT -> sort.ascending()
                    ? Sort.by(Sort.Order.asc(CREATED_AT), Sort.Order.asc(SOURCE_ID))
                    : newestFirst;
            case AMOUNT -> Sort.by(sort.ascending() ? Sort.Order.asc(AMOUNT) : Sort.Order.desc(AMOUNT))
                    .and(newestFirst);
        };
    }
}
