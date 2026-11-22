package com.trustledger.core.payments.domain;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/** Port for the history view; implemented by Spring Data in {@code payments.infrastructure}. */
public interface WalletTransactionRepository {

    /** A page plus the total count (offset pagination). */
    Page<WalletTransaction> findAll(Specification<WalletTransaction> spec, Pageable pageable);

    /** At most {@code limit} rows in {@code sort} order, without a count query (keyset pagination). */
    List<WalletTransaction> findFirst(Specification<WalletTransaction> spec, Sort sort, int limit);
}
