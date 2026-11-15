package com.trustledger.core.kyc.domain;

import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.shared.crypto.EncryptedLocalDateConverter;
import com.trustledger.core.shared.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * A KYC case (FR-KYC-1 to FR-KYC-3): the aggregate that owns the KYC state machine (see {@link KycStatus}).
 * Every state change is an explicit method; a move the current state does not allow throws
 * {@link ErrorCode#INVALID_STATE_TRANSITION}, and the same officer verifying and approving throws
 * {@link ErrorCode#MAKER_CHECKER_VIOLATION} (the database check {@code ck_kyc_cases_maker_checker}
 * enforces the same rule a second time).
 * <p>
 * Personal data (name, PAN, date of birth, address) is encrypted at rest; getters return plaintext, so
 * callers must mask before returning or logging it.
 */
@Entity
@Table(name = "kyc_cases", schema = "kyc")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KycCase {

    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_ADDRESS_LENGTH = 500;
    public static final int MAX_REASON_LENGTH = 500;

    /** Same rule as {@code @ValidPan}; the aggregate checks its own invariants too. */
    private static final Pattern PAN = Pattern.compile("[A-Z]{3}[ABCFGHLJPT][A-Z][0-9]{4}[A-Z]");

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, updatable = false, length = 8)
    private KycLevel level;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private KycStatus status;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "full_name_enc", columnDefinition = "text")
    private String fullName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "pan_enc", columnDefinition = "text")
    private String pan;

    @Column(name = "pan_hash", length = 64)
    private String panHash;

    @Convert(converter = EncryptedLocalDateConverter.class)
    @Column(name = "dob_enc", columnDefinition = "text")
    private LocalDate dateOfBirth;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "address_enc", columnDefinition = "text")
    private String address;

    @Column(name = "verified_by")
    private UUID verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejected_by")
    private UUID rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejection_reason", length = MAX_REASON_LENGTH)
    private String rejectionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * Minimum KYC (FR-KYC-1): name as on PAN, PAN with its blind index, and date of birth. Starts SUBMITTED;
     * the application approves it with {@link #autoApproveMin(Instant)} after its checks pass.
     */
    public static KycCase submitMin(UUID userId, String fullName, String pan, String panHash, LocalDate dateOfBirth,
            Instant now) {
        String name = requireText("fullName", fullName, MAX_NAME_LENGTH);
        if (pan == null || !PAN.matcher(pan).matches()) {
            throw new IllegalArgumentException("pan must be a valid upper-case PAN");
        }
        if (panHash == null || panHash.isBlank()) {
            throw new IllegalArgumentException("pan and its blind index must be set together");
        }
        KycCase kycCase = newCase(userId, KycLevel.MIN, now);
        kycCase.fullName = name;
        kycCase.pan = pan;
        kycCase.panHash = panHash;
        kycCase.dateOfBirth = Objects.requireNonNull(dateOfBirth, "dateOfBirth");
        return kycCase;
    }

    /** Full KYC details (FR-KYC-2). Documents are attached separately; the case waits for an officer. */
    public static KycCase submitFull(UUID userId, String address, Instant now) {
        String text = requireText("address", address, MAX_ADDRESS_LENGTH);
        KycCase kycCase = newCase(userId, KycLevel.FULL, now);
        kycCase.address = text;
        return kycCase;
    }

    /** MIN: SUBMITTED -> APPROVED by the system once the format and age checks passed. No officer involved. */
    public void autoApproveMin(Instant now) {
        requireLevel(KycLevel.MIN, "auto-approve");
        requireStatus(KycStatus.SUBMITTED, "auto-approve");
        this.status = KycStatus.APPROVED;
        this.approvedAt = now;
        touch(now);
    }

    /** FULL, maker step: SUBMITTED -> VERIFIED by officer A. */
    public void verify(UUID officerId, Instant now) {
        Objects.requireNonNull(officerId, "officerId");
        requireLevel(KycLevel.FULL, "verify");
        requireStatus(KycStatus.SUBMITTED, "verify");
        requireNotOwnCase(officerId);
        this.status = KycStatus.VERIFIED;
        this.verifiedBy = officerId;
        this.verifiedAt = now;
        touch(now);
    }

    /** FULL, checker step: VERIFIED -> APPROVED by officer B, who must not be the maker (four eyes). */
    public void approve(UUID officerId, Instant now) {
        Objects.requireNonNull(officerId, "officerId");
        requireLevel(KycLevel.FULL, "approve");
        requireStatus(KycStatus.VERIFIED, "approve");
        requireNotOwnCase(officerId);
        if (officerId.equals(verifiedBy)) {
            throw new TrustLedgerException(ErrorCode.MAKER_CHECKER_VIOLATION,
                    "The officer who verified a KYC case cannot also approve it.");
        }
        this.status = KycStatus.APPROVED;
        this.approvedBy = officerId;
        this.approvedAt = now;
        touch(now);
    }

    /** FULL: SUBMITTED or VERIFIED -> REJECTED by either officer, with a reason. */
    public void reject(UUID officerId, String reason, Instant now) {
        Objects.requireNonNull(officerId, "officerId");
        requireLevel(KycLevel.FULL, "reject");
        if (status != KycStatus.SUBMITTED && status != KycStatus.VERIFIED) {
            throw invalid("reject");
        }
        requireNotOwnCase(officerId);
        if (reason == null || reason.isBlank() || reason.strip().length() > MAX_REASON_LENGTH) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED,
                    "A rejection reason of 1-" + MAX_REASON_LENGTH + " characters is required.");
        }
        this.status = KycStatus.REJECTED;
        this.rejectedBy = officerId;
        this.rejectedAt = now;
        this.rejectionReason = reason.strip();
        touch(now);
    }

    /**
     * Documents can be attached to a FULL case only while it waits for the maker (SUBMITTED). Once an officer
     * has verified it, the evidence is frozen: a later upload would bypass the review.
     */
    public void requireAcceptsDocuments() {
        requireLevel(KycLevel.FULL, "add documents to");
        requireStatus(KycStatus.SUBMITTED, "add documents to");
    }

    public boolean isOwnedBy(UUID candidateUserId) {
        return userId.equals(candidateUserId);
    }

    public boolean isApproved() {
        return status == KycStatus.APPROVED;
    }

    private static KycCase newCase(UUID userId, KycLevel level, Instant now) {
        KycCase kycCase = new KycCase();
        kycCase.userId = Objects.requireNonNull(userId, "userId");
        kycCase.level = level;
        kycCase.status = KycStatus.SUBMITTED;
        kycCase.createdAt = Objects.requireNonNull(now, "now");
        kycCase.updatedAt = now;
        return kycCase;
    }

    private void requireLevel(KycLevel expected, String action) {
        if (level != expected) {
            throw invalid(action);
        }
    }

    private void requireStatus(KycStatus expected, String action) {
        if (status != expected) {
            throw invalid(action);
        }
    }

    /** An officer who is also a customer must never review their own case. */
    private void requireNotOwnCase(UUID officerId) {
        if (officerId.equals(userId)) {
            throw new TrustLedgerException(ErrorCode.MAKER_CHECKER_VIOLATION,
                    "An officer cannot review their own KYC case.");
        }
    }

    private TrustLedgerException invalid(String action) {
        return new TrustLedgerException(ErrorCode.INVALID_STATE_TRANSITION,
                "Cannot " + action + " a " + level + " KYC case in status " + status + ".");
    }

    private void touch(Instant now) {
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    private static String requireText(String field, String value, int maxLength) {
        if (value == null || value.isBlank() || value.strip().length() > maxLength) {
            throw new IllegalArgumentException(field + " must be 1-" + maxLength + " characters");
        }
        return value.strip();
    }
}
