package com.trustledger.aml.facts.infrastructure;

import com.trustledger.aml.facts.domain.TransactionFact;
import com.trustledger.aml.facts.domain.TransactionFactRepository;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Spring Data implementation of {@link TransactionFactRepository}. Extends the bare {@link Repository}, not
 * {@code JpaRepository}, so no delete method exists for an append-only table.
 */
public interface JpaTransactionFactRepository extends Repository<TransactionFact, UUID>, TransactionFactRepository {
}
