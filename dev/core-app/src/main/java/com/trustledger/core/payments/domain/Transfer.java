package com.trustledger.core.payments.domain;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * A P2P transfer between two wallets (FR-P-1 to FR-P-3): the aggregate that guards the transfer's state machine.
 * <pre>
 * INITIATED --complete-----------------------------&gt; COMPLETED
 * INITIATED --holdForReview--&gt; HELD_FOR_REVIEW --approve--&gt; COMPLETED
 *                                              --reject---&gt; REJECTED
 * INITIATED --fail---------------------------------&gt; FAILED
 * </pre>
 * Any other step throws {@link ErrorCode#INVALID_STATE_TRANSITION}. Invariants, also enforced by database checks
 * ({@code V11}): a positive INR amount, sender differs from receiver, a journal entry exactly when COMPLETED, and
 * who and when on every review decision.
 * <p>
 * The transfer only records state; the money itself moves through {@code WalletApi} and {@code LedgerApi} in
 * the same transaction (SPEC §4 "Transfer flow").
 */
@Entity
@Table(name = "transfers", schema = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transfer {

    public static final int MAX_NOTE_LENGTH = 140;

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "sender_wallet_id", nullable = false, updatable = false)
    private UUID senderWalletId;

    @Column(name = "receiver_wallet_id", nullable = false, updatable = false)
    private UUID receiverWalletId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private Currency currency;

    @Column(name = "note", updatable = false, length = MAX_NOTE_LENGTH)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransferStatus status;

    @Column(name = "journal_entry_id")
    private UUID journalEntryId;

    /** The compliance officer who approved or rejected a held transfer. */
    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * A new INITIATED transfer. Checks what doesn't need the database: a positive INR amount, two different
     * wallets, and an optional note of at most {@value #MAX_NOTE_LENGTH} characters (blank means none).
     */
    public static Transfer initiate(UUID senderWalletId, UUID receiverWalletId, Money amount, String note,
            Instant now) {
        Objects.requireNonNull(senderWalletId, "senderWalletId");
        Objects.requireNonNull(receiverWalletId, "receiverWalletId");
        Objects.requireNonNull(amount, "amount");
        if (senderWalletId.equals(receiverWalletId)) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED, "You cannot send money to your own wallet.");
        }
        if (!Money.INR.equals(amount.currency())) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED, "Transfers must be in INR.");
        }
        if (amount.isZero()) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED, "Amount must be greater than zero.");
        }
        Transfer transfer = new Transfer();
        transfer.senderWalletId = senderWalletId;
        transfer.receiverWalletId = receiverWalletId;
        transfer.amountMinor = amount.amountMinor();
        transfer.currency = amount.currency();
        transfer.note = normaliseNote(note);
        transfer.status = TransferStatus.INITIATED;
        transfer.createdAt = Objects.requireNonNull(now, "now");
        transfer.updatedAt = now;
        return transfer;
    }

    public Money amount() {
        return Money.of(amountMinor, currency);
    }

    /** INITIATED -> COMPLETED: the journal entry is posted and both wallets are updated. */
    public void complete(UUID journalEntryId, Instant now) {
        Objects.requireNonNull(journalEntryId, "journalEntryId");
        requireStatus(TransferStatus.INITIATED, "complete");
        this.journalEntryId = journalEntryId;
        moveTo(TransferStatus.COMPLETED, now);
    }

    /** INITIATED -> HELD_FOR_REVIEW (FR-P-3): at or above the high-value threshold; the amount is on hold. */
    public void holdForReview(Instant now) {
        requireStatus(TransferStatus.INITIATED, "hold");
        moveTo(TransferStatus.HELD_FOR_REVIEW, now);
    }

    /** INITIATED -> FAILED: a check failed after the transfer was created. */
    public void fail(Instant now) {
        requireStatus(TransferStatus.INITIATED, "fail");
        moveTo(TransferStatus.FAILED, now);
    }

    /** HELD_FOR_REVIEW -> COMPLETED: a compliance officer approved, and the posting happened now. */
    public void approve(UUID reviewer, UUID journalEntryId, Instant now) {
        Objects.requireNonNull(reviewer, "reviewer");
        Objects.requireNonNull(journalEntryId, "journalEntryId");
        requireStatus(TransferStatus.HELD_FOR_REVIEW, "approve");
        this.journalEntryId = journalEntryId;
        review(reviewer, now);
        moveTo(TransferStatus.COMPLETED, now);
    }

    /** HELD_FOR_REVIEW -> REJECTED: a compliance officer rejected; the caller releases the hold. */
    public void reject(UUID reviewer, Instant now) {
        Objects.requireNonNull(reviewer, "reviewer");
        requireStatus(TransferStatus.HELD_FOR_REVIEW, "reject");
        review(reviewer, now);
        moveTo(TransferStatus.REJECTED, now);
    }

    /** Whether the wallet is the sender or the receiver (ownership check for {@code GET /transfers/{id}}). */
    public boolean involves(UUID walletId) {
        return senderWalletId.equals(walletId) || receiverWalletId.equals(walletId);
    }

    private void review(UUID reviewer, Instant now) {
        this.reviewedBy = reviewer;
        this.reviewedAt = Objects.requireNonNull(now, "now");
    }

    private void requireStatus(TransferStatus expected, String action) {
        if (status != expected) {
            throw new TrustLedgerException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot " + action + " a transfer in status " + status + ".");
        }
    }

    private void moveTo(TransferStatus target, Instant now) {
        this.status = target;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    private static String normaliseNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String text = note.strip();
        if (text.length() > MAX_NOTE_LENGTH) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED,
                    "The note must be at most " + MAX_NOTE_LENGTH + " characters.");
        }
        return text;
    }
}
