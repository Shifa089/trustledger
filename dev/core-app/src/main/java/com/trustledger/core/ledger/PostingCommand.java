package com.trustledger.core.ledger;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A request to post one journal entry (FR-L-1). {@code (referenceType, referenceId, entryType)} identifies the
 * business event, so posting the same event twice is refused.
 * <p>
 * This record only checks its own shape. The ledger rules (at least 2 lines, debits = credits, one currency,
 * positive amounts, known accounts) are checked by the posting service, because they need the accounts.
 *
 * @param referenceType   upper-case kind of the business object, e.g. {@code TOPUP} or {@code TRANSFER}
 * @param reversesEntryId set only for a {@link EntryType#REVERSAL}
 * @param reason          set only for a {@link EntryType#REVERSAL}; free text, never logged
 */
public record PostingCommand(EntryType entryType, String referenceType, UUID referenceId, List<PostingLine> lines,
        UUID reversesEntryId, String reason) {

    /** Reference type of a reversal: the business object being undone is the original entry. */
    public static final String REVERSAL_REFERENCE_TYPE = "JOURNAL_ENTRY";

    private static final Pattern REFERENCE_TYPE = Pattern.compile("[A-Z_]{1,32}");

    public PostingCommand {
        Objects.requireNonNull(entryType, "entryType");
        Objects.requireNonNull(referenceType, "referenceType");
        Objects.requireNonNull(referenceId, "referenceId");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (!REFERENCE_TYPE.matcher(referenceType).matches()) {
            throw new IllegalArgumentException("referenceType must be 1-32 upper-case letters or underscores");
        }
        boolean reversal = entryType == EntryType.REVERSAL;
        if (reversal != (reversesEntryId != null)) {
            throw new IllegalArgumentException("reversesEntryId is required for, and only for, a REVERSAL");
        }
        if (reversal != (reason != null)) {
            throw new IllegalArgumentException("reason is required for, and only for, a REVERSAL");
        }
    }

    /** A business posting such as a top-up or a transfer. */
    public static PostingCommand of(EntryType entryType, String referenceType, UUID referenceId,
            List<PostingLine> lines) {
        return new PostingCommand(entryType, referenceType, referenceId, lines, null, null);
    }

    /** The reversal of entry {@code originalEntryId}, referenced by that entry's ID. */
    public static PostingCommand reversal(UUID originalEntryId, String reason, List<PostingLine> lines) {
        return new PostingCommand(EntryType.REVERSAL, REVERSAL_REFERENCE_TYPE, originalEntryId, lines,
                originalEntryId, reason);
    }

    public boolean isReversal() {
        return entryType == EntryType.REVERSAL;
    }
}
