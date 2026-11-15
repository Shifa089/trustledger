package com.trustledger.core.ledger.application;

import com.trustledger.core.ledger.PostingCommand;
import com.trustledger.core.ledger.domain.JournalEntryRepository;
import com.trustledger.core.ledger.domain.LedgerAccountRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts journal entries (FR-L-1). The one place where a {@link PostingCommand} becomes rows in
 * {@code ledger.journal_entries} and {@code ledger.journal_lines}.
 * <p>
 * <b>To be written by hand (M5).</b> The tests in {@code LedgerPostingServiceIT} and {@code LedgerReversalIT}
 * describe the contract. What {@link #post} must do, in order:
 * <ol>
 *   <li><b>Shape</b>, without touching the database ({@code VALIDATION_FAILED}): at least 2 lines; every amount
 *       above zero; every line in the same currency; debit total = credit total, summed with
 *       {@link Math#addExact} so a huge amount can't wrap around.</li>
 *   <li><b>Duplicate</b>: {@code entries.existsByReferenceTypeAndReferenceIdAndEntryType(...)} →
 *       {@code DUPLICATE_POSTING}. The friendly, fast path.</li>
 *   <li><b>Accounts</b>: {@code accounts.findAllByCodeIn(distinct codes)} in one query; a missing code →
 *       {@code NOT_FOUND}; an account whose currency differs from the lines' → {@code VALIDATION_FAILED}.</li>
 *   <li><b>Build</b>: {@code JournalEntry.start(type, referenceType, referenceId, reversesEntryId, reason,
 *       clock.instant())}, then {@code addLine(account, direction, amountMinor)} for each line, in order.</li>
 *   <li><b>Save</b>: {@code entries.saveAndFlush(entry)}. A concurrent post of the same reference passes step 2
 *       and then fails here on {@code uk_journal_entries_reference}: catch
 *       {@link org.springframework.dao.DataIntegrityViolationException} and throw {@code DUPLICATE_POSTING}.</li>
 *   <li><b>Return</b> the entry's ID. At commit the deferred trigger checks the balance once more.</li>
 * </ol>
 * No locks and no remote calls here: the caller (payments) holds the wallet locks and owns the transaction.
 */
@Service
public class LedgerPostingService {

    private final LedgerAccountRepository accounts;
    private final JournalEntryRepository entries;
    private final Clock clock;

    public LedgerPostingService(LedgerAccountRepository accounts, JournalEntryRepository entries, Clock clock) {
        this.accounts = accounts;
        this.entries = entries;
        this.clock = clock;
    }

    /**
     * Validates and saves one journal entry, joining the caller's transaction.
     *
     * @return the new entry's ID
     * @throws com.trustledger.core.shared.TrustLedgerException {@code VALIDATION_FAILED}, {@code NOT_FOUND} or
     *         {@code DUPLICATE_POSTING}
     */
    @Transactional
    public UUID post(PostingCommand command) {
        // TODO M5: written by hand. See the class Javadoc for the steps.
        throw new UnsupportedOperationException("LedgerPostingService.post() is not implemented yet");
    }
}
