package com.trustledger.aml.cases.domain;

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
 * {@code uq_cases_one_open_per_wallet} enforces it. The workflow (assign, close, escalate) comes with the case
 * endpoints; for now a case is opened and collects alerts.
 */
@Entity
@Table(name = "cases", schema = "aml")
public class AmlCase {

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
