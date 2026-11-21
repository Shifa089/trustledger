package com.trustledger.core.notification.domain;

/**
 * PENDING while the mail is being sent (inside the listener's transaction, so never visible after a crash);
 * SENT when the SMTP server accepted it; FAILED when sending threw; SKIPPED when there was nobody to send it to.
 */
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED,
    SKIPPED
}
