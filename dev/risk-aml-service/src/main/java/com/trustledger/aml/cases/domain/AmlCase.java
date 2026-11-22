package com.trustledger.aml.cases.domain;

import com.trustledger.aml.shared.ErrorCode;
import com.trustledger.aml.shared.TrustLedgerException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * An AML case (SPEC §4 {@code aml.cases}, FR-AML-2): the alerts of one wallet, grouped for one investigation. A
 * wallet has at most one active (OPEN or INVESTIGATING) case; the partial unique index
 * {@code uq_cases_one_open_per_wallet} enforces it.
 * <p>
 * Workflow (FR-AML-4): OPEN → INVESTIGATING ({@link #assign}) → CLOSED_FALSE_POSITIVE ({@link #closeAsFalsePositive})
 * or ESCALATED ({@link #escalate}). Every other step is {@code INVALID_STATE_TRANSITION} (409). Only the assigned
 * officer finishes a case, and always with notes, which become {@code resolution}.
 */
@Entity
@Table(name = "cases", schema = "aml")
public class AmlCase {

    public static final int MAX_NOTES_LENGTH = 2000;

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private CaseStatus status;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "resolution")
    private String resolution;

    @Column(name = "ai_summary")
    private String aiSummary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected AmlCase() {
        // JPA
    }

    private AmlCase(UUID walletId, Instant now) {
        this.walletId = Objects.requireNonNull(walletId, "walletId");
        this.status = CaseStatus.OPEN;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    /** A new case for the wallet's first alert since its last case was finished. */
    public static AmlCase open(UUID walletId, Instant now) {
        return new AmlCase(walletId, now);
    }

    /** A new alert joined this case: it counts as activity, so the officer's list can sort by it. */
    public void alertRaised(Instant now) {
        if (!status.isActive()) {
            throw new IllegalStateException("A finished case takes no new alerts");
        }
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    /**
     * FR-AML-4: OPEN → INVESTIGATING, or a reassignment while INVESTIGATING. A finished case can't be picked up
     * again: new alerts for the wallet open a new case.
     */
    public void assign(UUID assignee, Instant now) {
        Objects.requireNonNull(assignee, "assignee");
        if (!status.isActive()) {
            throw invalid("assign");
        }
        this.assignedTo = assignee;
        this.status = CaseStatus.INVESTIGATING;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    /** FR-AML-4: INVESTIGATING → CLOSED_FALSE_POSITIVE, by the assigned officer, with notes. */
    public void closeAsFalsePositive(UUID officer, String notes, Instant now) {
        finish(CaseStatus.CLOSED_FALSE_POSITIVE, officer, notes, now);
    }

    /** FR-AML-4: INVESTIGATING → ESCALATED (an STR is filed), by the assigned officer, with notes. */
    public void escalate(UUID officer, String notes, Instant now) {
        finish(CaseStatus.ESCALATED, officer, notes, now);
    }

    private void finish(CaseStatus target, UUID officer, String notes, Instant now) {
        Objects.requireNonNull(officer, "officer");
        if (status != CaseStatus.INVESTIGATING) {
            throw invalid(target == CaseStatus.ESCALATED ? "escalate" : "close");
        }
        if (!officer.equals(assignedTo)) {
            throw new TrustLedgerException(ErrorCode.NOT_CASE_ASSIGNEE,
                    "Only the officer the case is assigned to can finish it.");
        }
        this.resolution = requireNotes(notes);
        this.status = target;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    /** Notes become {@code resolution}; they may quote personal data, so they never go into a log or a message. */
    static String requireNotes(String notes) {
        if (notes == null || notes.isBlank()) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED, "Notes are required.");
        }
        String text = notes.strip();
        if (text.length() > MAX_NOTES_LENGTH) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED,
                    "Notes must be at most " + MAX_NOTES_LENGTH + " characters.");
        }
        return text;
    }

    private TrustLedgerException invalid(String action) {
        return new TrustLedgerException(ErrorCode.INVALID_STATE_TRANSITION,
                "A case in status " + status + " can't " + action + ".");
    }

    public boolean isFinished() {
        return !status.isActive();
    }

    public boolean isActive() {
        return status.isActive();
    }

    public UUID getId() {
        return id;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public UUID getAssignedTo() {
        return assignedTo;
    }

    public String getResolution() {
        return resolution;
    }

    public String getAiSummary() {
        return aiSummary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
