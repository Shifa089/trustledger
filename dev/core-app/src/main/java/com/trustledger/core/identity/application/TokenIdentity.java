package com.trustledger.core.identity.application;

import java.util.Objects;

/**
 * The identity claims we copy from a Keycloak access token at provisioning: {@code sub} (required),
 * {@code email} and {@code name} (optional). Built by the web layer so the application layer does not
 * depend on Spring Security's {@code Jwt} type.
 */
public record TokenIdentity(String subject, String email, String fullName) {

    public TokenIdentity {
        Objects.requireNonNull(subject, "subject");
    }

    /** No personal data in logs (CLAUDE.md), even by accident through string concatenation. */
    @Override
    public String toString() {
        return "TokenIdentity[subject=" + subject + ", email=****, fullName=****]";
    }
}
