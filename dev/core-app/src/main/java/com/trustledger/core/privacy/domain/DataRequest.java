package com.trustledger.core.privacy.domain;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * A customer's data export or erasure request (FR-PR-2).
 * <pre>
 * PENDING --completeExport(key)--&gt; COMPLETED
 * PENDING --fail(reason)---------&gt; FAILED
 * erasure(user) ------------------&gt; COMPLETED   (synchronous: written once the erasure has happened)
 * </pre>
 * Any other step throws {@link ErrorCode#INVALID_STATE_TRANSITION}. Invariants, also enforced by database checks
 * ({@code V21}, {@code V24}): only a completed export has a result key; a finished request has a completion time;
 * only a failed one has a reason; a user has at most one completed erasure. Holds no personal data.
 */
@Entity
@Table(name = "data_requests", schema = "privacy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DataRequest {

    static final int MAX_REASON_LENGTH = 64;

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 16)
    private DataRequestType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DataRequestStatus status;

    @Column(name = "result_s3_key", length = 256)
    private String resultS3Key;

    @Column(name = "failure_reason", length = MAX_REASON_LENGTH)
    private String failureReason;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** A new export request, waiting to be processed. */
    public static DataRequest export(UUID userId, Instant now) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(now, "now");
        DataRequest request = new DataRequest();
        request.userId = userId;
        request.type = DataRequestType.EXPORT;
        request.status = DataRequestStatus.PENDING;
        request.requestedAt = now;
        request.createdAt = now;
        request.updatedAt = now;
        return request;
    }

    /**
     * A completed erasure. Erasure runs synchronously in the request's transaction, so its record is written once it
     * has happened: COMPLETED, finished at {@code now}. A refused erasure leaves no row (the 409 is the answer).
     */
    public static DataRequest erasure(UUID userId, Instant now) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(now, "now");
        DataRequest request = new DataRequest();
        request.userId = userId;
        request.type = DataRequestType.ERASURE;
        request.status = DataRequestStatus.COMPLETED;
        request.requestedAt = now;
        request.completedAt = now;
        request.createdAt = now;
        request.updatedAt = now;
        return request;
    }

    /**
     * Where this export's file goes: {@code exports/<userId>/<requestId>.json}. Deterministic, so processing the
     * same request again (a redelivered event) overwrites the same object instead of leaving a second copy.
     */
    public String exportKey() {
        requireType(DataRequestType.EXPORT);
        Objects.requireNonNull(id, "id (save the request first)");
        return "exports/" + userId + "/" + id + ".json";
    }

    /** The export file is stored under {@link #exportKey()}. */
    public void completeExport(Instant now) {
        requireType(DataRequestType.EXPORT);
        requirePending("complete");
        resultS3Key = exportKey();
        finish(DataRequestStatus.COMPLETED, now);
    }

    /** Processing failed; {@code reason} is an error code or exception class name, never an exception message. */
    public void fail(String reason, Instant now) {
        requirePending("fail");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        failureReason = reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason;
        finish(DataRequestStatus.FAILED, now);
    }

    public boolean isPending() {
        return status == DataRequestStatus.PENDING;
    }

    /** A completed export whose file can be downloaded. */
    public boolean hasDownload() {
        return type == DataRequestType.EXPORT && status == DataRequestStatus.COMPLETED && resultS3Key != null;
    }

    private void finish(DataRequestStatus outcome, Instant now) {
        Objects.requireNonNull(now, "now");
        status = outcome;
        // A clock that went backwards must not break the database check completed_at >= requested_at.
        completedAt = now.isBefore(requestedAt) ? requestedAt : now;
        updatedAt = completedAt;
    }

    private void requirePending(String action) {
        if (status != DataRequestStatus.PENDING) {
            throw new TrustLedgerException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot " + action + " a data request that is " + status + ".");
        }
    }

    private void requireType(DataRequestType expected) {
        if (type != expected) {
            throw new IllegalStateException("Not an " + expected + " request: " + type);
        }
    }
}
