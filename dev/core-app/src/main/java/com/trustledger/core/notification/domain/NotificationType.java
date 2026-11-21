package com.trustledger.core.notification.domain;

/** What a notification is about (FR-N-1). Stored as text; the V17 CHECK lists the same values. */
public enum NotificationType {
    TRANSFER_SENT,
    TRANSFER_RECEIVED,
    TOPUP_CAPTURED,
    KYC_UPGRADED,
    WALLET_FROZEN;

    /** The de-duplication key for this type and its source, e.g. {@code TRANSFER_SENT:<transferId>}. */
    public String dedupKey(Object... parts) {
        StringBuilder key = new StringBuilder(name());
        for (Object part : parts) {
            key.append(':').append(part);
        }
        return key.toString();
    }
}
