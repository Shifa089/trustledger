package com.trustledger.core.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * One consent decision (FR-ID-3): who, which purpose, which policy version, granted or withdrawn,
 * when and from which IP. Rows are never changed: a withdrawal is a new row with {@code granted=false}.
 * {@code @Immutable} stops Hibernate from issuing UPDATEs; a database trigger rejects UPDATE and DELETE.
 */
@Entity
@Table(name = "consents", schema = "identity")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Consent {

    static final int MAX_IP_LENGTH = 45;

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    /**
     * Assigned by the database (identity column); orders the history exactly. Only used in queries, so it
     * is not read back after insert (Hibernate's {@code @Generated} re-read is not allowed on an
     * {@code @Immutable} entity); it is {@code null} on a freshly saved instance.
     */
    @Column(name = "seq", insertable = false, updatable = false)
    private Long seq;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, updatable = false, length = 16)
    private ConsentPurpose purpose;

    @Column(name = "policy_version", nullable = false, updatable = false, length = 32)
    private String policyVersion;

    @Column(name = "granted", nullable = false, updatable = false)
    private boolean granted;

    @Column(name = "captured_at", nullable = false, updatable = false)
    private Instant capturedAt;

    @Column(name = "ip", updatable = false, length = MAX_IP_LENGTH)
    private String ip;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static Consent record(UUID userId, ConsentChoice choice, String ip, Instant now) {
        Consent consent = new Consent();
        consent.userId = Objects.requireNonNull(userId, "userId");
        consent.purpose = choice.purpose();
        consent.policyVersion = choice.policyVersion();
        consent.granted = choice.granted();
        consent.capturedAt = Objects.requireNonNull(now, "now");
        consent.createdAt = now;
        // An over-long value cannot be an IP address; storing a truncated one would be misleading.
        consent.ip = ip == null || ip.length() > MAX_IP_LENGTH ? null : ip;
        return consent;
    }
}
