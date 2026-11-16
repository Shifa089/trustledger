package com.trustledger.core.ledger.application;

import com.trustledger.core.ledger.Direction;
import com.trustledger.core.ledger.PostingCommand;
import com.trustledger.core.ledger.PostingLine;
import com.trustledger.core.ledger.domain.JournalEntry;
import com.trustledger.core.ledger.domain.JournalEntryRepository;
import com.trustledger.core.ledger.domain.LedgerAccount;
import com.trustledger.core.ledger.domain.LedgerAccountRepository;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Clock;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts journal entries (FR-L-1). The one place where a {@link PostingCommand} becomes rows in
 * {@code ledger.journal_entries} and {@code ledger.journal_lines}.
 * <p>
 * The tests in {@code LedgerPostingServiceIT} and {@code LedgerReversalIT} describe the contract. What
 * {@link #post} does, in order:
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
        List<PostingLine> lines = command.lines();
        Currency currency = validateShape(lines);

        if (entries.existsByReferenceTypeAndReferenceIdAndEntryType(command.referenceType(), command.referenceId(),
                command.entryType())) {
            throw duplicate(command);
        }

        Map<String, LedgerAccount> byCode = resolveAccounts(lines, currency);

        JournalEntry entry = JournalEntry.start(command.entryType(), command.referenceType(), command.referenceId(),
                command.reversesEntryId(), command.reason(), clock.instant());
        for (PostingLine line : lines) {
            entry.addLine(byCode.get(line.accountCode()), line.direction(), line.amount().amountMinor());
        }

        try {
            return entries.saveAndFlush(entry).getId();
        } catch (DataIntegrityViolationException e) {
            // A concurrent post of the same reference won the unique key between the check above and this insert.
            throw duplicate(command);
        }
    }

    /** Step 1: checks that need no database. Returns the one currency of every line. */
    private static Currency validateShape(List<PostingLine> lines) {
        if (lines.size() < 2) {
            throw invalid("A journal entry needs at least 2 lines");
        }
        Currency currency = lines.getFirst().amount().currency();
        long debits = 0;
        long credits = 0;
        for (PostingLine line : lines) {
            Money amount = line.amount();
            if (amount.isZero()) {
                throw invalid("Every line amount must be above zero");
            }
            if (!amount.currency().equals(currency)) {
                throw invalid("Every line must be in the same currency");
            }
            try {
                if (line.direction() == Direction.DEBIT) {
                    debits = Math.addExact(debits, amount.amountMinor());
                } else {
                    credits = Math.addExact(credits, amount.amountMinor());
                }
            } catch (ArithmeticException e) {
                throw invalid("Line amounts are too large");
            }
        }
        if (debits != credits) {
            throw invalid("Debits and credits must be equal");
        }
        return currency;
    }

    /** Step 3: every account in one query; each must exist and be in the lines' currency. */
    private Map<String, LedgerAccount> resolveAccounts(List<PostingLine> lines, Currency currency) {
        Set<String> codes = new LinkedHashSet<>();
        lines.forEach(line -> codes.add(line.accountCode()));
        Map<String, LedgerAccount> byCode = new HashMap<>();
        accounts.findAllByCodeIn(codes).forEach(account -> byCode.put(account.getCode(), account));
        for (String code : codes) {
            LedgerAccount account = byCode.get(code);
            if (account == null) {
                throw new TrustLedgerException(ErrorCode.NOT_FOUND, "Ledger account " + code + " does not exist");
            }
            if (!account.getCurrency().equals(currency)) {
                throw invalid("Ledger account " + code + " is not in " + currency.getCurrencyCode());
            }
        }
        return byCode;
    }

    private static TrustLedgerException invalid(String detail) {
        return new TrustLedgerException(ErrorCode.VALIDATION_FAILED, detail);
    }

    private static TrustLedgerException duplicate(PostingCommand command) {
        return new TrustLedgerException(ErrorCode.DUPLICATE_POSTING, "Entry " + command.entryType() + " for "
                + command.referenceType() + " " + command.referenceId() + " is already posted");
    }
}
