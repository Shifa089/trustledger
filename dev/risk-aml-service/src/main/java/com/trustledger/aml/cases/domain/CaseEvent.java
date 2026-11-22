package com.trustledger.aml.cases.domain;

import com.trustledger.events.AmlCaseStatusChangedV1;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.annotations.UuidGenerator;

/**
 * "This case opened" or "this case finished", written in the same transaction as the change (the outbox, ADR 0007).
 * {@code CaseEventRelay} sends it as {@link AmlCaseStatusChangedV1} after the commit, so core-app can refuse an erasure
 * while the wallet has an open case (FR-PR-2). The ID is the message's {@code eventId}, fixed once.
 * <p>
 * Only OPEN and the two finished states are recorded: assignment (INVESTIGATING) does not change whether the case is
 * open. JPA only inserts and reads; the relay sets {@code published_at} and {@code attempts} with plain SQL.
 */
@Entity
@Table(name = "case_events", schema = "aml")
public class CaseEvent {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Column(name = "status", nullable = false, updatable = false, length = 32)
    private String status;

    @Column(name = "correlation_id", updatable = false, length = 64)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "published_at", insertable = false, updatable = false)
    private Instant publishedAt;

    @Column(name = "attempts", insertable = false, updatable = false)
    private int attempts;

    protected CaseEvent() {
        // JPA
    }

    private CaseEvent(AmlCase amlCase, String correlationId, Instant now) {
        Objects.requireNonNull(amlCase, "amlCase");
        if (amlCase.getStatus() == CaseStatus.INVESTIGATING) {
            throw new IllegalArgumentException("Only opening and finishing a case are sent");
        }
        this.caseId = Objects.requireNonNull(amlCase.getId(), "case must be saved first");
        this.walletId = amlCase.getWalletId();
        this.status = amlCase.getStatus().name();
        // Comes from a request or Kafka header: kept only if it is a plain token.
        this.correlationId = correlationId != null && SAFE_ID.matcher(correlationId).matches() ? correlationId : null;
        this.occurredAt = Objects.requireNonNull(now, "now");
    }

    /** The event for the case's current status (OPEN, CLOSED_FALSE_POSITIVE or ESCALATED). */
    public static CaseEvent of(AmlCase amlCase, String correlationId, Instant now) {
        return new CaseEvent(amlCase, correlationId, now);
    }

    /** The Kafka contract. Pure: a re-send builds the same record. */
    public AmlCaseStatusChangedV1 toV1() {
        return toV1(id, occurredAt, walletId, caseId, status);
    }

    /** Also used by the relay, which reads the row with plain SQL. */
    public static AmlCaseStatusChangedV1 toV1(UUID id, Instant occurredAt, UUID walletId, UUID caseId,
            String status) {
        return new AmlCaseStatusChangedV1(id, occurredAt, walletId, caseId, status,
                CaseStatus.valueOf(status).isActive());
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

    public String getStatus() {
        return status;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }
}
