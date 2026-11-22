package com.trustledger.aml.cases.domain;

import com.trustledger.aml.rules.domain.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One rule hit (SPEC §4 {@code aml.alerts}, FR-AML-2), attached to its wallet's active case. Never changed after it
 * is raised: it is evidence for the case and the STR report.
 */
@Entity
@Table(name = "alerts", schema = "aml")
public class Alert {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Column(name = "rule_code", nullable = false, updatable = false, length = 2)
    private String ruleCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, updatable = false, length = 8)
    private Severity severity;

    @Column(name = "details", nullable = false, updatable = false)
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Alert() {
        // JPA
    }

    private Alert(AmlCase amlCase, String ruleCode, Severity severity, String details, Instant now) {
        Objects.requireNonNull(amlCase, "amlCase");
        if (!amlCase.isActive()) {
            throw new IllegalArgumentException("An alert joins an active case only");
        }
        this.caseId = Objects.requireNonNull(amlCase.getId(), "case must be saved first");
        this.walletId = amlCase.getWalletId();
        this.ruleCode = Objects.requireNonNull(ruleCode, "ruleCode");
        this.severity = Objects.requireNonNull(severity, "severity");
        this.details = Objects.requireNonNull(details, "details");
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    /** The rule's hit, on the wallet's active case; the alert's wallet is the case's wallet. */
    public static Alert raise(AmlCase amlCase, String ruleCode, Severity severity, String details, Instant now) {
        return new Alert(amlCase, ruleCode, severity, details, now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getDetails() {
        return details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
