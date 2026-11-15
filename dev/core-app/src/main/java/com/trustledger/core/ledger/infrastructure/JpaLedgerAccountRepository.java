package com.trustledger.core.ledger.infrastructure;

import com.trustledger.core.ledger.domain.LedgerAccount;
import com.trustledger.core.ledger.domain.LedgerAccountRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data implementation of {@link LedgerAccountRepository}. */
public interface JpaLedgerAccountRepository extends JpaRepository<LedgerAccount, UUID>, LedgerAccountRepository {
}
