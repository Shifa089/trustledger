package com.trustledger.core.notification.application;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code trustledger.notification.*} (FR-N-1). The SMTP server itself is Boot's {@code spring.mail.*}. Startup
 * fails on unsafe values.
 *
 * @param from sender address of every notification, e.g. {@code no-reply@trustledger.local}
 * @param zone time zone for times shown in e-mails (Asia/Kolkata: our customers are in India)
 */
@ConfigurationProperties("trustledger.notification")
public record NotificationProperties(String from, ZoneId zone) {

    public NotificationProperties {
        if (from == null || from.isBlank() || from.indexOf('@') < 1 || from.strip().contains(" ")) {
            throw new IllegalArgumentException("trustledger.notification.from must be an e-mail address");
        }
        if (zone == null) {
            throw new IllegalArgumentException("trustledger.notification.zone must be set");
        }
        from = from.strip();
    }
}
