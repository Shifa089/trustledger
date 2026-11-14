package com.trustledger.core.identity;

import java.util.UUID;

/**
 * What other modules may know about a user: the ID and masked display values only (FR-PR-1), e.g. to
 * show "Sending to T*** C***, ******3210" before a transfer is confirmed.
 */
public record UserView(UUID id, Status status, String maskedFullName, String maskedPhone) {

    /** Mirrors the identity module's internal status without exposing the domain type. */
    public enum Status {
        ACTIVE,
        ERASED
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}
