package com.trustledger.core.notification.domain;

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
import org.hibernate.annotations.UuidGenerator;

/**
 * One notification to one user (FR-N-1): created PENDING, then SENT, FAILED or SKIPPED, exactly once. Holds no
 * personal data: the recipient is a user ID and {@code failureReason} is a code or an exception class name.
 */
@Entity
@Table(name = "notifications", schema = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    static final int MAX_DEDUP_KEY_LENGTH = 128;
    static final int MAX_REASON_LENGTH = 128;

    /** Failure reason when the user has no address we may write to (no e-mail, erased, unknown). */
    public static final String NO_CONTACT = "NO_CONTACT";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 32)
    private NotificationType type;

    @Column(name = "dedup_key", nullable = false, updatable = false, length = MAX_DEDUP_KEY_LENGTH)
    private String dedupKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private NotificationStatus status;

    @Column(name = "failure_reason", length = MAX_REASON_LENGTH)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    /** A notification about to be sent. */
    public static Notification pending(UUID userId, NotificationType type, String dedupKey, Instant now) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(now, "now");
        if (dedupKey == null || dedupKey.isBlank() || dedupKey.length() > MAX_DEDUP_KEY_LENGTH) {
            throw new IllegalArgumentException("dedupKey must be 1-" + MAX_DEDUP_KEY_LENGTH + " characters");
        }
        Notification notification = new Notification();
        notification.userId = userId;
        notification.type = type;
        notification.dedupKey = dedupKey;
        notification.status = NotificationStatus.PENDING;
        notification.createdAt = now;
        notification.updatedAt = now;
        return notification;
    }

    /** The mail server accepted the message. */
    public void markSent(Instant now) {
        requirePending();
        status = NotificationStatus.SENT;
        sentAt = Objects.requireNonNull(now, "now");
        updatedAt = now;
    }

    /** Sending failed; {@code reason} is a code or exception class name, never an exception message. */
    public void markFailed(String reason, Instant now) {
        finish(NotificationStatus.FAILED, reason, now);
    }

    /** There was nobody to send it to (e.g. {@link #NO_CONTACT}). */
    public void markSkipped(String reason, Instant now) {
        finish(NotificationStatus.SKIPPED, reason, now);
    }

    private void finish(NotificationStatus outcome, String reason, Instant now) {
        requirePending();
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        status = outcome;
        failureReason = reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    private void requirePending() {
        if (status != NotificationStatus.PENDING) {
            throw new IllegalStateException("Notification is already " + status);
        }
    }
}
