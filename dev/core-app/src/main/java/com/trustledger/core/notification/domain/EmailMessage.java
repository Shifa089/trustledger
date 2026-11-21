package com.trustledger.core.notification.domain;

/** A rendered plain-text e-mail, without its recipient. */
public record EmailMessage(String subject, String body) {

    public EmailMessage {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body must not be blank");
        }
    }
}
