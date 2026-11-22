package com.trustledger.aml.cases.domain;

import com.trustledger.aml.rules.domain.Severity;
import com.trustledger.events.AccountFreezeRequestedV1;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * FR-AML-3: "freeze this wallet", written in the same transaction as the HIGH alert that caused it (the outbox,
 * ADR 0007). {@code FreezeRequestRelay} sends it as {@link AccountFreezeRequestedV1} after the commit; until then
 * {@code publishedAt} is empty. The ID is the message's {@code eventId}, fixed once, so every re-send carries the
 * same one.
 * <p>
 * JPA only inserts and reads it; the relay sets {@code published_at} and {@code attempts} with plain SQL under
 * {@code FOR UPDATE SKIP LOCKED}, so those columns are not updatable here.
 */
@Entity
@Table(name = "freeze_requests", schema = "aml")
public class FreezeRequest {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "alert_id", nullable = false, updatable = false)
    private UUID alertId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Column(name = "rule_code", nullable = false, updatable = false, length = 2)
    private String ruleCode;

    @Column(name = "correlation_id", updatable = false, length = 64)
    private String correlationId;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "published_at", insertable = false, updatable = false)
    private Instant publishedAt;

    @Column(name = "attempts", insertable = false, updatable = false)
    private int attempts;

    protected FreezeRequest() {
        // JPA
    }

    private FreezeRequest(Alert alert, String correlationId, Instant now) {
        Objects.requireNonNull(alert, "alert");
        if (alert.getSeverity() != Severity.HIGH) {
            throw new IllegalArgumentException("Only a HIGH alert requests a freeze");
        }
        this.alertId = Objects.requireNonNull(alert.getId(), "alert must be saved first");
        this.caseId = alert.getCaseId();
        this.walletId = alert.getWalletId();
        this.ruleCode = alert.getRuleCode();
        this.correlationId = correlationId;
        this.requestedAt = Objects.requireNonNull(now, "now");
    }

    /** The freeze request of a HIGH alert; {@code correlationId} may be {@code null}. */
    public static FreezeRequest forAlert(Alert alert, String correlationId, Instant now) {
        return new FreezeRequest(alert, correlationId, now);
    }

    /** The Kafka contract. Pure: a re-send builds the same record. */
    public AccountFreezeRequestedV1 toV1() {
        return new AccountFreezeRequestedV1(id, requestedAt, walletId, caseId, ruleCode);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAlertId() {
        return alertId;
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

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }
}
