package com.trustledger.core.ledger.infrastructure;

import com.trustledger.core.ledger.domain.JournalEntry;
import com.trustledger.core.ledger.domain.JournalEntryRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data implementation of {@link JournalEntryRepository}. */
public interface JpaJournalEntryRepository extends JpaRepository<JournalEntry, UUID>, JournalEntryRepository {
}
