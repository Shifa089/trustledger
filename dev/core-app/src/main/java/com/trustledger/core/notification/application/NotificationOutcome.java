package com.trustledger.core.notification.application;

/** What {@link NotificationService#send} did. DUPLICATE: a row for this key exists, nothing was sent again. */
public enum NotificationOutcome {
    SENT,
    FAILED,
    SKIPPED,
    DUPLICATE
}
