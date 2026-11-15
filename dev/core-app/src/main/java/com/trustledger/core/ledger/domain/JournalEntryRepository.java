package com.trustledger.core.ledger.domain;

import com.trustledger.core.ledger.EntryType;
import java.util.Optional;
import java.util.UUID;

/** Port for journal entries; implemented by Spring Data in {@code ledger.infrastructure}. Insert-only. */
public interface JournalEntryRepository {

    Optional<JournalEntry> findById(UUID id);

    /** Whether this business event was already posted (the {@code uk_journal_entries_reference} key). */
    boolean existsByReferenceTypeAndReferenceIdAndEntryType(String referenceType, UUID referenceId,
            EntryType entryType);

    /** Whether {@code entryId} was already reversed. */
    boolean existsByReversesEntryId(UUID entryId);

    /**
     * Inserts the entry and its lines and flushes at once, so the unique reference (a duplicate posting) fails
     * here, inside the posting code, and not later at commit.
     */
    JournalEntry saveAndFlush(JournalEntry entry);
}
