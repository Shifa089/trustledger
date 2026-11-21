package com.trustledger.aml.shared.security;

// Copied from core-app (com.trustledger.core.shared.security), not shared through event-contracts: that module
// carries Kafka contracts only and has no dependencies. Keep the two copies in step (ADR 0007).

/**
 * Application roles (SPEC §2 actors). They are Keycloak realm roles and become Spring Security
 * authorities {@code ROLE_<name>}, so {@code hasRole("ADMIN")} and {@code @PreAuthorize("hasRole('ADMIN')")}
 * work. Same four roles as core-app: one Keycloak realm for both services. The AML endpoints need COMPLIANCE_OFFICER.
 */
public enum Role {

    CUSTOMER,
    COMPLIANCE_OFFICER,
    ADMIN,
    AUDITOR;

    public static final String AUTHORITY_PREFIX = "ROLE_";

    /** The Spring Security authority, e.g. {@code ROLE_ADMIN}. */
    public String authority() {
        return AUTHORITY_PREFIX + name();
    }
}
