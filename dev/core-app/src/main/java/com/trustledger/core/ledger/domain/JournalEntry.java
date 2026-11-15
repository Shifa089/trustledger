package com.trustledger.core.ledger.domain;

import com.trustledger.core.ledger.Direction;
import com.trustledger.core.ledger.EntryType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * One journal entry and its lines (FR-L-1). Append-only (FR-L-2): {@link Immutable} here, and the database
 * refuses UPDATE and DELETE as well. Lines can only be added before the entry is saved.
 * <p>
 * The aggregate deliberately does not check that it is balanced: that is the posting service's job
 * ({@code LedgerPostingService.post}), and the deferred database trigger checks it again at commit.
 */
@Entity
@Immutable
@Table(name = "journal_entries", schema = "ledger")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JournalEntry {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, updatable = false, length = 16)
    private EntryType entryType;

    @Column(name = "reference_type", nullable = false, updatable = false, length = 32)
    private String referenceType;

    @Column(name = "reference_id", nullable = false, updatable = false)
    private UUID referenceId;

    @Column(name = "reverses_entry_id", updatable = false)
    private UUID reversesEntryId;

    /** Why a reversal was posted. Free text: never logged, never in {@code toString}. */
    @Getter(AccessLevel.NONE)
    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Getter(AccessLevel.NONE)
    @OneToMany(mappedBy = "entry", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    @OrderBy("lineNo ASC")
    private List<JournalLine> lines = new ArrayList<>();

    /**
     * A new, empty entry. Add its lines with {@link #addLine} before saving it.
     *
     * @param reversesEntryId and {@code reason}: set for a {@link EntryType#REVERSAL}, {@code null} otherwise
     */
    public static JournalEntry start(EntryType entryType, String referenceType, UUID referenceId,
            UUID reversesEntryId, String reason, Instant now) {
        Objects.requireNonNull(entryType, "entryType");
        boolean reversal = entryType == EntryType.REVERSAL;
        if (reversal != (reversesEntryId != null) || reversal != (reason != null)) {
            throw new IllegalArgumentException("reversesEntryId and reason are required for, and only for, a REVERSAL");
        }
        JournalEntry entry = new JournalEntry();
        entry.entryType = entryType;
        entry.referenceType = Objects.requireNonNull(referenceType, "referenceType");
        entry.referenceId = Objects.requireNonNull(referenceId, "referenceId");
        entry.reversesEntryId = reversesEntryId;
        entry.reason = reason;
        entry.postedAt = Objects.requireNonNull(now, "now");
        entry.createdAt = now;
        return entry;
    }

    /**
     * Appends a line numbered {@code 1..n} in the order added.
     *
     * @param amountMinor must be positive: the direction carries the sign
     * @throws IllegalStateException once the entry has been saved
     */
    public JournalLine addLine(LedgerAccount account, Direction direction, long amountMinor) {
        if (id != null) {
            throw new IllegalStateException("Journal entry " + id + " is already posted and can't change");
        }
        JournalLine line = JournalLine.of(this, account, direction, amountMinor, lines.size() + 1, postedAt);
        lines.add(line);
        return line;
    }

    /** The lines in {@code line_no} order. Read-only. */
    public List<JournalLine> lines() {
        return Collections.unmodifiableList(lines);
    }

    public String reason() {
        return reason;
    }

    public boolean isReversal() {
        return entryType == EntryType.REVERSAL;
    }
}
