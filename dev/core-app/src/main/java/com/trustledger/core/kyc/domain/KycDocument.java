package com.trustledger.core.kyc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * Metadata of one uploaded KYC document (FR-KYC-2). The bytes live in S3 under {@link #getS3Key()}; the
 * SHA-256 lets anyone check later that the stored object is exactly what the customer uploaded.
 * Immutable once recorded: there are no state-changing methods.
 */
@Entity
@Table(name = "kyc_documents", schema = "kyc")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KycDocument {

    /** Hard upper bound (5 MB), the same as the database check {@code ck_kyc_documents_size}. */
    public static final long MAX_SIZE_BYTES = 5L * 1024 * 1024;

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, updatable = false, length = 16)
    private DocumentType type;

    @Column(name = "s3_key", nullable = false, updatable = false, length = 200)
    private String s3Key;

    @Column(name = "content_type", nullable = false, updatable = false, length = 32)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false, updatable = false, length = 64)
    private String sha256;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private UUID uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static KycDocument record(UUID caseId, DocumentType type, String s3Key, DocumentFormat format,
            long sizeBytes, String sha256, UUID uploadedBy, Instant now) {
        if (sizeBytes < 1 || sizeBytes > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("sizeBytes must be 1-" + MAX_SIZE_BYTES);
        }
        if (sha256 == null || !SHA256_HEX.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be 64 lower-case hex characters");
        }
        if (s3Key == null || s3Key.isBlank()) {
            throw new IllegalArgumentException("s3Key is required");
        }
        KycDocument document = new KycDocument();
        document.caseId = Objects.requireNonNull(caseId, "caseId");
        document.type = Objects.requireNonNull(type, "type");
        document.s3Key = s3Key;
        document.contentType = Objects.requireNonNull(format, "format").contentType();
        document.sizeBytes = sizeBytes;
        document.sha256 = sha256;
        document.uploadedBy = Objects.requireNonNull(uploadedBy, "uploadedBy");
        document.createdAt = Objects.requireNonNull(now, "now");
        return document;
    }
}
