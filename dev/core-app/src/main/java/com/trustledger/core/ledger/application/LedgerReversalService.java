package com.trustledger.core.ledger.application;

import com.trustledger.core.ledger.PostingCommand;
import com.trustledger.core.ledger.PostingLine;
import com.trustledger.core.ledger.domain.JournalEntry;
import com.trustledger.core.ledger.domain.JournalEntryRepository;
import com.trustledger.core.ledger.domain.JournalLine;
import com.trustledger.core.ledger.domain.LedgerAccount;
import com.trustledger.core.ledger.domain.LedgerAccountRepository;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-L-3: corrections never edit an entry; they post a REVERSAL with every line on the other side (SPEC §4
 * posting rules). The reversal goes through {@link LedgerPostingService#post}, so it passes the same checks as
 * any posting, and its reference {@code ("JOURNAL_ENTRY", originalId, REVERSAL)} makes a second reversal of
 * the same entry a duplicate, even when two run at once.
 */
@Service
public class LedgerReversalService {

    public static final int MAX_REASON_LENGTH = 500;

    private static final Logger log = LoggerFactory.getLogger(LedgerReversalService.class);

    private final JournalEntryRepository entries;
    private final LedgerAccountRepository accounts;
    private final LedgerPostingService posting;

    public LedgerReversalService(JournalEntryRepository entries, LedgerAccountRepository accounts,
            LedgerPostingService posting) {
        this.entries = entries;
        this.accounts = accounts;
        this.posting = posting;
    }

    /**
     * @return the reversal entry's ID
     * @throws TrustLedgerException {@code VALIDATION_FAILED} (reason), {@code NOT_FOUND} (entry),
     *         {@code INVALID_STATE_TRANSITION} (the entry is itself a reversal), {@code DUPLICATE_POSTING}
     *         (already reversed)
     */
    @Transactional
    public UUID reverse(UUID entryId, String reason) {
        Objects.requireNonNull(entryId, "entryId");
        String text = requireReason(reason);
        JournalEntry original = entries.findById(entryId)
                .orElseThrow(() -> new TrustLedgerException(ErrorCode.NOT_FOUND, "Journal entry not found."));
        if (original.isReversal()) {
            throw new TrustLedgerException(ErrorCode.INVALID_STATE_TRANSITION,
                    "A reversal can't be reversed; post a new entry instead.");
        }
        if (entries.existsByReversesEntryId(entryId)) {
            throw new TrustLedgerException(ErrorCode.DUPLICATE_POSTING, "This entry was already reversed.");
        }
        UUID reversalId = posting.post(PostingCommand.reversal(entryId, text, flipped(original)));
        log.info("Journal entry {} reversed by entry {}", entryId, reversalId);
        return reversalId;
    }

    /** Each line of the original on the other side, same account and amount, same order. */
    private List<PostingLine> flipped(JournalEntry original) {
        List<JournalLine> lines = original.lines();
        Map<UUID, LedgerAccount> byId = accounts.findAllByIdIn(lines.stream().map(JournalLine::getAccountId)
                .collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(LedgerAccount::getId, Function.identity()));
        return lines.stream().map(line -> {
            LedgerAccount account = byId.get(line.getAccountId());
            if (account == null) {
                throw new IllegalStateException("Journal line " + line.getId() + " points to a missing account");
            }
            return new PostingLine(account.getCode(), line.getDirection().opposite(),
                    Money.of(line.getAmountMinor(), account.getCurrency()));
        }).toList();
    }

    static String requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.strip().length() > MAX_REASON_LENGTH) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED,
                    "A reason of 1-" + MAX_REASON_LENGTH + " characters is required.");
        }
        return reason.strip();
    }
}
