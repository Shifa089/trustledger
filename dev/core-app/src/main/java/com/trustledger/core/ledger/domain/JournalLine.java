package com.trustledger.core.ledger.domain;

import com.trustledger.core.ledger.Direction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/** One line of a {@link JournalEntry}: an account, a side and a positive amount in paise. Append-only. */
@Entity
@Immutable
@Table(name = "journal_lines", schema = "ledger")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JournalLine {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Getter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false, updatable = false)
    private JournalEntry entry;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false, length = 6)
    private Direction direction;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    static JournalLine of(JournalEntry entry, LedgerAccount account, Direction direction, long amountMinor,
            int lineNo, Instant now) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(account.getId(), "account must be saved before it is used in a line");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("A journal line amount must be positive: " + amountMinor);
        }
        JournalLine line = new JournalLine();
        line.entry = Objects.requireNonNull(entry, "entry");
        line.accountId = account.getId();
        line.direction = Objects.requireNonNull(direction, "direction");
        line.amountMinor = amountMinor;
        line.lineNo = lineNo;
        line.createdAt = Objects.requireNonNull(now, "now");
        return line;
    }
}
