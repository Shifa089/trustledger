package com.trustledger.core.payments.infrastructure;

import com.trustledger.core.payments.domain.WalletTransaction;
import com.trustledger.core.payments.domain.WalletTransactionId;
import com.trustledger.core.payments.domain.WalletTransactionRepository;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

/** Spring Data implementation of {@link WalletTransactionRepository}: read-only, Specifications only. */
public interface JpaWalletTransactionRepository extends Repository<WalletTransaction, WalletTransactionId>,
        JpaSpecificationExecutor<WalletTransaction>, WalletTransactionRepository {

    @Override
    default List<WalletTransaction> findFirst(Specification<WalletTransaction> spec, Sort sort, int limit) {
        return findBy(spec, query -> query.sortBy(sort).limit(limit).all());
    }
}
