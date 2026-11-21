package com.trustledger.core.notification.application;

import com.trustledger.core.notification.domain.EmailMessage;
import com.trustledger.core.notification.domain.NotificationType;
import java.util.Objects;
import java.util.UUID;

/** One e-mail to one user, already rendered; {@code dedupKey} identifies it across event redeliveries. */
public record NotificationRequest(UUID userId, NotificationType type, String dedupKey, EmailMessage message) {

    public NotificationRequest {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(dedupKey, "dedupKey");
        Objects.requireNonNull(message, "message");
    }
}
