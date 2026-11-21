package com.trustledger.core.identity;

import com.trustledger.core.shared.masking.Masking;
import java.util.Objects;
import java.util.UUID;

/**
 * The address to send a user's notification to (FR-N-1), from {@link IdentityApi#findNotificationContact}. Holds a
 * plaintext e-mail address, so it is used only to address the message: never logged, never stored.
 * {@link #toString()} masks the address, so an accidental log line or assertion message does not leak it.
 */
public record NotificationContact(UUID userId, String email) {

    public NotificationContact {
        Objects.requireNonNull(userId, "userId");
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
    }

    @Override
    public String toString() {
        return "NotificationContact[userId=" + userId + ", email=" + Masking.email(email) + "]";
    }
}
