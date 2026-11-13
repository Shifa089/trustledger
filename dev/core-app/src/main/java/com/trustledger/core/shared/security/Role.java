package com.trustledger.core.shared.security;

/**
 * Application roles (SPEC §2 actors). They are Keycloak realm roles and become Spring Security
 * authorities {@code ROLE_<name>}, so {@code hasRole("ADMIN")} and {@code @PreAuthorize("hasRole('ADMIN')")}
 * work. SYSTEM is not a role: scheduled jobs and the AML service do not call the API with user tokens.
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
